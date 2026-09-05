package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import dev.fincore.application.DerivativesLabService;
import dev.fincore.domain.OrderSide;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.support.TestExecutors;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 合约故障实验的真实 PostgreSQL 回归；同时支持 Docker 和明确指定的隔离测试库。
 * 不使用测试事务包裹用例，确保并发线程通过 Spring 代理各自提交真实事务。
 */
@EnabledIf("databaseAvailable")
@ActiveProfiles("lab")
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false", "spring.task.scheduling.enabled=false"
})
class DerivativesLabIntegrationTest {
    /** CI 要求数据库集成测试不得跳过的系统属性。 */
    private static final String REQUIRE_DATABASE_PROPERTY = "fincore.test.require-database";
    /** 标记价新鲜度边界外使用的测试偏移秒数。 */
    private static final long STALE_MARK_OFFSET_SECONDS = 60L;
    /** 并发测试等待所有任务就绪的最长秒数。 */
    private static final long RACE_READY_TIMEOUT_SECONDS = 10L;
    /** 只有未指定独立测试库时才启动临时容器。 */
    private static PostgreSQLContainer<?> postgres;
    @Autowired DerivativesLabService lab;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean OutboxMapper outbox;

    /** 本地无 Docker 时显式跳过；CI 的 Docker 环境必须实际执行。 */
    static boolean databaseAvailable() {
        boolean available = System.getProperty("fincore.test.jdbc-url") != null
            || DockerClientFactory.instance().isDockerAvailable();
        if (!available && Boolean.getBoolean(REQUIRE_DATABASE_PROPERTY)) {
            throw new IllegalStateException("合约集成验收要求 PostgreSQL，禁止跳过后宣称通过");
        }
        return available;
    }

    /** 外部地址仅供全新、可丢弃测试库使用，禁止指向线上库。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getProperty("fincore.test.jdbc-url");
        if (url != null) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () ->
                System.getProperty("fincore.test.db-user", "postgres"));
            registry.add("spring.datasource.password", () ->
                System.getProperty("fincore.test.db-password", ""));
        } else {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** 账户维度串行扣减预算，不被不同交易对的并行 Lane 绕过。 */
    @Test
    void concurrentSymbolsCannotSpendTheSameMarginTwice() throws Exception {
        UUID account = account("10000");
        var results = race(
            () -> lab.reserve(account, "btc-order", "BTC-USDT", n("6000")),
            () -> lab.reserve(account, "eth-order", "ETH-USDT", n("6000")));
        assertEquals(1, results.stream().filter(r -> "RESERVED".equals(r.status())).count());
        assertEquals(1, results.stream().filter(r -> "INSUFFICIENT_MARGIN".equals(r.status())).count());
        amount("6000", value(account, "reserved"));
        String winner = jdbc.queryForObject("""
            SELECT business_key FROM lab_derivative_operation
            WHERE account_id=? AND kind='RESERVE' AND status='RESERVED'
            """, String.class, account);
        assertEquals("RELEASED", lab.release(account, winner).status());
        assertTrue(lab.release(account, winner).duplicate());
        amount("0", value(account, "reserved"));
    }

    /** 重试返回原决定，不能修改数量或把原拒绝偷偷变为成功。 */
    @Test
    void reservationReplayIsImmutableAndConflictsAreRejected() {
        UUID account = account("100");
        lab.reserve(account, "order", "BTC-USDT", n("80"));
        assertTrue(lab.reserve(account, "order", "BTC-USDT", n("80.00000000")).duplicate());
        assertThrows(IllegalArgumentException.class,
            () -> lab.reserve(account, "order", "ETH-USDT", n("80")));
        assertEquals("INSUFFICIENT_MARGIN", lab.reserve(account, "rejected", "BTC-USDT", n("30")).status());
        lab.release(account, "order");
        assertEquals("INSUFFICIENT_MARGIN", lab.reserve(account, "rejected", "BTC-USDT", n("30")).status());
        amount("0", value(account, "reserved"));
    }

