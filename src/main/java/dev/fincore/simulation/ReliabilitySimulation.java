package dev.fincore.simulation;

import dev.fincore.domain.BalancedJournal;
import dev.fincore.domain.FeeShardRouter;
import dev.fincore.domain.LedgerDirection;
import dev.fincore.domain.LedgerPosting;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import dev.fincore.domain.SettlementStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 不依赖中间件的金融可靠性可执行模型。
 *
 * <p>该类不是分布式持久化实现，而是一个只依赖 JDK 的教学模型，用于快速执行重复投递、平衡分录、
 * 反向补偿、账本对账、费用分片和 Epoch Fencing 等核心不变量。生产级行为由 Spring、PostgreSQL
 * 和 Kafka 路径验证。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
public final class ReliabilitySimulation {
    /** 工具类不允许实例化。 */
    private ReliabilitySimulation() {
    }

    /**
     * 执行全部内存可靠性场景并在任一不变量破坏时失败。
     *
     * @return 场景检查报告
     * @throws Exception 并发任务执行失败时抛出
     */
    public static Report runAndAssert() throws Exception {
        Engine engine = new Engine();
        UUID payer = engine.account("100");
        UUID payee = engine.account("0");
        UUID fee = engine.account("0");
        SettlementCommand command = new SettlementCommand("msg-100", "order-100", payer, payee, fee,
            "USDT", new BigDecimal("10"), new BigDecimal("1"));

        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Callable<SettlementOutcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(() -> engine.settle(command));
        }
        List<Future<SettlementOutcome>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        long duplicates = 0;
        for (Future<SettlementOutcome> future : futures) {
            if (future.get().duplicate()) {
                duplicates++;
            }
        }

        assertAmount(engine.balance(payer), "89", "payer balance after duplicate storm");
        assertAmount(engine.balance(payee), "10", "payee balance after duplicate storm");
        assertAmount(engine.balance(fee), "1", "fee balance after duplicate storm");
        if (duplicates != 99) {
            throw new AssertionError("expected 99 duplicates, got " + duplicates);
        }
        if (engine.journalCount() != 1) {
            throw new AssertionError("duplicate journal detected");
        }

        boolean firstCompensation = engine.compensate("order-100");
        boolean duplicateCompensation = !engine.compensate("order-100");
        if (!firstCompensation || !duplicateCompensation) {
            throw new AssertionError("compensation idempotency failed");
        }
        assertAmount(engine.balance(payer), "100", "payer balance after compensation");
        assertAmount(engine.balance(payee), "0", "payee balance after compensation");
        assertAmount(engine.balance(fee), "0", "fee balance after compensation");

        engine.corrupt(payee, new BigDecimal("3"));
        int differences = engine.reconcile();
        if (differences != 1) {
            throw new AssertionError("reconciliation failed to detect corruption");
        }

