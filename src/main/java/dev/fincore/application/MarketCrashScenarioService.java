package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import dev.fincore.domain.TradeSyncCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.infrastructure.persistence.mapper.LabScenarioMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 市场暴跌日复合实验 / Composite market-crash-day experiment.
 *
 * <p>只复现公开事故中的故障类型，不使用任何公司的源码、流量或内部参数。
 * It reproduces public failure patterns, never private implementation details.</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Profile("lab")
@Service
public class MarketCrashScenarioService {
    /** 预置买单数量。 */
    private static final int MAKERS = 60;
    /** 并发市价卖方数量。 */
    private static final int CONCURRENT_SELLERS = 12;
    /** 进入资金结算的成交数量。 */
    private static final int SETTLEMENTS = 6;
    /** 同一结算消息的并发重复投递次数。 */
    private static final int DUPLICATE_DELIVERIES = 17;

    /** 撮合服务。 */
    private final MatchingService matching;
    /** 成交同步与修复服务。 */
    private final TradeReliabilityService reliability;
    /** 账户服务。 */
    private final AccountService accounts;
    /** 资金结算服务。 */
    private final SettlementService settlements;
    /** Worker 分片租约服务。 */
    private final ShardLeaseService leases;
    /** 实验故障注入与数据库事实断言持久化接口。 */
    private final LabScenarioMapper labMapper;

    /**
     * 创建市场暴跌日场景编排器。
     *
     * @param matching 撮合服务
     * @param reliability 成交同步与修复服务
     * @param accounts 账户服务
     * @param settlements 资金结算服务
     * @param leases Worker 分片租约服务
     * @param labMapper 实验故障注入与事实断言接口
     */
    public MarketCrashScenarioService(MatchingService matching,
                                      TradeReliabilityService reliability,
                                      AccountService accounts,
                                      SettlementService settlements,
                                      ShardLeaseService leases,
                                      LabScenarioMapper labMapper) {
        this.matching = matching;
        this.reliability = reliability;
        this.accounts = accounts;
        this.settlements = settlements;
        this.leases = leases;
        this.labMapper = labMapper;
    }

