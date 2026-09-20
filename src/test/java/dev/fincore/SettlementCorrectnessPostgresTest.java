package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.application.BusinessConflictException;
import dev.fincore.application.FenceRejectedException;
import dev.fincore.application.SettlementService;
import dev.fincore.application.ShardLeaseService;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import dev.fincore.domain.SettlementStatus;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.infrastructure.persistence.mapper.LedgerMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.infrastructure.persistence.mapper.SettlementMapper;
import dev.fincore.infrastructure.persistence.mapper.ShardLeaseMapper;
import dev.fincore.web.ApiExceptionHandler;
import dev.fincore.web.SettlementController;
import dev.fincore.support.TestExecutors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 冲突、过期围栏和回滚必须保持全部金融状态不变。 */
class SettlementCorrectnessPostgresTest extends AbstractLocalPostgresTestSupport {
    /** 并发重投总次数；预期只有一笔成功资金效果。 */
    private static final int DUPLICATE_ATTEMPTS = 6;
    /** 重放失败终态时必须保持不变的资金事实表。 */
    private static final List<String> FINANCIAL_FACT_TABLES = List.of(
        "account", "ledger_transaction", "ledger_entry", "state_audit", "outbox_event");
    /** 回滚时逐行比较的全部金融事务状态。 */
    private static final List<String> FINANCIAL_STATE_TABLES = List.of(
        "account", "settlement_order", "inbox_message", "ledger_transaction",
        "ledger_entry", "state_audit", "outbox_event");
    private SettlementService settlements;
    private ShardLeaseService leases;
    private SimpleMeterRegistry metrics;

