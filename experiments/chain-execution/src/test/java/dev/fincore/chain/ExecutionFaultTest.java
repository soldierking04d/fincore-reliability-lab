package dev.fincore.chain;

import static dev.fincore.chain.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 故障只在临时数据库和纸面网关中注入，不访问网络、钱包或真实签名。
 * 不变量：发送先落库；过期工作者不能回写；所有未决订单继续占用资金与容量；
 * 差异收据只能冻结待审，不能创造资产或释放预留。
 */
class ExecutionFaultTest {
    @TempDir Path dir;
    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long BUY_INPUT = 100_000;
    private static final long BUY_OUTPUT = 4_000_000_000L;

    private static BigInteger n(long value) { return BigInteger.valueOf(value); }

    private static Quote quote(long input) {
        return new Quote("paper-quote-" + input, Side.BUY, n(input), n(BUY_OUTPUT),
            n(3_980_000_000L), MAX_FEE, 50, 40, n(10_000_000), NOW.plusSeconds(30));
    }

    private static OrderView reserve(ExecutionEngine engine, String id) {
        return engine.reserve(new Request(id, Side.BUY, n(BUY_INPUT)), quote(BUY_INPUT));
    }

    private static OrderView submit(ExecutionEngine engine, String id, long epoch) {
        reserve(engine, id);
        return engine.dispatch(id, epoch, plan -> "paper-tx-" + plan.attemptId());
    }

    private static List<Observation> receipts(OrderView order, Confirmation confirmation) {
        return List.of(receipt(order, "paper-rpc-a", confirmation, BUY_OUTPUT, 10_000),
            receipt(order, "paper-rpc-b", confirmation, BUY_OUTPUT, 10_000));
    }

    private static Observation receipt(OrderView order, String provider, Confirmation confirmation,
                                       long output, long fee) {
        return new Observation(provider, order.attemptId(), order.digest(),
            "paper-tx-" + order.attemptId(), confirmation, order.input(), n(output), n(fee), 42);
    }

    @Test void quoteExpiringAfterReservationPreventsAnyGatewayCall() {
        var clock = new MutableClock(NOW);
        try (var engine = new ExecutionEngine(dir, clock)) {
            long epoch = engine.takeover();
            reserve(engine, "expired-before-send");
            clock.set(NOW.plusSeconds(31));
            AtomicInteger calls = new AtomicInteger();

            assertThrows(RuntimeException.class, () -> engine.dispatch("expired-before-send", epoch,
                plan -> { calls.incrementAndGet(); return "unexpected-paper-send"; }));

            assertEquals(0, calls.get(), "An expired reserved quote must never reach the gateway");
            assertEquals(INITIAL_SOL, engine.snapshot().sol());
            assertEquals(BigInteger.ZERO, engine.snapshot().tokens());
        }
    }

    @Test void takeoverInsideGatewayRejectsOldWorkerWritebackAndPersistsDispatching() {
        AtomicLong freshEpoch = new AtomicLong();
        String id = "takeover-during-send";
        try (var oldWorker = new ExecutionEngine(dir, CLOCK);
             var newWorker = new ExecutionEngine(dir, CLOCK)) {
            long oldEpoch = oldWorker.takeover();
            reserve(oldWorker, id);
            AtomicInteger calls = new AtomicInteger();

            assertThrows(RuntimeException.class, () -> oldWorker.dispatch(id, oldEpoch, plan -> {
                calls.incrementAndGet();
                freshEpoch.set(newWorker.takeover());
                return "paper-tx-" + plan.attemptId();
            }));

            assertEquals(1, calls.get());
            assertTrue(freshEpoch.get() > oldEpoch);
            assertEquals(Status.DISPATCHING, newWorker.order(id).status());
            assertEquals(n(110_000), newWorker.snapshot().reservedSol());
        }
        try (var recovered = new ExecutionEngine(dir, CLOCK)) {
            var persisted = recovered.order(id);
            assertEquals(Status.DISPATCHING, persisted.status());
            AtomicInteger calls = new AtomicInteger();
            recovered.dispatch(id, freshEpoch.get(), plan -> {
                calls.incrementAndGet();
                return "unexpected-second-send";
            });
            assertEquals(0, calls.get(), "A takeover must not create a second broadcast attempt");
            recovered.reconcile(id, freshEpoch.get(), receipts(persisted, Confirmation.FINALIZED_SUCCESS));
            assertEquals(Status.FINALIZED, recovered.order(id).status());
            assertEquals(n(BUY_OUTPUT), recovered.snapshot().tokens());
        }
    }

