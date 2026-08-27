package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Profile("lab")
@Service
public class LabScenarioService {
    private final AccountService accounts;
    private final SettlementService settlements;
    private final CompensationService compensations;
    private final ReconciliationService reconciliation;
    private final FeeAggregationService fees;
    private final ShardLeaseService leases;
    private final JdbcTemplate jdbc;

    public LabScenarioService(AccountService accounts, SettlementService settlements,
                              CompensationService compensations, ReconciliationService reconciliation,
                              FeeAggregationService fees, ShardLeaseService leases, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.settlements = settlements;
        this.compensations = compensations;
        this.reconciliation = reconciliation;
        this.fees = fees;
        this.leases = leases;
        this.jdbc = jdbc;
    }

    public synchronized ScenarioReport runFullScenario() {
        String runId = Long.toString(System.currentTimeMillis());
        Map<String, String> checks = new LinkedHashMap<>();
        var payer = accounts.create("lab-payer-" + runId, "USDT", "USER", new BigDecimal("1000"));
        var payee = accounts.create("lab-payee-" + runId, "USDT", "USER", BigDecimal.ZERO);
        List<FeeAggregationService.FeeAccount> shards = fees.ensureShards("USDT", 16);
        var treasury = fees.ensureTreasury("USDT");

        for (int i = 0; i < 4; i++) {
            SettlementCommand command = new SettlementCommand("lab-msg-" + runId + "-" + i,
                "lab-order-" + runId + "-" + i, payer.accountId(), payee.accountId(),
                shards.get(i).accountId(), "USDT", new BigDecimal("10"), BigDecimal.ONE);
            if (i == 0) {
                runConcurrentDuplicateStorm(command, 21);
            } else {
                SettlementOutcome first = settlements.settle(command);
                if (!"SUCCESS".equals(first.status().name())) throw new IllegalStateException("base settlement failed");
            }
        }
        checks.put("duplicate settlement storm", "PASS");

        var compensation = compensations.compensate("lab-order-" + runId + "-0", "automated lab reversal");
        var repeatedCompensation = compensations.compensate("lab-order-" + runId + "-0", "duplicate request");
        if (!"SUCCESS".equals(compensation.status()) || !repeatedCompensation.duplicate()) {
            throw new IllegalStateException("compensation idempotency failed");
        }
        checks.put("idempotent reverse journal", "PASS");

        var aggregation = fees.aggregate("lab-aggregation-" + runId, "USDT", treasury.accountId());
        var repeatedAggregation = fees.aggregate("lab-aggregation-" + runId, "USDT", treasury.accountId());
        if (!"SUCCESS".equals(aggregation.status()) || !repeatedAggregation.duplicate()) {
            throw new IllegalStateException("fee aggregation idempotency failed");
        }
        checks.put("fee shard aggregation", "PASS: " + aggregation.totalAmount().toPlainString() + " USDT");

        int shardId = Math.floorMod(runId.hashCode(), 1_000_000) + 10_000;
        String workerA = "lab-worker-a-" + runId;
        String workerB = "lab-worker-b-" + runId;
        ShardLeaseService.Lease leaseA = leases.claim(shardId, workerA, Duration.ofSeconds(30));
        if (!leases.drain(shardId, workerA, leaseA.epoch())) throw new IllegalStateException("drain failed");
        jdbc.update("UPDATE shard_lease SET lease_until=now() - interval '1 second' WHERE shard_id=?", shardId);
        ShardLeaseService.Lease leaseB = leases.claim(shardId, workerB, Duration.ofSeconds(30));
        SettlementCommand fencedCommand = new SettlementCommand("lab-fence-msg-" + runId,
            "lab-fence-order-" + runId, payer.accountId(), payee.accountId(), shards.get(5).accountId(),
            "USDT", BigDecimal.ONE, new BigDecimal("0.1"));
        boolean staleRejected = false;
        try {
            settlements.settle(fencedCommand, new FenceToken(shardId, workerA, leaseA.epoch()));
        } catch (IllegalStateException expected) {
            staleRejected = expected.getMessage().startsWith("fence rejected");
        }
        if (!staleRejected) throw new IllegalStateException("stale worker was not rejected");
        SettlementOutcome fenced = settlements.settle(fencedCommand, new FenceToken(shardId, workerB, leaseB.epoch()));
        if (!"SUCCESS".equals(fenced.status().name())) throw new IllegalStateException("new owner failed to settle");
        checks.put("scale-down stale epoch rejection", "PASS");

        fees.aggregate("lab-final-aggregation-" + runId, "USDT", treasury.accountId());
        jdbc.update("UPDATE account SET balance=balance+7, updated_at=now() WHERE account_id=?", payee.accountId());
        ReconciliationService.ReconciliationReport report = reconciliation.reconcileAll();
        boolean found = report.differences().stream().anyMatch(d -> d.accountId().equals(payee.accountId()));
        if (!found) throw new IllegalStateException("reconciliation did not detect injected corruption");
        checks.put("reconciliation corruption detection", "PASS");

        return new ScenarioReport(runId, Instant.now(), checks, payer.accountId(), payee.accountId(),
            treasury.accountId(), report.differenceCount());
    }

    private void runConcurrentDuplicateStorm(SettlementCommand command, int deliveries) {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<SettlementOutcome>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < deliveries; i++) tasks.add(() -> settlements.settle(command));
            List<Future<SettlementOutcome>> results = pool.invokeAll(tasks);
            int financialEffects = 0;
            int duplicates = 0;
            for (Future<SettlementOutcome> result : results) {
                SettlementOutcome outcome = result.get();
                if (outcome.duplicate()) duplicates++; else financialEffects++;
            }
            if (financialEffects != 1 || duplicates != deliveries - 1) {
                throw new IllegalStateException("duplicate storm mismatch: effects=" + financialEffects + ", duplicates=" + duplicates);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("duplicate storm interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("duplicate storm failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    public record ScenarioReport(String runId, Instant completedAt, Map<String, String> checks,
                                 UUID payerAccountId, UUID payeeAccountId, UUID treasuryAccountId,
                                 int totalOpenDifferences) {}
}
