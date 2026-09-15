package dev.fincore.chain;

import static dev.fincore.chain.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 所有字面预期由资金守恒推导，使用临时文件库和合成观察，不使用网络或钱包。 */
class ExecutionEngineTest {
    @TempDir Path dir;
    static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static BigInteger n(long value) { return BigInteger.valueOf(value); }
    static Quote quote(Side side, long input, long output) {
        return new Quote("quote-" + side, side, n(input), n(output), n(output).multiply(n(9950)).divide(n(10000)),
            n(10000), 50, 40, n(10000000), NOW.plusSeconds(30));
    }
    static List<Observation> observations(OrderView order, Confirmation c, long output, long fee) {
        BigInteger input = c == Confirmation.FINALIZED_FAILURE ? BigInteger.ZERO : order.input();
        return List.of(new Observation("paper-rpc-a", order.attemptId(), order.digest(), "paper-tx-" + order.attemptId(), c, input, n(output), n(fee), 42),
            new Observation("paper-rpc-b", order.attemptId(), order.digest(), "paper-tx-" + order.attemptId(), c, input, n(output), n(fee), 42));
    }
    static OrderView buy(ExecutionEngine e, long epoch, String id) {
        e.reserve(new Request(id, Side.BUY, n(100000)), quote(Side.BUY, 100000, 4000000000L));
        return e.dispatch(id, epoch, p -> "paper-tx-" + p.attemptId());
    }
    @Test void buyHoldSellLedgerConservesAndFeesExplainLoss() {
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            long epoch = e.takeover();
            var b = buy(e, epoch, "buy");
            assertEquals(n(110000), e.snapshot().reservedSol()); assertEquals(n(0), e.snapshot().tokens());
            e.reconcile("buy", epoch, observations(b, Confirmation.CONFIRMED, 4000000000L, 10000));
            assertEquals(n(0), e.snapshot().tokens());
            e.reconcile("buy", epoch, observations(b, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000));
            assertEquals(n(9890000), e.snapshot().sol()); assertEquals(n(4000000000L), e.snapshot().tokens());
            e.reserve(new Request("sell", Side.SELL, n(4000000000L)), quote(Side.SELL, 4000000000L, 98000));
            var s = e.dispatch("sell", epoch, p -> "paper-tx-" + p.attemptId());
            e.reconcile("sell", epoch, observations(s, Confirmation.FINALIZED_SUCCESS, 98000, 10000));
            var r = e.snapshot(); assertEquals(n(9978000), r.sol()); assertEquals(n(0), r.tokens());
            assertEquals(n(120000), r.grossSolDebits()); assertEquals(n(0), r.reservedSol());
            assertEquals(n(0), r.reservedTokens()); r.journalSums().values().forEach(sum -> assertEquals(n(0), sum));
            int count = r.journalCount(); e.reconcile("sell", epoch, observations(s, Confirmation.FINALIZED_SUCCESS, 98000, 10000));
            assertEquals(count, e.snapshot().journalCount());
        }
    }
    @Test void duplicateIntentAndDispatchCannotProduceSecondEffect() {
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            var q = quote(Side.BUY, 100000, 4000000000L); var request = new Request("same", Side.BUY, n(100000));
            var first = e.reserve(request, q); assertEquals(first, e.reserve(request, q));
            assertThrows(RuntimeException.class, () -> e.reserve(new Request("same", Side.BUY, n(99999)), q));
            long epoch = e.takeover(); AtomicInteger calls = new AtomicInteger();
            PaperGateway gateway = p -> { calls.incrementAndGet(); return "paper-tx-" + p.attemptId(); };
            e.dispatch("same", epoch, gateway); e.dispatch("same", epoch, gateway);
            assertEquals(1, calls.get()); assertEquals(1, e.snapshot().orderCount());
        }
    }
    @Test void unknownPersistsAcrossRestartAndCannotBeBlindlyResent() {
        OrderView order;
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            long epoch = e.takeover(); e.reserve(new Request("unknown", Side.BUY, n(100000)), quote(Side.BUY, 100000, 4000000000L));
            order = e.dispatch("unknown", epoch, p -> { throw new java.io.IOException("paper timeout after acceptance"); });
            assertEquals(Status.UNKNOWN, order.status());
        }
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            long epoch = e.takeover(); AtomicInteger calls = new AtomicInteger();
            e.dispatch("unknown", epoch, p -> { calls.incrementAndGet(); return "unexpected"; }); assertEquals(0, calls.get());
            assertEquals(n(110000), e.snapshot().reservedSol());
            e.reconcile("unknown", epoch, observations(order, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000));
            assertEquals(Status.FINALIZED, e.order("unknown").status()); assertEquals(n(4000000000L), e.snapshot().tokens());
        }
    }
    @Test void finalizedFailureOnlyChargesNetworkFee() {
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            long epoch = e.takeover(); var order = buy(e, epoch, "fail");
            e.reconcile("fail", epoch, observations(order, Confirmation.FINALIZED_FAILURE, 0, 5000));
            assertEquals(Status.FAILED, e.order("fail").status()); assertEquals(n(9995000), e.snapshot().sol());
            assertEquals(n(0), e.snapshot().reservedSol()); assertEquals(n(5000), e.snapshot().grossSolDebits());
        }
    }
    @Test void missingOrDisagreeingReceiptCannotCreateHoldings() {
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            long epoch = e.takeover(); var order = buy(e, epoch, "pending");
            e.reconcile("pending", epoch, observations(order, Confirmation.NOT_FOUND, 0, 0));
            assertEquals(n(110000), e.snapshot().reservedSol()); assertEquals(n(0), e.snapshot().tokens());
            var a = observations(order, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000).getFirst();
            var b = observations(order, Confirmation.FINALIZED_SUCCESS, 3990000000L, 10000).getLast();
            e.reconcile("pending", epoch, List.of(a, b));
            assertEquals(Status.REVIEW, e.order("pending").status()); assertEquals(n(110000), e.snapshot().reservedSol());
        }
    }
    @Test void staleEpochCannotDispatchOrSettleAndKillSwitchDoesNotBlockRecovery() {
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            long old = e.takeover(); var order = buy(e, old, "fenced"); long fresh = e.takeover();
            assertThrows(RuntimeException.class, () -> e.reconcile("fenced", old, observations(order, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000)));
            e.setKillSwitch(true);
            assertThrows(RuntimeException.class, () -> e.reserve(new Request("blocked", Side.BUY, n(100000)), quote(Side.BUY, 100000, 4000000000L)));
            e.reconcile("fenced", fresh, observations(order, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000));
            assertEquals(Status.FINALIZED, e.order("fenced").status());
        }
    }
    @Test void concurrentSellReservationsCannotOversell() throws Exception {
        try (var e = new ExecutionEngine(dir, CLOCK); var pool = Executors.newFixedThreadPool(2)) {
            long epoch = e.takeover(); var b = buy(e, epoch, "buy");
            e.reconcile("buy", epoch, observations(b, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000));
            AtomicInteger accepted = new AtomicInteger();
            var tasks = List.<java.util.concurrent.Callable<Void>>of(() -> { reserveSell(e, "s1", accepted); return null; }, () -> { reserveSell(e, "s2", accepted); return null; });
            for (var f : pool.invokeAll(tasks)) f.get();
            assertEquals(1, accepted.get()); assertEquals(n(4000000000L), e.snapshot().reservedTokens());
        }
    }
    static void reserveSell(ExecutionEngine e, String id, AtomicInteger accepted) {
        try { e.reserve(new Request(id, Side.SELL, n(4000000000L)), quote(Side.SELL, 4000000000L, 98000)); accepted.incrementAndGet(); }
        catch (IllegalStateException ignored) { /* 预期一笔被可用持仓保护拒绝。 */ }
    }
    @Test void expiredQuoteAndExcessAmountAreRejectedWithoutReservation() {
        try (var e = new ExecutionEngine(dir, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC))) {
            assertThrows(RuntimeException.class, () -> e.reserve(new Request("expired", Side.BUY, n(100000)), quote(Side.BUY, 100000, 4000000000L)));
            assertEquals(0, e.snapshot().orderCount()); assertEquals(n(0), e.snapshot().reservedSol());
        }
    }
    @Test void lateDispatchTimeoutCannotEraseAlreadyObservedSignature() {
        try (var e = new ExecutionEngine(dir, CLOCK)) {
            long epoch = e.takeover(); e.reserve(new Request("late", Side.BUY, n(100000)), quote(Side.BUY, 100000, 4000000000L));
            OrderView result = e.dispatch("late", epoch, plan -> {
                OrderView persisted = e.order("late");
                e.reconcile("late", epoch, observations(persisted, Confirmation.CONFIRMED, 4000000000L, 10000));
                e.reconcile("late", epoch, observations(persisted, Confirmation.NOT_FOUND, 0, 0));
                throw new java.io.IOException("late paper gateway timeout");
            });
            assertEquals("paper-tx-" + result.attemptId(), result.signature());
            assertEquals(Status.UNKNOWN, result.status());
            var conflicting = observations(result, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000).stream()
                .map(o -> new Observation(o.provider(), o.attemptId(), o.digest(), "different-paper-signature", o.confirmation(), o.input(), o.output(), o.networkFee(), o.slot())).toList();
            e.reconcile("late", epoch, conflicting);
            assertEquals(Status.REVIEW, e.order("late").status());
            assertEquals(n(0), e.snapshot().tokens()); assertEquals(n(110000), e.snapshot().reservedSol());
        }
    }
}