    /**
     * 执行市场暴跌日端到端场景并验证全部不变量。
     *
     * <p>场景串联撮合冲击、请求重放、流动性耗尽、Worker 接管、结算重复投递、成交投影污染与
     * 自动恢复。使用 {@code synchronized} 避免同一实例并行注入复合故障。</p>
     *
     * @return 时间线、指标、证据和检查结果
     */
    public synchronized MarketCrashReport runMarketCrashDay() {
        Instant startedAt = Instant.now();
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String symbol = "CRASH" + runId.toUpperCase() + "-USDT";
        List<Phase> timeline = new ArrayList<>();
        Map<String, String> checks = new LinkedHashMap<>();

        seedBuyLiquidity(runId, symbol);
        timeline.add(new Phase("T-05m", "预置三档买方流动性",
            "60 张买单分布在 100、99、98 三个价位",
            "订单按交易对持久化排序",
            "60 张 OPEN Maker，价格/时间优先"));

        TrafficOutcome traffic = runSellOff(runId, symbol);
        verifyMatching(symbol, traffic);
        checks.put("大行情撮合", "PASS：12 路并发卖单形成 60 条唯一成交");
        timeline.add(new Phase("T+00s", "价格连续吃穿三档深度",
            "12 路市价卖单同时进入同一热门交易对",
            "交易对级数据库锁串行确定成交顺序",
            "60 条成交、60 个唯一序列、订单数量守恒"));

        PlaceOrderCommand firstCommand = traffic.commands().get(0);
        long beforeReplay = labMapper.countTrades(symbol);
        MatchingResult replay = matching.place(firstCommand);
        require(replay.order().duplicate() && replay.trades().size() == 5,
            "相同客户端订单重试未幂等返回 / retry was not idempotent");
        require(labMapper.countTrades(symbol) == beforeReplay,
            "重试生成了重复成交 / retry duplicated trades");

        boolean conflictRejected = false;
        try {
            matching.place(new PlaceOrderCommand(
                firstCommand.clientOrderId(), firstCommand.userId(), symbol,
                OrderSide.SELL, OrderType.MARKET, null, new BigDecimal("6")));
        } catch (IllegalArgumentException expected) {
            conflictRejected = true;
        }
        require(conflictRejected, "冲突重放未拒绝 / conflicting replay accepted");
        checks.put("请求重放", "PASS：相同请求幂等，篡改数量的重放拒绝");
        timeline.add(new Phase("T+01s", "客户端超时后集中重试",
            "同一业务键重复提交，并尝试篡改数量",
            "相同载荷返回原结果；冲突载荷立即拒绝",
            "成交事实仍为 60 条"));

        MatchingResult overflow = matching.place(new PlaceOrderCommand(
            "overflow-" + runId, "panic-seller-overflow-" + runId, symbol,
            OrderSide.SELL, OrderType.MARKET, null, new BigDecimal("10")));
        require(overflow.order().status() == OrderStatus.REJECTED
            && overflow.trades().isEmpty(),
            "深度耗尽后未安全拒单 / empty book did not reject safely");
        checks.put("流动性耗尽", "PASS：无深度时拒单，不生成幽灵成交");
        timeline.add(new Phase("T+02s", "买盘完全耗尽",
            "额外 10 单位市价卖单进入空订单簿",
            "订单进入 REJECTED 终态，不伪造成交",
            "0 新成交，余额和持仓不受影响"));

        SettlementEvidence settlement = runFailoverAndSettlement(
            runId, symbol, traffic.trades());
        checks.put("节点接管", "PASS：旧 Epoch 拒写，新 Worker 完成结算");
        checks.put("结算重投", "PASS：17 次投递只有 1 次资金效果");
        timeline.add(new Phase("T+03s", "结算 Worker 接管",
            "旧节点在排空、Lease 过期后恢复写入",
            "Epoch Fencing 在资金事务内拒绝旧节点",
            "旧 Epoch 被拒；新 Epoch=" + settlement.newEpoch()));
        timeline.add(new Phase("T+04s", "消息系统重复投递",
            "同一结算消息并发投递 17 次",
            "Inbox、message_id、business_key 三层幂等",
            "6 笔结算、6 个账本交易、无重复入账"));

        TruthSnapshot truthBefore = truth(symbol);
        RecoveryEvidence recovery = injectAndRecover(symbol, runId, traffic.trades());
        TruthSnapshot truthAfter = truth(symbol);
        require(sameTruth(truthBefore, truthAfter),
            "修复修改了权威成交 / repair changed authoritative trades");
        checks.put("同步异常发现",
            "PASS：同时识别 MISSING=1、MISMATCH=1、EXTRA=1");
        checks.put("派生数据修复",
            "PASS：重建 2 条、隔离 1 条、重复修复不二次执行");
        checks.put("权威事实保护", "PASS：修复前后成交事实快照一致");
        checks.put("最终收敛", "PASS：再次对账为 CLEAN");

        timeline.add(new Phase("T+05s", "行情与成交查询出现偏差",
            "乱序、重复、漏同步、错值和幽灵成交同时注入",
            "事件指纹幂等；全量外连接对账",
            "MISSING=1、MISMATCH=1、EXTRA=1"));
        timeline.add(new Phase("T+06s", "恢复任务可能重复执行",
            "相同 repair key 连续提交两次",
            "只重建派生投影，EXTRA 进入隔离区",
            "重建 2、隔离 1，第二次 duplicate=true"));
        timeline.add(new Phase("T+07s", "恢复后复市判定",
            "再次比较权威成交与活动投影",
            "只有 CLEAN 才允许闭环",
            "60 条权威成交 = 60 条活动投影"));

        long ledgerMismatches = ledgerMismatchCount(
            settlement.payerId(), settlement.payeeId(), settlement.feeId());
        require(ledgerMismatches == 0,
            "资金余额与账本不一致 / balance-ledger mismatch");
        checks.put("资金账本", "PASS：借贷平衡且三个场景账户对账一致");

        long totalElapsedMs = Math.max(1,
            Duration.between(startedAt, Instant.now()).toMillis());
        CrashMetrics metrics = new CrashMetrics(
            MAKERS, CONCURRENT_SELLERS, traffic.trades().size(),
            labMapper.countDistinctTradeSequences(symbol),
            labMapper.countTradeOutboxEvents("%\"symbol\":\"" + symbol + "\"%"),
            SETTLEMENTS, DUPLICATE_DELIVERIES,
            settlement.ledgerTransactions(), recovery.repairedCount(),
            recovery.quarantinedCount(), traffic.elapsedMs(),
            traffic.rate(), totalElapsedMs);
        return new MarketCrashReport(
            "市场暴跌日 / Market Crash Day", runId, symbol,
            startedAt, Instant.now(), "RECOVERED", designBasis(),
            List.copyOf(timeline), metrics, recovery, checks,
            "这是正确性与恢复性实验，不是生产 TPS、灾备或可用性认证。"
                + " / Correctness lab, not a production capacity certificate.");
    }

