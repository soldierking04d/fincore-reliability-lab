package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.FenceRejectedException;
import dev.fincore.application.ShardLeaseService;
import dev.fincore.application.SpotDeliveryService;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.SpotDeliveryCommand;
import dev.fincore.infrastructure.persistence.mapper.LedgerMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.infrastructure.persistence.mapper.ShardLeaseMapper;
import dev.fincore.infrastructure.persistence.mapper.SpotFundsMapper;
import dev.fincore.support.TestExecutors;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionSystemException;

/** Real PostgreSQL commit, rollback and concurrent replay checks for spot metrics. */
class SpotDeliveryMetricsPostgresTest extends LocalPostgresTestSupport {
    /** A returned delivery is completed only after the enclosing physical commit. */
    @Test
    void completedAndDuplicateCountersFollowPhysicalCommit() {
        Fixture fixture = fixture();
        transactions.executeWithoutResult(status -> {
            assertEquals("SETTLED", fixture.service().settle(fixture.command(), fixture.fence()).status());
            assertEquals(0, completed(fixture));
        });
        assertEquals(1, completed(fixture));

        transactions.executeWithoutResult(status -> {
            fixture.service().settle(fixture.command(), fixture.fence());
            assertEquals(0, duplicates(fixture));
        });
        assertEquals(1, completed(fixture));
        assertEquals(1, duplicates(fixture));
        assertSettled(fixture);
    }

    /** An outer rollback erases both assets, inbox and outbox and never counts completion. */
    @Test
    void enclosingRollbackLeavesNoFinancialEffectOrCompletedCount() {
        Fixture fixture = fixture();
        transactions.executeWithoutResult(status -> {
            fixture.service().settle(fixture.command(), fixture.fence());
            status.setRollbackOnly();
        });

        assertEquals(0, completed(fixture));
        assertPending(fixture);
        transactions.executeWithoutResult(status -> fixture.service().settle(fixture.command(), fixture.fence()));
        assertEquals(1, completed(fixture));
        assertSettled(fixture);
    }

