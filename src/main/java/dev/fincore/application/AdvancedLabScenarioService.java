package dev.fincore.application;

import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeSyncCommand;
import dev.fincore.domain.TradeView;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Profile("lab")
@Service
public class AdvancedLabScenarioService {
    private final MatchingService matching;
    private final TradeReliabilityService reliability;
    private final JdbcTemplate jdbc;

    public AdvancedLabScenarioService(MatchingService matching,
                                      TradeReliabilityService reliability,
                                      JdbcTemplate jdbc) {
        this.matching = matching;
        this.reliability = reliability;
        this.jdbc = jdbc;
    }

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
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(takerCount, 16));
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MatchingResult>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发压测被中断 / burst interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("并发压测失败 / burst failed", e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

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

        jdbc.update("""
            UPDATE trade_projection
            SET quantity=quantity+1, updated_at=now()
            WHERE trade_id=? AND status='ACTIVE'
            """, trades.get(0).tradeId());
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

    private BurstReport verifyBurst(String runId, String symbol, int makers,
                                    int takers, int returnedTrades, long elapsedMs) {
        long storedTrades = count("""
            SELECT COUNT(*) FROM trade_execution WHERE symbol=?
            """, symbol);
        long distinctSequences = count("""
            SELECT COUNT(DISTINCT trade_sequence) FROM trade_execution WHERE symbol=?
            """, symbol);
        long brokenOrders = count("""
            SELECT COUNT(*) FROM matching_order
            WHERE symbol=? AND original_quantity<>executed_quantity+remaining_quantity
            """, symbol);
        long openOrders = count("""
            SELECT COUNT(*) FROM matching_order
            WHERE symbol=? AND status IN ('OPEN', 'PARTIALLY_FILLED')
            """, symbol);
        long tradeEvents = jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_event
            WHERE event_type='MATCHING_TRADE_EXECUTED'
              AND payload LIKE ?
            """, Long.class, "%\"symbol\":\"" + symbol + "\"%");

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

    private long count(String sql, String symbol) {
        Long value = jdbc.queryForObject(sql, Long.class, symbol);
        return value == null ? 0 : value;
    }

    public record BurstReport(String runId, String symbol, Instant completedAt,
                              int makerCount, int concurrentTakers, long tradeCount,
                              long elapsedMs, BigDecimal observedTradesPerSecond,
                              Map<String, String> checks) {}
    public record SyncRecoveryReport(String runId, String symbol, Instant completedAt,
                                     Map<String, String> checks, UUID missingRunId,
                                     UUID corruptedRunId, UUID finalCleanRunId) {}
}