    @Test void killSwitchBlocksNewReservationsAndReservedDispatchButAllowsRecovery() {
        try (var engine = new ExecutionEngine(dir, CLOCK)) {
            long epoch = engine.takeover();
            var submitted = submit(engine, "already-sent", epoch);
            reserve(engine, "not-yet-sent");
            engine.setKillSwitch(true);
            AtomicInteger calls = new AtomicInteger();

            assertThrows(IllegalStateException.class, () -> reserve(engine, "new-while-stopped"));
            assertThrows(RuntimeException.class, () -> engine.dispatch("not-yet-sent", epoch, plan -> {
                calls.incrementAndGet();
                return "unexpected-send-while-stopped";
            }));
            assertEquals(0, calls.get());
            assertEquals(Status.RESERVED, engine.order("not-yet-sent").status());
            assertEquals(n(220_000), engine.snapshot().reservedSol());

            engine.reconcile("already-sent", epoch, receipts(submitted, Confirmation.FINALIZED_SUCCESS));
            assertEquals(Status.FINALIZED, engine.order("already-sent").status());
            assertEquals(n(BUY_OUTPUT), engine.snapshot().tokens());
            assertEquals(n(110_000), engine.snapshot().reservedSol());
            assertTrue(engine.snapshot().killSwitch());
        }
    }

    enum ReceiptFault { DUPLICATE_PROVIDER, UNKNOWN_PROVIDER, BELOW_MINIMUM_OUTPUT, EXCESS_FEE, WRONG_DIGEST, WRONG_ATTEMPT }