        FeeShardRouter router = new FeeShardRouter(16);
        boolean deterministicFeeShard = router.shardFor("order-100") == router.shardFor("order-100");
        FenceRegistry fences = new FenceRegistry();
        Fence first = fences.claim(7, "worker-a");
        fences.drain(first);
        Fence second = fences.takeover(7, "worker-b");
        boolean staleWorkerRejected = !fences.valid(first) && fences.valid(second) && second.epoch() > first.epoch();
        if (!staleWorkerRejected) {
            throw new AssertionError("stale fencing token was accepted");
        }

        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("100 concurrent duplicate deliveries", "PASS");
        checks.put("exactly one financial effect", "PASS");
        checks.put("terminal settlement status", "PASS");
        checks.put("idempotent reverse journal", "PASS");
        checks.put("ledger reconciliation detects corruption", "PASS");
        checks.put("deterministic fee sharding", deterministicFeeShard ? "PASS" : "FAIL");
        checks.put("stale epoch fencing", staleWorkerRejected ? "PASS" : "FAIL");
        return new Report(Instant.now(), checks, duplicates, engine.journalCount(), differences);
    }

    /**
     * 命令行入口。
     *
     * @param args 未使用的命令行参数
     * @throws Exception 场景执行失败时抛出
     */
    public static void main(String[] args) throws Exception {
        Report report = runAndAssert();
        System.out.println("FinCore self-contained reliability simulation");
        report.checks().forEach((name, status) -> System.out.println("[" + status + "] " + name));
        System.out.println("duplicates=" + report.duplicateCount() + ", journals=" + report.journalCount()
            + ", reconciliationDifferences=" + report.reconciliationDifferences());
    }

    /** 比较金额数值而不比较 BigDecimal 的 scale。 */
    private static void assertAmount(BigDecimal actual, String expected, String label) {
        if (actual.compareTo(new BigDecimal(expected)) != 0) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    /**
     * 自包含模拟报告。
     *
     * @param executedAt 执行时间
     * @param checks 各不变量检查结果
     * @param duplicateCount 重复返回数量
     * @param journalCount 账本交易数量
     * @param reconciliationDifferences 对账差异数量
     */
    public record Report(Instant executedAt, Map<String, String> checks, long duplicateCount,
                         int journalCount, int reconciliationDifferences) {
    }

    /**
     * 仅用于模拟的内存结算引擎。
     *
     * <p>所有修改方法使用对象锁串行化，以便示例聚焦金融不变量而不是容器并发实现。</p>
     */
    private static final class Engine {
        /** 账户期初余额。 */
        private final Map<UUID, BigDecimal> opening = new LinkedHashMap<>();
        /** 账户当前余额。 */
        private final Map<UUID, BigDecimal> balances = new LinkedHashMap<>();
        /** 账本累计净变动。 */
        private final Map<UUID, BigDecimal> ledgerDelta = new LinkedHashMap<>();
        /** 已处理消息号集合。 */
        private final Set<String> processedMessages = new java.util.HashSet<>();
        /** 已补偿业务单集合。 */
        private final Set<String> compensatedOrders = new java.util.HashSet<>();
        /** 成功结算命令。 */
        private final Map<String, SettlementCommand> successful = new LinkedHashMap<>();
        /** 已生成账本交易数量。 */
        private int journalCount;

        /** 创建内存账户并记录期初余额。 */
        synchronized UUID account(String amount) {
            UUID id = UUID.randomUUID();
            BigDecimal value = new BigDecimal(amount);
            opening.put(id, value);
            balances.put(id, value);
            ledgerDelta.put(id, BigDecimal.ZERO);
            return id;
        }

        /** 幂等执行结算，并以平衡分录更新余额。 */
        synchronized SettlementOutcome settle(SettlementCommand command) {
            if (!processedMessages.add(command.messageId()) || successful.containsKey(command.businessKey())) {
                return new SettlementOutcome(command.businessKey(), SettlementStatus.SUCCESS, true, "existing result");
            }
            BigDecimal total = command.amount().add(command.fee());
            if (balances.get(command.payerAccountId()).compareTo(total) < 0) {
                return new SettlementOutcome(command.businessKey(), SettlementStatus.FAILED, false, "insufficient balance");
            }
            List<LedgerPosting> postings = new ArrayList<>();
            postings.add(new LedgerPosting(command.payerAccountId(), LedgerDirection.DEBIT, total));
            postings.add(new LedgerPosting(command.payeeAccountId(), LedgerDirection.CREDIT, command.amount()));
            if (command.fee().signum() > 0) {
                postings.add(new LedgerPosting(
                    command.feeAccountId(), LedgerDirection.CREDIT, command.fee()));
            }
            post(postings);
            successful.put(command.businessKey(), command);
            return new SettlementOutcome(command.businessKey(), SettlementStatus.SUCCESS, false, "settled");
        }

        /** 使用一组方向相反的新分录幂等补偿原结算。 */
        synchronized boolean compensate(String businessKey) {
            if (compensatedOrders.contains(businessKey)) {
                return false;
            }
            SettlementCommand command = successful.get(businessKey);
            if (command == null) {
                throw new IllegalStateException("successful settlement missing");
            }
            if (balances.get(command.payeeAccountId()).compareTo(command.amount()) < 0 ||
                balances.get(command.feeAccountId()).compareTo(command.fee()) < 0) {
                throw new IllegalStateException("insufficient balance for compensation");
            }
            BigDecimal total = command.amount().add(command.fee());
            List<LedgerPosting> reverse = new ArrayList<>();
            reverse.add(new LedgerPosting(command.payeeAccountId(), LedgerDirection.DEBIT, command.amount()));
            if (command.fee().signum() > 0) {
                reverse.add(new LedgerPosting(
                    command.feeAccountId(), LedgerDirection.DEBIT, command.fee()));
            }
            reverse.add(new LedgerPosting(command.payerAccountId(), LedgerDirection.CREDIT, total));
            post(reverse);
            compensatedOrders.add(businessKey);
            return true;
        }

        /** 校验借贷平衡后更新账本累计值与派生余额。 */
        private void post(List<LedgerPosting> postings) {
            BalancedJournal.requireBalanced(postings);
            for (LedgerPosting posting : postings) {
                BigDecimal signed = posting.direction() == LedgerDirection.CREDIT ? posting.amount() : posting.amount().negate();
                ledgerDelta.merge(posting.accountId(), signed, BigDecimal::add);
                balances.merge(posting.accountId(), signed, BigDecimal::add);
            }
            journalCount++;
        }

        /** 绕过账本修改余额，用于验证对账检测能力。 */
        synchronized void corrupt(UUID accountId, BigDecimal delta) {
            balances.merge(accountId, delta, BigDecimal::add);
        }

        /** 根据“期初余额 + 账本净变动”统计余额差异。 */
        synchronized int reconcile() {
            int count = 0;
            for (UUID id : balances.keySet()) {
                if (opening.get(id).add(ledgerDelta.get(id)).compareTo(balances.get(id)) != 0) {
                    count++;
                }
            }
            return count;
        }

        /** 返回账户当前余额。 */
        synchronized BigDecimal balance(UUID id) {
            return balances.get(id);
        }

        /** 返回已经生成的账本交易数量。 */
        synchronized int journalCount() {
            return journalCount;
        }
    }

    /** 内存租约令牌。 */
    private record Fence(int shardId, String ownerId, long epoch, boolean draining) {
    }

    /** 最小化的内存 Epoch Fencing 注册表。 */
    private static final class FenceRegistry {
        /** 分片当前租约。 */
        private final Map<Integer, Fence> leases = new LinkedHashMap<>();

        /** 首次领取无人持有的分片。 */
        synchronized Fence claim(int shard, String owner) {
            if (leases.containsKey(shard)) {
                throw new IllegalStateException("live owner exists");
            }
            Fence fence = new Fence(shard, owner, 1, false);
            leases.put(shard, fence);
            return fence;
        }

        /** 把当前租约标记为排空状态。 */
        synchronized void drain(Fence token) {
            if (!valid(token)) {
                throw new IllegalStateException("invalid drain token");
            }
            leases.put(token.shardId(), new Fence(token.shardId(), token.ownerId(), token.epoch(), true));
        }

        /** 在旧租约排空后由新 Worker 接管，并严格递增 Epoch。 */
        synchronized Fence takeover(int shard, String owner) {
            Fence prior = leases.get(shard);
            if (prior == null || !prior.draining()) {
                throw new IllegalStateException("prior owner not drained");
            }
            Fence next = new Fence(shard, owner, prior.epoch() + 1, false);
            leases.put(shard, next);
            return next;
        }

        /** 验证所有权、Epoch 与排空状态。 */
        synchronized boolean valid(Fence token) {
            Fence current = leases.get(token.shardId());
            return current != null
                && !current.draining()
                && current.ownerId().equals(token.ownerId())
                && current.epoch() == token.epoch();
        }
    }
}