    /** 在三个价位预置确定数量的买方流动性。 */
    private void seedBuyLiquidity(String runId, String symbol) {
        for (int i = 0; i < MAKERS; i++) {
            BigDecimal price = new BigDecimal(
                i < 20 ? "100" : i < 40 ? "99" : "98");
            matching.place(new PlaceOrderCommand(
                "crash-maker-" + runId + "-" + i,
                "crash-buyer-" + runId + "-" + i,
                symbol, OrderSide.BUY, OrderType.LIMIT,
                price, BigDecimal.ONE));
        }
    }

    /** 使用同一个起跑闩锁并发提交市价卖单，形成热点卖压。 */
    private TrafficOutcome runSellOff(String runId, String symbol) {
        List<PlaceOrderCommand> commands = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_SELLERS; i++) {
            commands.add(new PlaceOrderCommand(
                "crash-taker-" + runId + "-" + i,
                "panic-seller-" + runId + "-" + i,
                symbol, OrderSide.SELL, OrderType.MARKET,
                null, new BigDecimal("5")));
        }
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MatchingResult>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (PlaceOrderCommand command : commands) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return matching.place(command);
                }));
            }
            start.countDown();
            List<TradeView> trades = new ArrayList<>();
            for (Future<MatchingResult> future : futures) {
                trades.addAll(future.get().trades());
            }
            long elapsedMs = Math.max(1,
                (System.nanoTime() - started) / 1_000_000);
            BigDecimal rate = BigDecimal.valueOf(trades.size())
                .multiply(BigDecimal.valueOf(1000))
                .divide(BigDecimal.valueOf(elapsedMs), 2, RoundingMode.HALF_UP);
            trades.sort(Comparator.comparingLong(TradeView::sequence));
            return new TrafficOutcome(List.copyOf(commands),
                List.copyOf(trades), elapsedMs, rate);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("暴跌流量实验被中断 / interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("暴跌流量实验失败 / failed",
                exception.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 从数据库核验成交数量、序号、订单数量和 Outbox 事件完整性。 */
    private void verifyMatching(String symbol, TrafficOutcome traffic) {
        require(traffic.trades().size() == MAKERS,
            "成交数量错误 / unexpected trade count");
        require(labMapper.countTrades(symbol) == MAKERS,
            "成交落库数量错误 / stored trade count mismatch");
        require(labMapper.countDistinctTradeSequences(symbol) == MAKERS,
            "成交序列不唯一 / duplicate trade sequence");
        require(labMapper.countBrokenOrders(symbol) == 0,
            "订单数量不守恒 / order quantity not conserved");
        require(labMapper.countTradeOutboxEvents(
            "%\"symbol\":\"" + symbol + "\"%") == MAKERS,
            "成交 Outbox 不完整 / trade outbox incomplete");
    }

    /** 模拟旧 Worker 排空、新 Worker 接管及结算消息重复投递。 */
    private SettlementEvidence runFailoverAndSettlement(
        String runId, String symbol, List<TradeView> trades) {
        var payer = accounts.create("crash-payer-" + runId,
            "USDT", "USER", new BigDecimal("10000"));
        var payee = accounts.create("crash-payee-" + runId,
            "USDT", "USER", BigDecimal.ZERO);
        var fee = accounts.create("crash-fee-" + runId,
            "USDT", "SYSTEM_FEE", BigDecimal.ZERO);

        int shardId = Math.floorMod(runId.hashCode(), 900_000) + 100_000;
        String oldWorker = "crash-worker-old-" + runId;
        String newWorker = "crash-worker-new-" + runId;
        var oldLease = leases.claim(shardId, oldWorker, Duration.ofSeconds(30));
        require(leases.drain(shardId, oldWorker, oldLease.epoch()),
            "旧节点排空失败 / drain failed");
        labMapper.injectExpiredLease(shardId);
        var newLease = leases.claim(shardId, newWorker, Duration.ofSeconds(30));

        SettlementCommand first = settlementCommand(
            runId, 0, trades.get(0), payer.accountId(),
            payee.accountId(), fee.accountId());
        boolean staleRejected = false;
        try {
            settlements.settle(first,
                new FenceToken(shardId, oldWorker, oldLease.epoch()));
        } catch (IllegalStateException expected) {
            staleRejected = expected.getMessage().startsWith("fence rejected");
        }
        require(staleRejected,
            "旧节点未被 Fencing 拒绝 / stale worker was not fenced");

        SettlementStorm storm = runSettlementStorm(first,
            new FenceToken(shardId, newWorker, newLease.epoch()));
        require(storm.effects() == 1
            && storm.duplicates() == DUPLICATE_DELIVERIES - 1,
            "结算重投产生重复资金效果 / duplicate financial effect");

        for (int i = 1; i < SETTLEMENTS; i++) {
            SettlementOutcome outcome = settlements.settle(
                settlementCommand(runId, i, trades.get(i),
                    payer.accountId(), payee.accountId(), fee.accountId()),
                new FenceToken(shardId, newWorker, newLease.epoch()));
            require("SUCCESS".equals(outcome.status().name()),
                "后续结算失败 / settlement failed");
        }
        String settlementPattern = "crash-settle-" + runId + "-%";
        long ledgerTransactions = labMapper.countLedgerTransactions(settlementPattern);
        require(ledgerTransactions == SETTLEMENTS,
            "账本交易数量错误 / ledger transaction count mismatch");
        require(labMapper.countSuccessfulSettlements(settlementPattern) == SETTLEMENTS,
            "结算终态数量错误 / settlement terminal count mismatch");

        return new SettlementEvidence(payer.accountId(), payee.accountId(),
            fee.accountId(), oldLease.epoch(), newLease.epoch(),
            staleRejected, storm.effects(), storm.duplicates(),
            ledgerTransactions, symbol);
    }

    /** 根据成交构造资金结算命令。 */
    private SettlementCommand settlementCommand(
        String runId, int index, TradeView trade,
        UUID payerId, UUID payeeId, UUID feeId) {
        return new SettlementCommand(
            "crash-msg-" + runId + "-" + index,
            "crash-settle-" + runId + "-" + index,
            payerId, payeeId, feeId, "USDT",
            trade.quoteAmount(), new BigDecimal("0.01"));
    }

    /** 并发重复执行同一结算命令，并统计真实资金效果与幂等返回。 */
    private SettlementStorm runSettlementStorm(
        SettlementCommand command, FenceToken fence) {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Callable<SettlementOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < DUPLICATE_DELIVERIES; i++) {
                tasks.add(() -> settlements.settle(command, fence));
            }
            List<Future<SettlementOutcome>> futures = pool.invokeAll(tasks);
            int effects = 0;
            int duplicates = 0;
            for (Future<SettlementOutcome> future : futures) {
                if (future.get().duplicate()) {
                    duplicates++;
                } else {
                    effects++;
                }
            }
            return new SettlementStorm(effects, duplicates);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("结算重投实验被中断 / interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("结算重投实验失败 / failed",
                exception.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 注入乱序、重复、漏数、错值和幽灵成交，再验证幂等恢复闭环。 */
    private RecoveryEvidence injectAndRecover(
        String symbol, String runId, List<TradeView> sourceTrades) {
        List<TradeView> reverse = sourceTrades.stream()
            .sorted(Comparator.comparingLong(TradeView::sequence).reversed())
            .toList();
        TradeView missing = reverse.get(0);
        TradeView mismatch = reverse.get(1);
        UUID duplicateEventId = null;
        TradeView duplicateTrade = null;

        for (TradeView trade : reverse) {
            if (trade.tradeId().equals(missing.tradeId())) {
                continue;
            }
            UUID eventId = UUID.randomUUID();
            reliability.apply(TradeSyncCommand.from(eventId, trade));
            if (duplicateEventId == null) {
                duplicateEventId = eventId;
                duplicateTrade = trade;
            }
        }
        require(duplicateEventId != null && duplicateTrade != null,
            "缺少可重放事件 / no event available for replay");
        var duplicate = reliability.apply(
            TradeSyncCommand.from(duplicateEventId, duplicateTrade));
        require(duplicate.duplicateEvent(),
            "重复成交事件未识别 / duplicate event not detected");

        // 只污染派生投影，权威成交事实保持不变。
        labMapper.injectProjectionMismatch(mismatch.tradeId());

        long ghostSequence = sourceTrades.stream()
            .mapToLong(TradeView::sequence).max().orElseThrow() + 10_000;
        TradeView basis = sourceTrades.get(0);
        TradeSyncCommand ghost = new TradeSyncCommand(
            UUID.randomUUID(), UUID.randomUUID(), symbol,
            basis.makerOrderId(), basis.takerOrderId(),
            basis.price(), BigDecimal.ONE, basis.price(), ghostSequence);
        reliability.apply(ghost);

        var dirty = reliability.reconcile(symbol);
        require(dirty.missingCount() == 1
            && dirty.mismatchCount() == 1
            && dirty.extraCount() == 1,
            "复合差异识别错误 / composite differences not detected");

        String repairKey = "crash-repair-" + runId;
        var repaired = reliability.repair(dirty.runId(), repairKey);
        var repeated = reliability.repair(dirty.runId(), repairKey);
        require(repaired.repairedCount() == 2
            && repaired.quarantinedCount() == 1
            && repeated.duplicate(),
            "修复结果或幂等性错误 / repair idempotency failed");

        var clean = reliability.reconcile(symbol);
        require("CLEAN".equals(clean.status()),
            "修复后未收敛 / reconciliation did not converge");
        require(reliability.activeProjectionCount(symbol) == MAKERS,
            "活动投影数量错误 / active projection count mismatch");

        return new RecoveryEvidence(
            dirty.runId(), dirty.missingCount(), dirty.mismatchCount(),
            dirty.extraCount(), repaired.repairId(),
            repaired.repairedCount(), repaired.quarantinedCount(),
            repeated.duplicate(), clean.runId(), clean.status());
    }

    /** 读取权威成交的紧凑校验快照。 */
    private TruthSnapshot truth(String symbol) {
        LabScenarioMapper.TruthRow row = labMapper.summarizeTruth(symbol);
        return new TruthSnapshot(
            row.tradeCount(), row.sequenceSum(), row.quantitySum(), row.quoteSum());
    }

    /** 比较恢复前后权威成交快照，防止修复越权修改事实。 */
    private boolean sameTruth(TruthSnapshot left, TruthSnapshot right) {
        return left != null && right != null
            && left.tradeCount() == right.tradeCount()
            && left.sequenceSum() == right.sequenceSum()
            && left.quantitySum().compareTo(right.quantitySum()) == 0
            && left.quoteSum().compareTo(right.quoteSum()) == 0;
    }

    /** 统计指定账户余额与不可变账本推导值不一致的数量。 */
    private long ledgerMismatchCount(UUID payer, UUID payee, UUID fee) {
        return labMapper.countLedgerMismatches(payer, payee, fee);
    }

    /** 返回实验采用的公开故障模型以及明确边界。 */
    private List<String> designBasis() {
        return List.of(
            "高波动与惊群：并发市价单、客户端集中重试和深度耗尽",
            "错误版本或旧节点：Lease 接管后用 Epoch Fencing 拒绝迟到写入",
            "接管链路失败：显式排空、过期、领取新 Epoch，并在数据面校验",
            "信息同步异常：乱序、重复、缺失、错值、幽灵数据与幂等修复",
            "边界：未模拟 DNS、真实网络分区、存储硬件故障或生产容量");
    }

    /** 将场景断言失败转换为明确异常，禁止继续生成成功报告。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 热点撮合阶段内部结果。 */
    private record TrafficOutcome(List<PlaceOrderCommand> commands,
                                  List<TradeView> trades,
                                  long elapsedMs,
                                  BigDecimal rate) {
    }

    /** 重复结算阶段内部统计。 */
    private record SettlementStorm(int effects, int duplicates) {
    }

    /** 权威成交的聚合校验快照。 */
    private record TruthSnapshot(long tradeCount, long sequenceSum,
                                 BigDecimal quantitySum, BigDecimal quoteSum) {
    }

    /** 场景时间线阶段。 */
    public record Phase(String relativeTime, String businessState,
                        String injectedFailure, String systemResponse,
                        String evidence) {
    }

    /** 场景关键业务与性能指标。 */
    public record CrashMetrics(int makerOrders, int concurrentMarketSellers,
                               long tradeCount, long uniqueTradeSequences,
                               long tradeOutboxEvents, int settlementCommands,
                               int duplicateSettlementDeliveries,
                               long ledgerTransactions,
                               int repairedProjections,
                               int quarantinedProjections,
                               long matchingElapsedMs,
                               BigDecimal observedTradesPerSecond,
                               long endToEndElapsedMs) {
    }

    /** Worker 接管与结算的可核验证据。 */
    public record SettlementEvidence(UUID payerId, UUID payeeId, UUID feeId,
                                     long oldEpoch, long newEpoch,
                                     boolean staleWorkerRejected,
                                     int financialEffects, int duplicateReturns,
                                     long ledgerTransactions, String symbol) {
    }

    /** 成交投影故障注入与恢复证据。 */
    public record RecoveryEvidence(UUID dirtyRunId, int missingCount,
                                   int mismatchCount, int extraCount,
                                   UUID repairId, int repairedCount,
                                   int quarantinedCount,
                                   boolean duplicateRepairDetected,
                                   UUID cleanRunId, String finalStatus) {
    }

    /** 市场暴跌日完整实验报告。 */
    public record MarketCrashReport(String scenario, String runId, String symbol,
                                    Instant startedAt, Instant completedAt,
                                    String finalStatus, List<String> designBasis,
                                    List<Phase> timeline, CrashMetrics metrics,
                                    RecoveryEvidence recovery,
                                    Map<String, String> checks,
                                    String boundary) {
    }
}
