package dev.fincore.application;

import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeSyncCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.infrastructure.persistence.mapper.LabScenarioMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 高级可靠性实验编排服务。
 *
 * <p>该服务仅在 {@code lab} Profile 启用，用真实数据库事务组合热点撮合与成交同步恢复场景。它负责
 * 造数和断言，不参与生产业务链路。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Profile("lab")
@Service
public class AdvancedLabScenarioService {
    /** 撮合服务。 */
    private final MatchingService matching;
    /** 成交同步、对账与修复服务。 */
    private final TradeReliabilityService reliability;
    /** 实验故障注入与数据库事实断言持久化接口。 */
    private final LabScenarioMapper labMapper;

    /**
     * 创建高级实验编排服务。
     *
     * @param matching 撮合服务
     * @param reliability 成交可靠性服务
     * @param labMapper 实验故障注入与事实断言接口
     */
    public AdvancedLabScenarioService(MatchingService matching,
                                      TradeReliabilityService reliability,
                                      LabScenarioMapper labMapper) {
        this.matching = matching;
        this.reliability = reliability;
        this.labMapper = labMapper;
    }

    /**
     * 运行同一交易对的并发撮合突发实验。
     *
     * @param makerCount 预置 Maker 数量，范围为 20 至 200
     * @param takerCount 并发 Taker 数量，范围为 1 至 32
     * @return 含吞吐量和守恒断言的实验报告
     */
    public BurstReport runMatchingBurst(int makerCount, int takerCount) {
        if (makerCount < 20 || makerCount > 200 || takerCount < 1
            || takerCount > 32 || makerCount % takerCount != 0) {
            throw new IllegalArgumentException(
                "Maker 数量需为 20—200、Taker 数量需为 1—32，且前者能被后者整除");
        }
        String runId = Long.toString(System.currentTimeMillis());
        String symbol = "HOT" + runId.substring(Math.max(0, runId.length() - 10)) + "-USDT";
        for (int i = 0; i < makerCount; i++) {
            matching.place(new PlaceOrderCommand(
                "burst-maker-" + runId + "-" + i,
                "burst-seller-" + runId + "-" + i,
                symbol, OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal("100"), BigDecimal.ONE));
        }

        int quantityPerTaker = makerCount / takerCount;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MatchingResult>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
            // 所有 Taker 等待同一个闩锁，尽量在同一时间进入撮合服务。
            for (int i = 0; i < takerCount; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return matching.place(new PlaceOrderCommand(
                        "burst-taker-" + runId + "-" + index,
                        "burst-buyer-" + runId + "-" + index,
                        symbol, OrderSide.BUY, OrderType.LIMIT,
                        new BigDecimal("100"), new BigDecimal(quantityPerTaker)));
                }));
            }
            start.countDown();
            int returnedTrades = 0;
            for (Future<MatchingResult> future : futures) {
                returnedTrades += future.get().trades().size();
            }
            long elapsedMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
            return verifyBurst(runId, symbol, makerCount, takerCount,
                returnedTrades, elapsedMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发压测被中断 / burst interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("并发压测失败 / burst failed", exception.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 运行乱序、重复、漏数、错值与幽灵成交的同步恢复实验。
     *
     * @return 包含每个故障断言和对账批次编号的恢复报告
     */
    public SyncRecoveryReport runTradeSyncRecovery() {
        String runId = Long.toString(System.currentTimeMillis());
        String symbol = "SYNC" + runId.substring(Math.max(0, runId.length() - 9)) + "-USDT";
        for (int i = 0; i < 3; i++) {
            matching.place(new PlaceOrderCommand(
                "sync-maker-" + runId + "-" + i,
                "sync-seller-" + runId + "-" + i,
                symbol, OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal(100 + i), BigDecimal.ONE));
        }
        MatchingResult taker = matching.place(new PlaceOrderCommand(
            "sync-taker-" + runId, "sync-buyer-" + runId,
            symbol, OrderSide.BUY, OrderType.LIMIT,
            new BigDecimal("102"), new BigDecimal("3")));
        List<TradeView> trades = taker.trades();
        if (trades.size() != 3) {
            throw new IllegalStateException("基础成交数量错误 / unexpected trade count");
        }

        UUID eventThird = UUID.randomUUID();
        UUID eventFirst = UUID.randomUUID();
        // 故意先同步第三笔，再同步第一笔，证明投影不依赖消息到达顺序。
        reliability.apply(TradeSyncCommand.from(eventThird, trades.get(2)));
        reliability.apply(TradeSyncCommand.from(eventFirst, trades.get(0)));
        var duplicate = reliability.apply(TradeSyncCommand.from(eventThird, trades.get(2)));
        if (!duplicate.duplicateEvent()) {
            throw new IllegalStateException("重复事件未被识别 / duplicate event not detected");
        }

        var missingRun = reliability.reconcile(symbol);
        if (missingRun.missingCount() != 1 || missingRun.mismatchCount() != 0
            || missingRun.extraCount() != 0) {
            throw new IllegalStateException("漏同步识别结果错误 / missing-event detection failed");
        }
        String missingRepairKey = "repair-missing-" + runId;
        var missingRepair = reliability.repair(missingRun.runId(), missingRepairKey);
        var repeatedRepair = reliability.repair(missingRun.runId(), missingRepairKey);
        if (missingRepair.repairedCount() != 1 || !repeatedRepair.duplicate()) {
            throw new IllegalStateException("漏同步修复幂等失败 / repair idempotency failed");
        }
        var firstClean = reliability.reconcile(symbol);
        if (!"CLEAN".equals(firstClean.status())) {
            throw new IllegalStateException("漏同步修复后仍有差异 / repair did not converge");
        }

        // 注入错值投影；权威 trade_execution 保持不可变。
        labMapper.injectProjectionMismatch(trades.get(0).tradeId());
        long extraSequence = trades.stream().mapToLong(TradeView::sequence).max().orElseThrow() + 10_000;
        TradeSyncCommand ghost = new TradeSyncCommand(
            UUID.randomUUID(), UUID.randomUUID(), symbol,
            trades.get(0).makerOrderId(), trades.get(0).takerOrderId(),
            new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("100"),
            extraSequence);
        reliability.apply(ghost);

        var corruptedRun = reliability.reconcile(symbol);
        if (corruptedRun.mismatchCount() != 1 || corruptedRun.extraCount() != 1) {
            throw new IllegalStateException(
                "错值或幽灵成交识别失败 / mismatch or extra detection failed");
        }
        var corruptedRepair = reliability.repair(
            corruptedRun.runId(), "repair-corrupted-" + runId);
        if (corruptedRepair.repairedCount() != 1
            || corruptedRepair.quarantinedCount() != 1) {
            throw new IllegalStateException("错值修复或隔离失败 / repair or quarantine failed");
        }
        var finalRun = reliability.reconcile(symbol);
        if (!"CLEAN".equals(finalRun.status())) {
            throw new IllegalStateException("最终对账未收敛 / final reconciliation is not clean");
        }

        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("乱序成交事件", "PASS：不依赖到达顺序");
        checks.put("重复成交事件", "PASS：相同事件号只处理一次");
        checks.put("漏同步发现", "PASS：识别 1 条缺失投影");
        checks.put("修复重试", "PASS：同一幂等键只执行一次");
        checks.put("错值成交发现", "PASS：不可变字段差异被识别");
        checks.put("幽灵成交隔离", "PASS：额外投影被隔离而非删除事实");
        checks.put("修复后再对账", "PASS：最终 CLEAN");
        return new SyncRecoveryReport(runId, symbol, Instant.now(), checks,
            missingRun.runId(), corruptedRun.runId(), finalRun.runId());
    }

    /**
     * 从持久化事实验证热点撮合的数量守恒、序号唯一和事件完整性。
     */
    private BurstReport verifyBurst(String runId, String symbol, int makers,
                                    int takers, int returnedTrades, long elapsedMs) {
        long storedTrades = labMapper.countTrades(symbol);
        long distinctSequences = labMapper.countDistinctTradeSequences(symbol);
        long brokenOrders = labMapper.countBrokenOrders(symbol);
        long openOrders = labMapper.countOpenOrders(symbol);
        long tradeEvents = labMapper.countTradeOutboxEvents(
            "%\"symbol\":\"" + symbol + "\"%");

        if (returnedTrades != makers || storedTrades != makers
            || distinctSequences != makers || brokenOrders != 0
            || openOrders != 0 || tradeEvents != makers) {
            throw new IllegalStateException("热点流量守恒校验失败 / burst invariant failed");
        }
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("同交易对并发 Taker", "PASS：" + takers + " 路并发");
        checks.put("成交唯一性", "PASS：" + storedTrades + " 条成交");
        checks.put("成交序列唯一", "PASS：" + distinctSequences + " 个唯一序列");
        checks.put("订单数量守恒", "PASS：0 条异常订单");
        checks.put("成交事件完整", "PASS：" + tradeEvents + " 条 Outbox");
        BigDecimal observedRate = BigDecimal.valueOf(storedTrades)
            .multiply(BigDecimal.valueOf(1000))
            .divide(BigDecimal.valueOf(elapsedMs), 2, java.math.RoundingMode.HALF_UP);
        return new BurstReport(runId, symbol, Instant.now(), makers, takers,
            storedTrades, elapsedMs, observedRate, checks);
    }

    /**
     * 热点撮合实验报告。
     *
     * @param runId 实验编号
     * @param symbol 独立交易对
     * @param completedAt 完成时间
     * @param makerCount Maker 数量
     * @param concurrentTakers 并发 Taker 数量
     * @param tradeCount 成交数量
     * @param elapsedMs 耗时毫秒数
     * @param observedTradesPerSecond 实测每秒成交数
     * @param checks 守恒检查结果
     */
    public record BurstReport(String runId, String symbol, Instant completedAt,
                              int makerCount, int concurrentTakers, long tradeCount,
                              long elapsedMs, BigDecimal observedTradesPerSecond,
                              Map<String, String> checks) {
    }

    /**
     * 成交同步恢复实验报告。
     *
     * @param runId 实验编号
     * @param symbol 独立交易对
     * @param completedAt 完成时间
     * @param checks 各故障注入的检查结果
     * @param missingRunId 漏数对账批次
     * @param corruptedRunId 错值与幽灵数据对账批次
     * @param finalCleanRunId 修复后最终对账批次
     */
    public record SyncRecoveryReport(String runId, String symbol, Instant completedAt,
                                     Map<String, String> checks, UUID missingRunId,
                                     UUID corruptedRunId, UUID finalCleanRunId) {
    }
}
