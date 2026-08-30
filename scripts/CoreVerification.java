import dev.fincore.domain.*;
import dev.fincore.simulation.ReliabilitySimulation;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class CoreVerification {
    public static void main(String[] args) throws Exception {
        if (SettlementStatus.SUCCESS.canTransitionTo(SettlementStatus.FAILED)) fail("SUCCESS is not terminal");
        if (!SettlementStatus.FAILED.canTransitionTo(SettlementStatus.COMPENSATING)) fail("compensation transition missing");
        BalancedJournal.requireBalanced(List.of(
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.DEBIT, new BigDecimal("11")),
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.CREDIT, new BigDecimal("10")),
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.CREDIT, new BigDecimal("1"))));
        boolean rejected = false;
        try {
            BalancedJournal.requireBalanced(List.of(
                new LedgerPosting(UUID.randomUUID(), LedgerDirection.DEBIT, BigDecimal.TEN),
                new LedgerPosting(UUID.randomUUID(), LedgerDirection.CREDIT, BigDecimal.ONE)));
        } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) fail("unbalanced journal accepted");
        FeeShardRouter router = new FeeShardRouter(16);
        if (router.shardFor("order-123") != router.shardFor("order-123")) fail("routing is not deterministic");
        ShardRouter settlementRouter = new ShardRouter(8);
        if (settlementRouter.shardFor("user-123") != settlementRouter.shardFor("user-123")) fail("settlement routing is not deterministic");
        new FenceToken(1, "worker-a", 1);
        if (!MatchingPolicy.crosses(OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("101"), new BigDecimal("100"))) fail("crossing buy was rejected");
        if (MatchingPolicy.crosses(OrderSide.BUY, OrderType.LIMIT,
                new BigDecimal("99"), new BigDecimal("100"))) fail("non-crossing buy was accepted");
        if (MatchingPolicy.quoteAmount(new BigDecimal("123.45"), new BigDecimal("0.2"))
                .compareTo(new BigDecimal("24.690")) != 0) fail("quote amount lost precision");
        new PlaceOrderCommand("client-1", "user-1", "BTC-USDT", OrderSide.BUY,
            OrderType.LIMIT, new BigDecimal("65000"), new BigDecimal("0.1"));
        UUID maker = UUID.randomUUID();
        UUID taker = UUID.randomUUID();
        new TradeSyncCommand(UUID.randomUUID(), UUID.randomUUID(), "BTC-USDT",
            maker, taker, new BigDecimal("100"), new BigDecimal("2"),
            new BigDecimal("200"), 1);
        boolean badTradeRejected = false;
        try {
            new TradeSyncCommand(UUID.randomUUID(), UUID.randomUUID(), "BTC-USDT",
                maker, taker, new BigDecimal("100"), new BigDecimal("2"),
                new BigDecimal("199"), 2);
        } catch (IllegalArgumentException expected) { badTradeRejected = true; }
        if (!badTradeRejected) fail("conflicting trade amount accepted");
        ReliabilitySimulation.runAndAssert();
        System.out.println("Core verification passed");
    }
    private static void fail(String message) { throw new AssertionError(message); }
}
