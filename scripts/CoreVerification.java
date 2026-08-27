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
        ReliabilitySimulation.runAndAssert();
        System.out.println("Core verification passed");
    }
    private static void fail(String message) { throw new AssertionError(message); }
}