    @ParameterizedTest
    @EnumSource(ReceiptFault.class)
    void invalidReceiptsFreezeForReviewWithoutChangingLedger(ReceiptFault fault) {
        String id = "invalid-" + fault;
        OrderView submitted;
        Snapshot before;
        try (var engine = new ExecutionEngine(dir, CLOCK)) {
            long epoch = engine.takeover();
            submitted = submit(engine, id, epoch);
            before = engine.snapshot();
            var valid = receipts(submitted, Confirmation.FINALIZED_SUCCESS);
            List<Observation> bad = switch (fault) {
                case DUPLICATE_PROVIDER -> List.of(valid.getFirst(), valid.getFirst());
                case UNKNOWN_PROVIDER -> List.of(valid.getFirst(),
                    receipt(submitted, "untrusted-paper-provider", Confirmation.FINALIZED_SUCCESS, BUY_OUTPUT, 10_000));
                case BELOW_MINIMUM_OUTPUT -> List.of(
                    receipt(submitted, "paper-rpc-a", Confirmation.FINALIZED_SUCCESS, 3_979_999_999L, 10_000),
                    receipt(submitted, "paper-rpc-b", Confirmation.FINALIZED_SUCCESS, 3_979_999_999L, 10_000));
                case EXCESS_FEE -> List.of(
                    receipt(submitted, "paper-rpc-a", Confirmation.FINALIZED_SUCCESS, BUY_OUTPUT, 10_001),
                    receipt(submitted, "paper-rpc-b", Confirmation.FINALIZED_SUCCESS, BUY_OUTPUT, 10_001));
                case WRONG_DIGEST -> valid.stream().map(o -> new Observation(o.provider(), o.attemptId(),
                    "other-paper-digest", o.signature(), o.confirmation(), o.input(), o.output(), o.networkFee(), o.slot())).toList();
                case WRONG_ATTEMPT -> valid.stream().map(o -> new Observation(o.provider(), "other-paper-attempt",
                    o.digest(), o.signature(), o.confirmation(), o.input(), o.output(), o.networkFee(), o.slot())).toList();
            };

            engine.reconcile(id, epoch, bad);
            assertEquals(Status.REVIEW, engine.order(id).status());
            assertLedgerUnchanged(before, engine.snapshot());
        }
        try (var recovered = new ExecutionEngine(dir, CLOCK)) {
            long epoch = recovered.takeover();
            assertEquals(Status.REVIEW, recovered.order(id).status());
            assertLedgerUnchanged(before, recovered.snapshot());
            recovered.reconcile(id, epoch, receipts(submitted, Confirmation.FINALIZED_SUCCESS));
            assertEquals(Status.REVIEW, recovered.order(id).status(), "Ordinary reconciliation cannot silently unfreeze review");
            assertLedgerUnchanged(before, recovered.snapshot());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"RESERVED", "DISPATCHING", "SUBMITTED", "UNKNOWN", "CONFIRMING", "REVIEW"})
    void everyPendingStatusStillConsumesGrossDebitBudget(Status status) {
        try (var engine = new ExecutionEngine(dir, CLOCK)) {
            long epoch = engine.takeover();
            spendEightBuyDebits(engine, epoch);
            makePending(engine, "pending", status, epoch);
            assertEquals(status, engine.order("pending").status());
            var before = engine.snapshot();
            assertEquals(n(880_000), before.grossSolDebits());
            assertEquals(n(110_000), before.reservedSol());
            assertEquals(n(990_000), before.grossSolDebits().add(before.reservedSol()));

            assertThrows(IllegalStateException.class, () -> reserve(engine, "would-exceed-budget"));

            assertLedgerUnchanged(before, engine.snapshot());
            assertEquals(before.orderCount(), engine.snapshot().orderCount());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = {"RESERVED", "DISPATCHING", "SUBMITTED", "UNKNOWN", "CONFIRMING", "REVIEW"})
    void everyPendingStatusStillConsumesOneOfEightOpenSlots(Status status) {
        try (var engine = new ExecutionEngine(dir, CLOCK)) {
            long epoch = engine.takeover();
            makePending(engine, "pending", status, epoch);
            for (int i = 1; i < MAX_OPEN_ORDERS; i++) reserve(engine, "other-" + i);
            assertEquals(status, engine.order("pending").status());
            var before = engine.snapshot();
            assertEquals(MAX_OPEN_ORDERS, before.orderCount());
            assertEquals(n(880_000), before.reservedSol());
            assertTrue(before.reservedSol().add(n(110_000)).compareTo(MAX_GROSS_SOL_DEBITS) < 0,
                "A ninth order fits the money budget, so rejection must enforce open-order capacity");

            assertThrows(IllegalStateException.class, () -> reserve(engine, "ninth-open-order"));

            assertEquals(MAX_OPEN_ORDERS, engine.snapshot().orderCount());
            assertLedgerUnchanged(before, engine.snapshot());
        }
    }

    @Test void concurrentReservationsAcrossEnginesCannotExceedEightOpenOrders() throws Exception {
        try (var first = new ExecutionEngine(dir, CLOCK);
             var second = new ExecutionEngine(dir, CLOCK)) {
            int accepted = concurrentReservations(first, second, 16);
            assertEquals(MAX_OPEN_ORDERS, accepted);
            var snapshot = first.snapshot();
            assertEquals(MAX_OPEN_ORDERS, snapshot.orderCount());
            assertEquals(n(880_000), snapshot.reservedSol());
            assertEquals(INITIAL_SOL, snapshot.sol());
            assertEquals(snapshot, second.snapshot());
        }
    }

    @Test void concurrentReservationsAcrossEnginesCannotOversubscribeRemainingBudget() throws Exception {
        try (var first = new ExecutionEngine(dir, CLOCK);
             var second = new ExecutionEngine(dir, CLOCK)) {
            spendEightBuyDebits(first, first.takeover());
            int accepted = concurrentReservations(first, second, 8);
            assertEquals(1, accepted, "Only one 110000 reservation fits after 880000 finalized debits");
            var snapshot = first.snapshot();
            assertEquals(n(880_000), snapshot.grossSolDebits());
            assertEquals(n(110_000), snapshot.reservedSol());
            assertEquals(9, snapshot.orderCount());
            assertEquals(snapshot, second.snapshot());
        }
    }

    @Test void gatewaySeesDispatchIntentCommittedThroughAnotherEngine() {
        try (var sending = new ExecutionEngine(dir, CLOCK)) {
            long epoch = sending.takeover();
            reserve(sending, "durable-before-send");
            AtomicInteger calls = new AtomicInteger();
            var result = sending.dispatch("durable-before-send", epoch, plan -> {
                calls.incrementAndGet();
                try (var observer = new ExecutionEngine(dir, CLOCK)) {
                    var persisted = observer.order(plan.requestId());
                    assertEquals(Status.DISPATCHING, persisted.status());
                    assertEquals(plan.attemptId(), persisted.attemptId());
                    assertEquals(plan.digest(), persisted.digest());
                    assertEquals(n(110_000), observer.snapshot().reservedSol());
                }
                return "paper-tx-" + plan.attemptId();
            });
            assertEquals(1, calls.get());
            assertEquals(Status.SUBMITTED, result.status());
        }
    }

    private static void spendEightBuyDebits(ExecutionEngine engine, long epoch) {
        for (int i = 0; i < 8; i++) {
            String id = "settled-" + i;
            var submitted = submit(engine, id, epoch);
            engine.reconcile(id, epoch, receipts(submitted, Confirmation.FINALIZED_SUCCESS));
            assertEquals(Status.FINALIZED, engine.order(id).status());
        }
    }

    private static void makePending(ExecutionEngine engine, String id, Status status, long epoch) {
        reserve(engine, id);
        if (status == Status.RESERVED) return;
        if (status == Status.DISPATCHING) {
            assertThrows(RuntimeException.class, () -> engine.dispatch(id, epoch, plan -> {
                engine.takeover();
                return "paper-tx-" + plan.attemptId();
            }));
            return;
        }
        if (status == Status.UNKNOWN) {
            engine.dispatch(id, epoch, plan -> { throw new IOException("paper acceptance response lost"); });
            return;
        }
        var submitted = engine.dispatch(id, epoch, plan -> "paper-tx-" + plan.attemptId());
        if (status == Status.CONFIRMING) engine.reconcile(id, epoch, receipts(submitted, Confirmation.CONFIRMED));
        if (status == Status.REVIEW) {
            var receipt = receipts(submitted, Confirmation.FINALIZED_SUCCESS).getFirst();
            engine.reconcile(id, epoch, List.of(receipt, receipt));
        }
    }

    private static int concurrentReservations(ExecutionEngine first, ExecutionEngine second, int attempts) throws Exception {
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            String id = "concurrent-" + i;
            ExecutionEngine engine = i % 2 == 0 ? first : second;
            tasks.add(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Concurrent start gate timed out");
                try {
                    reserve(engine, id);
                    return true;
                } catch (IllegalStateException rejectedByBusinessLimit) {
                    // A wrapped database failure must fail the test, not count as a valid limit rejection.
                    if (rejectedByBusinessLimit.getCause() != null) throw rejectedByBusinessLimit;
                    return false;
                }
            });
        }
        try (var pool = Executors.newFixedThreadPool(attempts)) {
            var futures = tasks.stream().map(pool::submit).toList();
            boolean allReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertTrue(allReady, "Every reservation must be ready before racing the capacity check");
            int accepted = 0;
            for (var future : futures) if (future.get(30, TimeUnit.SECONDS)) accepted++;
            return accepted;
        }
    }

    private static void assertLedgerUnchanged(Snapshot before, Snapshot after) {
        assertEquals(before.sol(), after.sol());
        assertEquals(before.tokens(), after.tokens());
        assertEquals(before.reservedSol(), after.reservedSol());
        assertEquals(before.reservedTokens(), after.reservedTokens());
        assertEquals(before.grossSolDebits(), after.grossSolDebits());
        assertEquals(before.journalCount(), after.journalCount());
        assertEquals(before.journalSums(), after.journalSums());
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Paper test uses UTC only");
            return this;
        }
        @Override public Instant instant() { return now.get(); }
    }
}