    /** Reusing locked snapshots preserves the asset check and rolls back the newly inserted inbox. */
    @Test
    void lockedAccountAssetMismatchRollsBackWholeDelivery() {
        Fixture fixture = fixture();
        jdbc.update("UPDATE account SET asset='WRONG' WHERE account_id=?", fixture.buyerBase());

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status ->
            fixture.service().settle(fixture.command(), fixture.fence())));

        assertEquals(0, completed(fixture));
        assertPending(fixture);
    }

    /** Joining an outer transaction must keep the lease valid until its physical commit. */
    @Test
    void outerTransactionDelayBeyondLeaseTtlRollsBackBeforeCommit() {
        Fixture fixture = fixture();
        Instant expires = shortLease(fixture);

        assertThrows(FenceRejectedException.class, () -> transactions.executeWithoutResult(status -> {
            assertEquals("SETTLED", fixture.service().settle(fixture.command(), fixture.fence()).status());
            waitForExpiry(expires);
        }));

        assertEquals(0, completed(fixture));
        assertPending(fixture);
    }

    /** A verified PostgreSQL row-lock wait crossing TTL must erase both asset transfers. */
    @Test
    void accountLockWaitBeyondLeaseTtlRollsBackBeforeCommit() throws Exception {
        Fixture fixture = fixture();
        Instant expires = shortLease(fixture);
        try (Connection blocker = dataSource.getConnection();
             var executor = TestExecutors.fixedThreadPool(1, "spot-expiry-postgres-")) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement(
                "SELECT account_id FROM account WHERE account_id=? FOR NO KEY UPDATE")) {
                lock.setObject(1, fixture.buyerQuote());
                lock.executeQuery().close();
            }
            Future<?> pending = executor.submit(() -> transactions.executeWithoutResult(status ->
                fixture.service().settle(fixture.command(), fixture.fence())));
            boolean waiting = false;
            try {
                Instant deadline = Instant.now().plusSeconds(1);
                while (Instant.now().isBefore(deadline)) {
                    waiting = jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity "
                        + "WHERE application_name=current_setting('application_name') "
                        + "AND wait_event_type='Lock' AND query LIKE '%FROM account%'", Boolean.class);
                    if (waiting) {
                        break;
                    }
                    Thread.sleep(20);
                }
                waitForExpiry(expires);
            } finally {
                blocker.rollback();
            }
            assertTrue(waiting, "spot delivery must actually wait on the account row lock");
            ExecutionException failure = assertThrows(ExecutionException.class,
                () -> pending.get(5, TimeUnit.SECONDS));
            assertInstanceOf(FenceRejectedException.class, failure.getCause());
        }
        assertEquals(0, completed(fixture));
        assertPending(fixture);
    }

    /** Existing FK references must not make two balance writers deadlock when upgrading account locks. */
    @Test
    void accountLocksDoNotDeadlockAfterConcurrentForeignKeyReferences() throws Exception {
        Fixture fixture = fixture();
        CyclicBarrier referencesHeld = new CyclicBarrier(2);
        try (var executor = TestExecutors.fixedThreadPool(2, "spot-fk-lock-postgres-")) {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                futures.add(executor.submit(() -> transactions.executeWithoutResult(status -> {
                    // A real FK check holds KEY SHARE, as financial references inserted before account locking do.
                    UUID issue = UUID.randomUUID();
                    jdbc.update("INSERT INTO reconciliation_issue(issue_id,account_id,issue_type,risk_level) "
                        + "VALUES(?,?,?,'LOW')", issue, fixture.buyerQuote(), "test-" + issue);
                    try {
                        referencesHeld.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("interrupted while preparing FK contention", exception);
                    } catch (Exception exception) {
                        throw new IllegalStateException("failed to prepare FK contention", exception);
                    }
                    var locked = sessions.getMapper(SpotFundsMapper.class).lockFunds(fixture.buyerQuote());
                    assertEquals(0, new BigDecimal("100").compareTo(locked.balance()));
                })));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }
        assertPending(fixture);
    }

    /** Renew the fixture lease after setup so the test starts with a known, valid TTL. */
    private Instant shortLease(Fixture fixture) {
        ShardLeaseService leases = new ShardLeaseService(sessions.getMapper(ShardLeaseMapper.class));
        return transactions.execute(status -> leases.acquireOrRenew(fixture.fence().shardId(),
            fixture.fence().ownerId(), Duration.ofSeconds(2))).leaseUntil();
    }

    /** Wait only until this real test lease expires, preserving interruption semantics. */
    private static void waitForExpiry(Instant expiry) {
        long millis = Math.max(0, Duration.between(Instant.now(), expiry).toMillis()) + 100;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for test lease expiry", exception);
        }
    }

    /** Deferred PostgreSQL failure happens after the Java body returns but before commit succeeds. */
    @Test
    void deferredCommitFailureDoesNotCountOrLeavePartialDelivery() {
        Fixture fixture = fixture();
        jdbc.execute("""
            CREATE FUNCTION reject_test_spot_commit() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.event_type='SPOT_DVP_SETTLED' THEN
                    RAISE EXCEPTION 'test rejects deferred spot commit';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE CONSTRAINT TRIGGER reject_test_spot_commit
            AFTER INSERT ON outbox_event DEFERRABLE INITIALLY DEFERRED
            FOR EACH ROW EXECUTE FUNCTION reject_test_spot_commit()
            """);
        try {
            assertThrows(TransactionSystemException.class, () -> transactions.executeWithoutResult(status ->
                assertEquals("SETTLED", fixture.service().settle(fixture.command(), fixture.fence()).status())));
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_spot_commit ON outbox_event");
            jdbc.execute("DROP FUNCTION reject_test_spot_commit()");
        }

        assertEquals(0, completed(fixture));
        assertPending(fixture);
        transactions.executeWithoutResult(status -> fixture.service().settle(fixture.command(), fixture.fence()));
        assertEquals(1, completed(fixture));
        assertSettled(fixture);
    }

    /** Concurrent redelivery commits exactly one financial effect and five duplicate outcomes. */
    @Test
    void concurrentReplayCountsOneCompletion() throws Exception {
        Fixture fixture = fixture();
        int attempts = 6;
        CyclicBarrier start = new CyclicBarrier(attempts);
        try (var executor = TestExecutors.fixedThreadPool(attempts, "spot-metrics-postgres-")) {
            List<Future<?>> futures = new ArrayList<>();
            for (int attempt = 0; attempt < attempts; attempt++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    transactions.executeWithoutResult(status ->
                        fixture.service().settle(fixture.command(), fixture.fence()));
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        assertEquals(1, completed(fixture));
        assertEquals(attempts - 1, duplicates(fixture));
        assertSettled(fixture);
    }

    /** Synthetic matched facts satisfy the real production schema and both pending balances. */
    private Fixture fixture() {
        UUID trade = UUID.randomUUID();
        UUID buy = UUID.randomUUID();
        UUID sell = UUID.randomUUID();
        UUID buyerQuote = account("USDT", "100", "10");
        UUID buyerBase = account("BTC", "0", "0");
        UUID sellerBase = account("BTC", "10", "1");
        UUID sellerQuote = account("USDT", "0", "0");
        order(buy, trade.toString(), "BUY", 1);
        order(sell, trade.toString(), "SELL", 2);
        jdbc.update("""
            INSERT INTO trade_execution(trade_id,symbol,maker_order_id,taker_order_id,
              price,quantity,quote_amount,trade_sequence) VALUES(?,?,?,?,10,1,10,1)
            """, trade, trade.toString(), sell, buy);
        reservation(buy, buyerQuote, buyerBase, "10");
        reservation(sell, sellerBase, sellerQuote, "1");
        SpotFundsMapper funds = sessions.getMapper(SpotFundsMapper.class);
        funds.insertDelivery(new SpotFundsMapper.DeliveryRow(trade, buy, sell, buyerQuote, buyerBase,
            sellerBase, sellerQuote, "BTC", "USDT", BigDecimal.ONE, BigDecimal.TEN, "PENDING", null, null));
        ShardLeaseService leases = new ShardLeaseService(sessions.getMapper(ShardLeaseMapper.class));
        var lease = transactions.execute(status -> leases.acquireOrRenew(0, "spot-metrics-test", Duration.ofMinutes(5)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpotDeliveryService service = new SpotDeliveryService(funds, sessions.getMapper(LedgerMapper.class),
            sessions.getMapper(OutboxMapper.class), leases, registry, 1);
        return new Fixture(service, registry, new SpotDeliveryCommand("spot-metrics:" + trade, trade),
            new FenceToken(0, lease.ownerId(), lease.epoch()), buyerQuote, buyerBase, sellerBase, sellerQuote);
    }

    /** Account setup is isolated to this test schema. */
    private UUID account(String asset, String balance, String pending) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO account(account_id,owner_id,asset,account_type,opening_balance,balance,pending_debit)
            VALUES(?,?,?,'TRADING',?,?,?)
            """, id, id.toString(), asset, new BigDecimal(balance), new BigDecimal(balance), new BigDecimal(pending));
        return id;
    }

    /** Matching facts are immutable financial inputs for the delivery service. */
    private void order(UUID id, String symbol, String side, int sequence) {
        jdbc.update("""
            INSERT INTO matching_order(order_id,client_order_id,user_id,symbol,side,order_type,price,
              original_quantity,executed_quantity,remaining_quantity,status,order_sequence)
            VALUES(?,?,?,?,?,'LIMIT',10,1,1,0,'FILLED',?)
            """, id, id.toString(), id.toString(), symbol, side, sequence);
    }

    /** Fully matched reservations start with pending, never available, funds. */
    private void reservation(UUID order, UUID payer, UUID receiver, String amount) {
        jdbc.update("""
            INSERT INTO spot_order_reservation(order_id,payer_account_id,receiver_account_id,initial_amount,held,pending)
            VALUES(?,?,?,?,0,?)
            """, order, payer, receiver, new BigDecimal(amount), new BigDecimal(amount));
    }

    /** Real rollback must restore each side and erase all newly written facts. */
    private void assertPending(Fixture fixture) {
        assertEquals("PENDING", fixture.service().get(fixture.command().tradeId()).status());
        balance(fixture.buyerQuote(), "100", "10");
        balance(fixture.buyerBase(), "0", "0");
        balance(fixture.sellerBase(), "10", "1");
        balance(fixture.sellerQuote(), "0", "0");
        assertFacts(fixture, 0, 0, 0);
    }

    /** Committed transfer conserves the two assets and consumes exactly the staged budget. */
    private void assertSettled(Fixture fixture) {
        assertEquals("SETTLED", fixture.service().get(fixture.command().tradeId()).status());
        balance(fixture.buyerQuote(), "90", "0");
        balance(fixture.buyerBase(), "1", "0");
        balance(fixture.sellerBase(), "9", "0");
        balance(fixture.sellerQuote(), "10", "0");
        assertFacts(fixture, 2, 1, 1);
    }

    /** Check final authoritative rows independently of the Java result. */
    private void assertFacts(Fixture fixture, int ledger, int inbox, int outbox) {
        UUID trade = fixture.command().tradeId();
        assertEquals(ledger, jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE business_key LIKE ?",
            Integer.class, "spot:" + trade + ":%"));
        assertEquals(inbox, jdbc.queryForObject("SELECT count(*) FROM spot_delivery_inbox WHERE trade_id=?",
            Integer.class, trade));
        assertEquals(outbox, jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id=?",
            Integer.class, trade.toString()));
    }

    /** Money comparisons use exact decimal values. */
    private void balance(UUID id, String amount, String pending) {
        SpotFundsMapper.FundsRow row = sessions.getMapper(SpotFundsMapper.class).funds(id);
        assertEquals(0, new BigDecimal(amount).compareTo(row.balance()));
        assertEquals(0, new BigDecimal(pending).compareTo(row.pendingDebit()));
    }

    /** Committed new delivery count. */
    private static double completed(Fixture fixture) {
        return fixture.registry().get("fincore.spot.delivery.completed").counter().count();
    }

    /** Committed duplicate confirmation count. */
    private static double duplicates(Fixture fixture) {
        return fixture.registry().get("fincore.spot.delivery.duplicate").counter().count();
    }

    /** All references belong to one synthetic delivery. */
    private record Fixture(SpotDeliveryService service, SimpleMeterRegistry registry,
                           SpotDeliveryCommand command, FenceToken fence, UUID buyerQuote,
                           UUID buyerBase, UUID sellerBase, UUID sellerQuote) { }
}