    /** 使用生产 Mapper 与 Spring 管理的真实 PostgreSQL 事务。 */
    @BeforeEach
    void service() {
        metrics = new SimpleMeterRegistry();
        leases = new ShardLeaseService(sessions.getMapper(ShardLeaseMapper.class));
        settlements = service(sessions.getMapper(OutboxMapper.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"payer", "payee", "feeAccount", "asset", "amount", "fee"})
    void changedBusinessPayloadIsConflictWithoutAnyFinancialMutation(String field) {
        SettlementCommand original = command();
        settle(original);
        Map<String, List<Map<String, Object>>> before = snapshot();
        SettlementCommand changed = changed(original, "new-" + original.messageId(), field);
        assertThrows(BusinessConflictException.class, () -> settle(changed));
        assertEquals(before, snapshot());
    }

    @ParameterizedTest
    @ValueSource(strings = {"payer", "payee", "feeAccount", "asset", "amount", "fee"})
    void changedMessagePayloadIsConflictWithoutAnyFinancialMutation(String field) {
        SettlementCommand original = command();
        settle(original);
        Map<String, List<Map<String, Object>>> before = snapshot();
        SettlementCommand changed = changed(original, original.messageId(), field);
        assertThrows(BusinessConflictException.class, () -> settle(changed));
        assertEquals(before, snapshot());
    }

    @Test
    void sameMessageCannotBeReboundToAnotherBusiness() {
        SettlementCommand original = command();
        settle(original);
        Map<String, List<Map<String, Object>>> before = snapshot();
        SettlementCommand changed = copy(original, original.messageId(), "another-" + original.businessKey(),
            original.amount(), original.fee());
        assertThrows(BusinessConflictException.class, () -> settle(changed));
        assertEquals(before, snapshot());
    }

    @Test
    void newMessageForExistingBusinessCanBeReplayedAndMoneyMovesOnce() {
        SettlementCommand original = command();
        settle(original);
        SettlementCommand duplicate = copy(original, "new-" + original.messageId(), original.businessKey(),
            original.amount(), original.fee());
        assertTrue(settle(duplicate).duplicate());
        assertTrue(settle(duplicate).duplicate());
        assertEquals(SettlementStatus.SUCCESS, settlements.get(original.businessKey()).status());
        assertSingleSettlement(original);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inbox_message WHERE message_id IN (?, ?)",
            Integer.class, original.messageId(), duplicate.messageId()));
        assertEquals(original.businessKey(), sessions.getMapper(SettlementMapper.class)
            .findByMessageId(duplicate.messageId()).businessKey());
    }

    @Test
    void numericallyEqualAmountsRemainIdempotentAcrossJsonScales() {
        SettlementCommand original = command();
        settle(original);
        assertTrue(settle(copy(original, original.messageId(), original.businessKey(),
            new BigDecimal("10.000"), new BigDecimal("1.000"))).duplicate());
        assertTrue(settle(copy(original, "new-" + original.messageId(), original.businessKey(),
            new BigDecimal("10.00"), new BigDecimal("1.00"))).duplicate());
        assertSingleSettlement(original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryBeforeWorkerCommitReturnsExplicitNotFound() throws Exception {
        SettlementController controller = new SettlementController(settlements, mock(KafkaTemplate.class),
            "settlement", new ConcurrencyProperties());
        MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build()
            .perform(get("/api/settlements/not-yet-visible"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_VISIBLE"))
            .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void successMetricOnlyCountsCommittedResults() {
        SettlementCommand command = command();
        Map<String, List<Map<String, Object>>> before = snapshot();
        transactions.executeWithoutResult(status -> {
            settlements.settle(command);
            assertEquals(0.0, metrics.counter("fincore.settlement.success").count());
            status.setRollbackOnly();
        });
        assertEquals(before, snapshot());
        assertEquals(0.0, metrics.counter("fincore.settlement.success").count());
        settle(command);
        assertEquals(1.0, metrics.counter("fincore.settlement.success").count());
    }

    @Test
    void lateOutboxFailureRollsBackEveryFinancialWrite() {
        SettlementCommand command = command();
        Map<String, List<Map<String, Object>>> before = snapshot();
        OutboxMapper broken = spy(sessions.getMapper(OutboxMapper.class));
        doThrow(new IllegalStateException("injected outbox failure"))
            .when(broken).insert(any(), anyString(), anyString(), anyString());
        SettlementService failing = service(broken);
        assertThrows(IllegalStateException.class,
            () -> transactions.execute(status -> failing.settle(command)));
        assertEquals(before, snapshot());
        assertEquals(0.0, metrics.counter("fincore.settlement.success").count());
    }

    @Test
    void failedSettlementRemainsTerminalOnMessageAndBusinessReplay() {
        SettlementCommand original = command();
        SettlementCommand insufficient = copy(original, original.messageId(), original.businessKey(),
            new BigDecimal("150"), original.fee());
        assertEquals(SettlementStatus.FAILED, settle(insufficient).status());
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertTrue(settle(insufficient).duplicate());
        assertEquals(before, snapshot());
        SettlementCommand retry = copy(insufficient, "new-" + insufficient.messageId(),
            insufficient.businessKey(), insufficient.amount(), insufficient.fee());
        assertEquals(SettlementStatus.FAILED, settle(retry).status());
        Map<String, List<Map<String, Object>>> afterAlias = snapshot();
        assertTrue(settle(retry).duplicate());
        assertEquals(afterAlias, snapshot());
        for (String table : FINANCIAL_FACT_TABLES) {
            assertEquals(before.get(table), afterAlias.get(table), table);
        }
        assertEquals(1.0, metrics.counter("fincore.settlement.failure").count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentDuplicateKeysHaveOneFinancialEffect(boolean sameMessage) throws Exception {
        SettlementCommand original = command();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = TestExecutors.fixedThreadPool(DUPLICATE_ATTEMPTS, "settlement-duplicates-")) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<SettlementOutcome>>();
            try {
                for (int i = 0; i < DUPLICATE_ATTEMPTS; i++) {
                    SettlementCommand candidate = sameMessage ? original : copy(original,
                        i + "-" + original.messageId(), original.businessKey(), original.amount(), original.fee());
                    futures.add(executor.submit(() -> {
                        start.await();
                        return settle(candidate);
                    }));
                }
            } finally {
                start.countDown();
            }
            int duplicates = 0;
            for (var future : futures) {
                SettlementOutcome outcome = future.get(10, TimeUnit.SECONDS);
                assertEquals(SettlementStatus.SUCCESS, outcome.status());
                duplicates += outcome.duplicate() ? 1 : 0;
            }
            assertEquals(5, duplicates);
        }
        assertSingleSettlement(original);
        assertEquals(1.0, metrics.counter("fincore.settlement.success").count());
        assertEquals(5.0, metrics.counter("fincore.settlement.duplicate").count());
    }

    @Test
    void oppositeTransfersWithForeignKeyLocksDoNotDeadlock() throws Exception {
        UUID payer = account(new BigDecimal("100"));
        UUID payee = account(new BigDecimal("100"));
        UUID fee = account(BigDecimal.ZERO);
        SettlementCommand forward = transfer(payer, payee, fee, new BigDecimal("10"));
        SettlementCommand reverse = transfer(payee, payer, fee, new BigDecimal("10"));
        List<SettlementOutcome> outcomes = simultaneousAfterOrderInsert(forward, reverse);
        assertEquals(List.of(SettlementStatus.SUCCESS, SettlementStatus.SUCCESS),
            outcomes.stream().map(SettlementOutcome::status).toList());
        assertBalance(payer, "99");
        assertBalance(payee, "99");
        assertBalance(fee, "2");
        for (SettlementCommand c : List.of(forward, reverse)) {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE business_key=?",
                Integer.class, c.businessKey()));
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM ledger_entry e JOIN ledger_transaction t "
                + "USING (transaction_id) WHERE business_key=?", Integer.class, c.businessKey()));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id=?",
                Integer.class, c.businessKey()));
        }
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertTrue(settle(forward).duplicate());
        assertTrue(settle(reverse).duplicate());
        assertEquals(before, snapshot());
    }

    @Test
    void concurrentSharedPayerCannotOverspend() throws Exception {
        SettlementCommand original = command();
        SettlementCommand first = copy(original, original.messageId(), original.businessKey(),
            new BigDecimal("80"), original.fee());
        SettlementCommand second = transfer(first.payerAccountId(), first.payeeAccountId(),
            first.feeAccountId(), first.amount());
        List<SettlementOutcome> outcomes = simultaneousAfterOrderInsert(first, second);
        assertEquals(1, outcomes.stream().filter(o -> o.status() == SettlementStatus.SUCCESS).count());
        assertEquals(1, outcomes.stream().filter(o -> o.status() == SettlementStatus.FAILED).count());
        assertBalance(first.payerAccountId(), "19");
        assertBalance(first.payeeAccountId(), "80");
        assertBalance(first.feeAccountId(), "1");
        assertEquals(1.0, metrics.counter("fincore.settlement.success").count());
        assertEquals(1.0, metrics.counter("fincore.settlement.failure").count());
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertTrue(settle(first).duplicate());
        assertTrue(settle(second).duplicate());
        assertEquals(before, snapshot());
    }

    @Test
    void frozenPayerCannotLeavePartialFinancialState() {
        SettlementCommand command = command();
        jdbc.update("UPDATE account SET financial_hold=true WHERE account_id=?", command.payerAccountId());
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertThrows(IllegalStateException.class, () -> settle(command));
        assertEquals(before, snapshot());
        assertEquals(0.0, metrics.counter("fincore.settlement.success").count());
    }

    /** 在余额加锁前确保两个事务均已持有订单外键锁，稳定复现锁升级竞争。 */
    private List<SettlementOutcome> simultaneousAfterOrderInsert(SettlementCommand first,
                                                                SettlementCommand second) throws Exception {
        SettlementMapper delegate = sessions.getMapper(SettlementMapper.class);
        SettlementMapper synchronizedMapper = spy(delegate);
        CyclicBarrier inserted = new CyclicBarrier(2);
        doAnswer(invocation -> {
            int changed = delegate.insertOrder(invocation.getArgument(0));
            inserted.await(5, TimeUnit.SECONDS);
            return changed;
        }).when(synchronizedMapper).insertOrder(any());
        SettlementService concurrent = new SettlementService(synchronizedMapper,
            sessions.getMapper(LedgerMapper.class), sessions.getMapper(OutboxMapper.class),
            new ObjectMapper(), metrics, leases);
        try (var executor = TestExecutors.fixedThreadPool(2, "settlement-shared-accounts-")) {
            var one = executor.submit(() -> transactions.execute(status -> concurrent.settle(first)));
            var two = executor.submit(() -> transactions.execute(status -> concurrent.settle(second)));
            return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void accountLockWaitBeyondLeaseTtlRollsBackBeforeCommit() throws Exception {
        SettlementCommand command = command();
        ShardLeaseService.Lease lease = transactions.execute(status ->
            leases.claim(731, "settlement-test-worker", Duration.ofSeconds(2)));
        FenceToken token = new FenceToken(lease.shardId(), lease.ownerId(), lease.epoch());
        Map<String, List<Map<String, Object>>> before = snapshot();
        try (Connection blocker = dataSource.getConnection();
             var executor = TestExecutors.fixedThreadPool(1, "settlement-expired-fence-")) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT account_id FROM account WHERE account_id=? FOR NO KEY UPDATE")) {
                lock.setObject(1, command.payerAccountId());
                lock.executeQuery().close();
            }
            var pending = executor.submit(() -> transactions.execute(status -> settlements.settle(command, token)));
            boolean waiting = false;
            try {
                Instant waitDeadline = Instant.now().plusSeconds(1);
                while (Instant.now().isBefore(waitDeadline)) {
                    waiting = jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity "
                        + "WHERE application_name=current_setting('application_name') "
                        + "AND wait_event_type='Lock' AND query LIKE '%FROM account%'", Boolean.class);
                    if (waiting) {
                        break;
                    }
                    Thread.sleep(20);
                }
                long remaining = Duration.between(Instant.now(), lease.leaseUntil()).toMillis();
                Thread.sleep(Math.max(0, remaining) + 100);
            } finally {
                blocker.rollback();
            }
            assertTrue(waiting, "settlement must actually wait on the account row lock");
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(FenceRejectedException.class, failure.getCause());
        }
        assertEquals(before, snapshot());
        assertEquals(0.0, metrics.counter("fincore.settlement.success").count());
    }

    private SettlementService service(OutboxMapper outbox) {
        return new SettlementService(sessions.getMapper(SettlementMapper.class),
            sessions.getMapper(LedgerMapper.class), outbox, new ObjectMapper(), metrics, leases);
    }

    private SettlementOutcome settle(SettlementCommand command) {
        return transactions.execute(status -> settlements.settle(command));
    }

    private SettlementCommand command() {
        UUID payer = account(new BigDecimal("100"));
        UUID payee = account(BigDecimal.ZERO);
        UUID fee = account(BigDecimal.ZERO);
        String id = UUID.randomUUID().toString();
        return new SettlementCommand("message-" + id, "business-" + id, payer, payee, fee,
            "USDT", new BigDecimal("10"), new BigDecimal("1"));
    }

    private SettlementCommand transfer(UUID payer, UUID payee, UUID fee, BigDecimal amount) {
        String id = UUID.randomUUID().toString();
        return new SettlementCommand("message-" + id, "business-" + id, payer, payee, fee,
            "USDT", amount, BigDecimal.ONE);
    }

    private void assertBalance(UUID id, String expected) {
        assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(
            "SELECT balance FROM account WHERE account_id=?", BigDecimal.class, id)));
    }

    private UUID account(BigDecimal opening) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance) "
            + "VALUES (?, ?, 'USDT', 'USER', ?, ?)", id, id.toString(), opening, opening);
        return id;
    }

    private static SettlementCommand copy(SettlementCommand c, String messageId, String businessKey,
                                          BigDecimal amount, BigDecimal fee) {
        return new SettlementCommand(messageId, businessKey, c.payerAccountId(), c.payeeAccountId(),
            c.feeAccountId(), c.asset(), amount, fee);
    }

    private static SettlementCommand changed(SettlementCommand c, String messageId, String field) {
        return new SettlementCommand(messageId, c.businessKey(),
            "payer".equals(field) ? UUID.randomUUID() : c.payerAccountId(),
            "payee".equals(field) ? UUID.randomUUID() : c.payeeAccountId(),
            "feeAccount".equals(field) ? UUID.randomUUID() : c.feeAccountId(),
            "asset".equals(field) ? "USD" : c.asset(),
            "amount".equals(field) ? new BigDecimal("12") : c.amount(),
            "fee".equals(field) ? new BigDecimal("2") : c.fee());
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : FINANCIAL_STATE_TABLES) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1"));
        }
        return result;
    }

    private void assertSingleSettlement(SettlementCommand c) {
        assertEquals(0, new BigDecimal("89").compareTo(jdbc.queryForObject(
            "SELECT balance FROM account WHERE account_id=?", BigDecimal.class, c.payerAccountId())));
        assertEquals(0, new BigDecimal("10").compareTo(jdbc.queryForObject(
            "SELECT balance FROM account WHERE account_id=?", BigDecimal.class, c.payeeAccountId())));
        assertEquals(0, BigDecimal.ONE.compareTo(jdbc.queryForObject(
            "SELECT balance FROM account WHERE account_id=?", BigDecimal.class, c.feeAccountId())));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE business_key=?",
            Integer.class, c.businessKey()));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM ledger_entry e JOIN ledger_transaction t "
            + "USING (transaction_id) WHERE t.business_key=?", Integer.class, c.businessKey()));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM state_audit WHERE business_key=?",
            Integer.class, c.businessKey()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id=?",
            Integer.class, c.businessKey()));
    }
}
