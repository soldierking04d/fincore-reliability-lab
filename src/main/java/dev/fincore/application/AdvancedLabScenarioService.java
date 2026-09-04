package dev.fincore.application;

import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeSyncCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.infrastructure.persistence.mapper.LabScenarioMapper;
import dev.fincore.infrastructure.concurrent.StripedTaskExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
     * 运行“普通下单积压 + 撤单风暴”确定性实验。
     *
     * <p>实验创建与生产相同的双有界 Lane：先阻塞唯一 Worker，再填满普通队列并验证后续新单被
     * 明确拒绝；撤单使用独立保留容量受理。放行 Worker 后，撤单应先于全部普通积压进入权威数据库
     * 事务。实验只使用独立交易对，不修改生产配置，结束时会关闭临时执行器。</p>
     *
     * @param ordinaryBacklog 普通命令积压量，范围为 8 至 512
     * @return 撤单准入、执行顺序与数量守恒报告
     */
    public CancellationStormReport runCancellationStorm(int ordinaryBacklog) {
        if (ordinaryBacklog < 8 || ordinaryBacklog > 512) {
            throw new IllegalArgumentException("普通积压量需为 8—512");
        }
        String runId = Long.toString(System.currentTimeMillis());
        String symbol = "CXL" + runId.substring(Math.max(0, runId.length() - 9)) + "-USDT";
        String userId = "cancel-owner-" + runId;
        var placed = matching.place(new PlaceOrderCommand(
            "cancel-storm-" + runId, userId, symbol, OrderSide.BUY, OrderType.LIMIT,
            new BigDecimal("99"), new BigDecimal("5")));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        StripedTaskExecutor lane = new StripedTaskExecutor(1, ordinaryBacklog, 16, registry);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicInteger completedOrdinary = new AtomicInteger();
        List<CompletableFuture<Integer>> backlog = new ArrayList<>();
        long startedAt = System.nanoTime();
        try {
            CompletableFuture<Boolean> blocker = lane.submit(symbol, () -> {
                workerStarted.countDown();
                return releaseWorker.await(5, TimeUnit.SECONDS);
            });
            require(workerStarted.await(1, TimeUnit.SECONDS), "无法建立确定性积压 / worker did not block");
            for (int index = 0; index < ordinaryBacklog; index++) {
                backlog.add(lane.submit(symbol, completedOrdinary::incrementAndGet));
            }

            boolean overflowRejected;
            try {
                lane.submit(symbol, completedOrdinary::incrementAndGet);
                overflowRejected = false;
            } catch (dev.fincore.infrastructure.concurrent.ConcurrencyRejectedException expected) {
                overflowRejected = true;
            }
            CompletableFuture<dev.fincore.domain.OrderView> cancellation = lane.submitPriority(
                symbol, () -> matching.cancel(placed.order().orderId(), userId));

            releaseWorker.countDown();
            require(blocker.get(2, TimeUnit.SECONDS), "阻塞任务未正常结束 / blocker did not finish");
            var canceled = cancellation.get(5, TimeUnit.SECONDS);
            int completedAtCancellation = completedOrdinary.get();
            CompletableFuture.allOf(backlog.toArray(CompletableFuture[]::new))
                .get(5, TimeUnit.SECONDS);

            boolean quantityInvariant = canceled.executedQuantity()
                .add(canceled.remainingQuantity())
                .compareTo(canceled.originalQuantity()) == 0;
            require(overflowRejected, "普通队列饱和后仍受理新单 / overflow order was accepted");
            require(canceled.status() == OrderStatus.CANCELED,
                "撤单未形成权威终态 / cancellation not finalized");
            require(completedAtCancellation == 0,
                "撤单没有越过普通积压 / cancellation did not overtake backlog");
            require(quantityInvariant, "订单数量不守恒 / order quantity invariant failed");

            Map<String, String> checks = new LinkedHashMap<>();
            checks.put("普通队列背压", "PASS：满载后新单返回 429 语义，不转移到无界队列");
            checks.put("撤单保留容量", "PASS：普通队列已满仍能受理撤单");
            checks.put("同交易对顺序", "PASS：不抢占进行中事务，随后优先于 " + ordinaryBacklog + " 笔积压");
            checks.put("前端成功语义", "PASS：数据库返回 CANCELED 终态后才报告成功");
            checks.put("订单数量守恒", "PASS：executed + remaining = original");
            long elapsedMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            return new CancellationStormReport(runId, symbol, Instant.now(), ordinaryBacklog,
                overflowRejected, canceled.status(), completedAtCancellation,
                completedOrdinary.get(), quantityInvariant, elapsedMs, checks);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("撤单风暴实验被中断 / cancellation storm interrupted", exception);
        } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("撤单风暴实验失败 / cancellation storm failed", exception);
        } finally {
            releaseWorker.countDown();
            lane.shutdown();
            registry.close();
        }
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

    /** 场景断言失败时立即终止，禁止生成伪成功报告。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
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

    /**
     * 撤单风暴实验报告。
     *
     * @param runId 实验编号
     * @param symbol 隔离交易对
     * @param completedAt 完成时间
     * @param ordinaryBacklog 普通命令积压量
     * @param overflowRejected 普通队列满载后是否拒绝新单
     * @param cancellationStatus 权威撤单终态
     * @param ordinaryCompletedAtCancellation 撤单完成时已完成的普通积压数
     * @param ordinaryCompleted 最终完成的普通积压数
     * @param quantityInvariant 订单数量是否守恒
     * @param elapsedMs 场景总耗时
     * @param checks 场景断言
     */
    public record CancellationStormReport(
        String runId, String symbol, Instant completedAt, int ordinaryBacklog,
        boolean overflowRejected, OrderStatus cancellationStatus,
        int ordinaryCompletedAtCancellation, int ordinaryCompleted,
        boolean quantityInvariant, long elapsedMs, Map<String, String> checks) {
    }
}