    /** 不同消息编号重送同一资金费周期，仍只有一笔资金效果。 */
    @Test
    void fundingUsesBusinessCycleInsteadOfDeliveryId() throws Exception {
        UUID account = longPosition("10000"), pool = account("1000000");
        Instant cycle = Instant.parse("2026-09-01T00:00:00Z");
        lab.captureFunding(account, "BTC-USDT", cycle, n("60000"), n("0.0001"));
        var results = race(
            () -> lab.applyFunding(account, pool, "BTC-USDT", cycle, UUID.randomUUID()),
            () -> lab.applyFunding(account, pool, "BTC-USDT", cycle, UUID.randomUUID()));
        assertEquals(1, results.stream().filter(DerivativesLabService.Result::duplicate).count());
        amount("9994", value(account, "wallet"));
        amount("1000006", value(pool, "wallet"));
        assertEquals(2, count("lab_derivative_ledger", account));
        balanced(account);
        assertThrows(IllegalArgumentException.class, () -> lab.captureFunding(
            account, "BTC-USDT", cycle, n("60001"), n("0.0001")));
        assertThrows(IllegalArgumentException.class, () -> lab.applyFunding(
            account, account("20000"), "BTC-USDT", cycle, UUID.randomUUID()));
    }

    /** 收费使用已固化的周期仓位，晚到任务不读当前仓位重算；负费率方向相反。 */
    @Test
    void fundingSnapshotSurvivesCloseAndNegativeRateCreditsLong() {
        UUID account = longPosition("10000"), pool = account("1000000");
        Instant cycle = Instant.parse("2026-09-01T08:00:00Z");
        lab.captureFunding(account, "BTC-USDT", cycle, n("60000"), n("-0.0001"));
        lab.reduceOnly(account, pool, "close", "BTC-USDT", OrderSide.SELL, n("1"), n("60000"));
        lab.captureFunding(account, "BTC-USDT", cycle, n("60000"), n("-0.0001"));
        lab.applyFunding(account, pool, "BTC-USDT", cycle, UUID.randomUUID());
        amount("10006", value(account, "wallet"));
        balanced(account);
    }

    /** 新消息重用其他周期的消息编号必须拒绝，不能串账。 */
    @Test
    void conflictingDeliveryIdCannotCrossFundingCycles() {
        UUID account = longPosition("10000"), pool = account("1000000"), message = UUID.randomUUID();
        Instant first = Instant.parse("2026-09-01T00:00:00Z"), second = first.plusSeconds(3600);
        lab.captureFunding(account, "BTC-USDT", first, n("60000"), n("0.0001"));
        lab.captureFunding(account, "BTC-USDT", second, n("60000"), n("0.0001"));
        lab.applyFunding(account, pool, "BTC-USDT", first, message);
        assertThrows(IllegalArgumentException.class,
            () -> lab.applyFunding(account, pool, "BTC-USDT", second, message));
        amount("9994", value(account, "wallet"));
    }

