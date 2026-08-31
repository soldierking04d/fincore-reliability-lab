package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import dev.fincore.infrastructure.persistence.mapper.LabScenarioMapper;
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
import org.springframework.stereotype.Service;

/**
 * 金融核心链路的一键综合实验编排服务。
 *
 * <p>该服务只在 {@code lab} Profile 下启用，依次验证重复投递、反向分录补偿、费用分片聚合、
 * Worker 接管与 Epoch Fencing，以及账本对账发现人工错账。场景中的失败均直接抛出，避免输出
 * 具有误导性的“成功报告”。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Profile("lab")
@Service
public class LabScenarioService {
    /** 账户服务。 */
    private final AccountService accounts;
    /** 结算服务。 */
    private final SettlementService settlements;
    /** 反向分录补偿服务。 */
    private final CompensationService compensations;
    /** 资金账本对账服务。 */
    private final ReconciliationService reconciliation;
    /** 费用分片与归集服务。 */
    private final FeeAggregationService fees;
    /** 分片租约服务。 */
    private final ShardLeaseService leases;
    /** 仅供实验环境使用的故障注入持久化接口。 */
    private final LabScenarioMapper labMapper;

    /**
     * 创建综合实验编排服务。
     *
     * @param accounts 账户服务
     * @param settlements 结算服务
     * @param compensations 补偿服务
     * @param reconciliation 资金账本对账服务
     * @param fees 费用分片与归集服务
     * @param leases 分片租约服务
     * @param labMapper 实验故障注入持久化接口
     */
    public LabScenarioService(AccountService accounts, SettlementService settlements,
                              CompensationService compensations, ReconciliationService reconciliation,
                              FeeAggregationService fees, ShardLeaseService leases,
                              LabScenarioMapper labMapper) {
        this.accounts = accounts;
        this.settlements = settlements;
        this.compensations = compensations;
        this.reconciliation = reconciliation;
        this.fees = fees;
        this.leases = leases;
        this.labMapper = labMapper;
    }

    /**
     * 串行执行完整可靠性场景。
     *
     * <p>方法使用 {@code synchronized} 防止同一进程并行运行多个综合场景，降低故障注入相互影响。</p>
     *
     * @return 场景编号、账户编号和全部断言结果
     */
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
                // 第一笔故意并发投递 21 次，验证只有一次资金效果。
                runConcurrentDuplicateStorm(command, 21);
            } else {
                SettlementOutcome first = settlements.settle(command);
                if (!"SUCCESS".equals(first.status().name())) {
                    throw new IllegalStateException("base settlement failed");
                }
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
        if (!leases.drain(shardId, workerA, leaseA.epoch())) {
            throw new IllegalStateException("drain failed");
        }
        // 人工推进租约时间，模拟旧 Worker 排空后失去所有权。
        labMapper.injectExpiredLease(shardId);
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
        if (!staleRejected) {
            throw new IllegalStateException("stale worker was not rejected");
        }
        SettlementOutcome fenced = settlements.settle(fencedCommand, new FenceToken(shardId, workerB, leaseB.epoch()));
        if (!"SUCCESS".equals(fenced.status().name())) {
            throw new IllegalStateException("new owner failed to settle");
        }
        checks.put("scale-down stale epoch rejection", "PASS");

        fees.aggregate("lab-final-aggregation-" + runId, "USDT", treasury.accountId());
        // 直接修改余额但不写账本，验证对账能够冻结并报告差异。
        labMapper.injectBalanceCorruption(payee.accountId(), new BigDecimal("7"));
        ReconciliationService.ReconciliationReport report = reconciliation.reconcileAll();
        boolean found = report.differences().stream().anyMatch(d -> d.accountId().equals(payee.accountId()));
        if (!found) {
            throw new IllegalStateException("reconciliation did not detect injected corruption");
        }
        checks.put("reconciliation corruption detection", "PASS");

        return new ScenarioReport(runId, Instant.now(), checks, payer.accountId(), payee.accountId(),
            treasury.accountId(), report.differenceCount());
    }

    /**
     * 并发重复投递同一结算命令，并验证只有一次资金效果。
     *
     * @param command 结算命令
     * @param deliveries 并发投递次数
     */
    private void runConcurrentDuplicateStorm(SettlementCommand command, int deliveries) {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<SettlementOutcome>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < deliveries; i++) {
                tasks.add(() -> settlements.settle(command));
            }
            List<Future<SettlementOutcome>> results = pool.invokeAll(tasks);
            int financialEffects = 0;
            int duplicates = 0;
            for (Future<SettlementOutcome> result : results) {
                SettlementOutcome outcome = result.get();
                if (outcome.duplicate()) {
                    duplicates++;
                } else {
                    financialEffects++;
                }
            }
            if (financialEffects != 1 || duplicates != deliveries - 1) {
                throw new IllegalStateException("duplicate storm mismatch: effects=" + financialEffects + ", duplicates=" + duplicates);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("duplicate storm interrupted", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("duplicate storm failed", exception.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 综合实验结果。
     *
     * @param runId 实验编号
     * @param completedAt 完成时间
     * @param checks 各可靠性断言结果
     * @param payerAccountId 付款账户
     * @param payeeAccountId 收款账户
     * @param treasuryAccountId 费用归集账户
     * @param totalOpenDifferences 未关闭的对账差异数
     */
    public record ScenarioReport(String runId, Instant completedAt, Map<String, String> checks,
                                 UUID payerAccountId, UUID payeeAccountId, UUID treasuryAccountId,
                                 int totalOpenDifferences) {
    }
}