    /** 业务状态已改但 Outbox 写失败时，余额、账本、幂等记录、Inbox 必须全部回滚。 */
    @Test
    void fundingRollsBackAllEffectsWhenOutboxFails() {
        UUID account = longPosition("10000"), pool = account("1000000"), message = UUID.randomUUID();
        Instant cycle = Instant.parse("2026-09-01T16:00:00Z");
        lab.captureFunding(account, "BTC-USDT", cycle, n("60000"), n("0.0001"));
        doThrow(new IllegalStateException("注入 Outbox 写入失败"))
            .when(outbox).insert(any(), anyString(), anyString(), anyString());
        try {
            assertThrows(IllegalStateException.class,
                () -> lab.applyFunding(account, pool, "BTC-USDT", cycle, message));
        } finally {
            reset(outbox);
        }
        amount("10000", value(account, "wallet"));
        amount("1000000", value(pool, "wallet"));
        assertEquals(0, count("lab_derivative_ledger", account));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM lab_derivative_inbox WHERE message_id=?",
            Integer.class, message));
        assertFalse(lab.applyFunding(account, pool, "BTC-USDT", cycle, message).duplicate());
        amount("9994", value(account, "wallet"));
    }

    /** 两张平仓单并发到达时总计只能平掉现有仓位，并按真实成交量计算盈亏。 */
    @Test
    void concurrentReduceOnlyPreventsReversal() throws Exception {
        UUID account = longPosition("10000"), pool = account("1000000");
        var results = race(
            () -> lab.reduceOnly(account, pool, "fill-1", "BTC-USDT", OrderSide.SELL, n("0.8"), n("61000")),
            () -> lab.reduceOnly(account, pool, "fill-2", "BTC-USDT", OrderSide.SELL, n("0.8"), n("61000")));
        amount("1", results.stream().map(DerivativesLabService.Result::effect)
            .reduce(BigDecimal.ZERO, BigDecimal::add));
        amount("0", quantity(account));
        amount("11000", value(account, "wallet"));
        assertTrue(lab.reduceOnly(account, pool, "fill-1", "BTC-USDT", OrderSide.SELL,
            n("0.8"), n("61000")).duplicate());
        amount("11000", value(account, "wallet"));
        balanced(account);
    }

    /** 空仓和错误方向不能变成新开仓；空头的已实现盈亏符号正确。 */
    @Test
    void reduceOnlyChecksDirectionAndSupportsShortPnl() {
        UUID account = account("10000"), pool = account("1000000");
        lab.seedPosition(account, "BTC-USDT", n("-1"), n("60000"));
        assertEquals("WRONG_SIDE", lab.reduceOnly(account, pool, "wrong", "BTC-USDT",
            OrderSide.SELL, n("1"), n("59000")).status());
        lab.reduceOnly(account, pool, "short-close", "BTC-USDT", OrderSide.BUY, n("2"), n("59000"));
        amount("11000", value(account, "wallet"));
        assertEquals("NO_POSITION", lab.reduceOnly(account, pool, "late", "BTC-USDT",
            OrderSide.BUY, n("1"), n("59000")).status());
        amount("0", quantity(account));
    }

    /** Epoch 检查发生在写事务内；旧 Worker 即使持有正确账户版本也不能接管。 */
    @Test
    void oldLiquidationWorkerIsFencedAtTheDatabase() {
        UUID account = longPosition("5000");
        long oldEpoch = lab.takeover(account, UUID.randomUUID());
        var snapshot = lab.assess(account, "BTC-USDT", n("54000"), Instant.now());
        long newEpoch = lab.takeover(account, UUID.randomUUID());
        assertEquals("STALE_EPOCH", lab.liquidate(UUID.randomUUID(), snapshot, oldEpoch).status());
        var current = lab.assess(account, "BTC-USDT", n("54000"), Instant.now());
        assertEquals("LIQUIDATING", lab.liquidate(UUID.randomUUID(), current, newEpoch).status());
        assertEquals("LIQUIDATING", jdbc.queryForObject(
            "SELECT state FROM lab_derivative_account WHERE account_id=?", String.class, account));
        assertEquals("ACCOUNT_FROZEN", lab.reserve(account, "late-open", "BTC-USDT", n("1")).status());
    }

    /** 追加保证金使旧风险快照失效，重新计算后不应继续强平。 */
    @Test
    void marginTopupInvalidatesLiquidationSnapshot() {
        UUID account = longPosition("5000"), pool = account("1000000");
        long epoch = lab.takeover(account, UUID.randomUUID());
        var stale = lab.assess(account, "BTC-USDT", n("54000"), Instant.now());
        lab.topUp(account, pool, "topup", n("2000"));
        assertEquals("STALE_ACCOUNT", lab.liquidate(UUID.randomUUID(), stale, epoch).status());
        var current = lab.assess(account, "BTC-USDT", n("54000"), Instant.now());
        assertEquals("NOT_REQUIRED", lab.liquidate(UUID.randomUUID(), current, epoch).status());
    }

    /** 过期和未来行情都不能触发强平；不使用最新成交价代替标记价。 */
    @Test
    void staleOrFutureMarkFailsClosed() {
        UUID account = longPosition("5000");
        long epoch = lab.takeover(account, UUID.randomUUID());
        for (Instant time : List.of(
            Instant.now().minusSeconds(STALE_MARK_OFFSET_SECONDS),
            Instant.now().plusSeconds(STALE_MARK_OFFSET_SECONDS))) {
            var snapshot = lab.assess(account, "BTC-USDT", n("54000"), time);
            assertEquals("STALE_MARK", lab.liquidate(UUID.randomUUID(), snapshot, epoch).status());
        }
        assertEquals("ACTIVE", jdbc.queryForObject(
            "SELECT state FROM lab_derivative_account WHERE account_id=?", String.class, account));
    }

    /** 资金费扣收不能因预算不足而丢失，扣收后的风险必须重新暴露。 */
    @Test
    void fundingCanCrossMaintenanceThreshold() {
        UUID account = longPosition("305"), pool = account("1000000");
        Instant cycle = Instant.parse("2026-09-02T00:00:00Z");
        long epoch = lab.takeover(account, UUID.randomUUID());
        lab.captureFunding(account, "BTC-USDT", cycle, n("60000"), n("0.0001"));
        lab.applyFunding(account, pool, "BTC-USDT", cycle, UUID.randomUUID());
        amount("299", value(account, "wallet"));
        var snapshot = lab.assess(account, "BTC-USDT", n("60000"), Instant.now());
        assertEquals("LIQUIDATING", lab.liquidate(UUID.randomUUID(), snapshot, epoch).status());
    }

    /** 负金额、精度溢出不能悄悄进入资金模型。 */
    @Test
    void invalidFinancialInputsAreRejected() {
        UUID account = account("10000");
        assertThrows(IllegalArgumentException.class, () -> lab.reserve(account, "bad", "BTC-USDT", n("-1")));
        assertThrows(IllegalArgumentException.class, () -> lab.reserve(account, "tiny", "BTC-USDT", n("0.000000001")));
        amount("0", value(account, "reserved"));
    }

    private UUID account(String wallet) {
        UUID account = UUID.randomUUID();
        lab.openAccount(account, n(wallet));
        return account;
    }

    private UUID longPosition(String wallet) {
        UUID account = account(wallet);
        lab.seedPosition(account, "BTC-USDT", n("1"), n("60000"));
        return account;
    }

    private BigDecimal value(UUID account, String column) {
        // 列名只能来自本测试的固定常量，生产 Mapper 不允许字符串直替。
        return jdbc.queryForObject("SELECT " + column + " FROM lab_derivative_account WHERE account_id=?",
            BigDecimal.class, account);
    }

    private BigDecimal quantity(UUID account) {
        return jdbc.queryForObject("SELECT quantity FROM lab_derivative_position WHERE account_id=?",
            BigDecimal.class, account);
    }

    private int count(String table, UUID account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table
            + " WHERE operation_id IN (SELECT operation_id FROM lab_derivative_operation WHERE account_id=?)",
            Integer.class, account);
    }

    private void balanced(UUID account) {
        amount("0", jdbc.queryForObject("""
            SELECT COALESCE(SUM(delta), 0) FROM lab_derivative_ledger WHERE operation_id IN
            (SELECT operation_id FROM lab_derivative_operation WHERE account_id=?)
            """, BigDecimal.class, account));
        amount("0", jdbc.queryForObject("""
            SELECT a.wallet-a.opening_wallet-COALESCE(SUM(l.delta), 0)
            FROM lab_derivative_account a LEFT JOIN lab_derivative_ledger l USING(account_id)
            WHERE a.account_id=? GROUP BY a.wallet,a.opening_wallet
            """, BigDecimal.class, account));
    }

    /** 有界线程、有界等待，同时释放起跑线，避免把串行调用误称为并发测试。 */
    private List<DerivativesLabService.Result> race(Callable<DerivativesLabService.Result> first,
                                                    Callable<DerivativesLabService.Result> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = TestExecutors.fixedThreadPool(2, "derivatives-race-test-")) {
            var futures = List.of(first, second).stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(RACE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发测试起跑超时");
                }
                return task.call();
            })).toList();
            try {
                assertTrue(ready.await(RACE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            } finally {
                // 断言失败也要释放已经就绪的任务，避免测试线程泄漏。
                start.countDown();
            }
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }
    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), () -> "预期=" + expected + "，实际=" + actual);
    }
}
