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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 市场暴跌日复合实验 / Composite market-crash-day experiment.
 *
 * <p>只复现公开事故中的故障类型，不使用任何公司的源码、流量或内部参数。
 * It reproduces public failure patterns, never private implementation details.</p>
 */
@Profile("lab")
@Service
public class MarketCrashScenarioService {
    private static final int MAKERS = 60;
    private static final int CONCURRENT_SELLERS = 12;
    private static final int SETTLEMENTS = 6;
    private static final int DUPLICATE_DELIVERIES = 17;

    private final MatchingService matching;
    private final TradeReliabilityService reliability;
    private final AccountService accounts;
    private final SettlementService settlements;
    private final ShardLeaseService leases;
    private final JdbcTemplate jdbc;

    public MarketCrashScenarioService(MatchingService matching,
                                      TradeReliabilityService reliability,
                                      AccountService accounts,
                                      SettlementService settlements,
                                      ShardLeaseService leases,
                                      JdbcTemplate jdbc) {
        this.matching = matching;
        this.reliability = reliability;
        this.accounts = accounts;
        this.settlements = settlements;
        this.leases = leases;
        this.jdbc = jdbc;
    }

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
        long beforeReplay = count(
            "SELECT COUNT(*) FROM trade_execution WHERE symbol=?", symbol);
        MatchingResult replay = matching.place(firstCommand);
        require(replay.order().duplicate() && replay.trades().size() == 5,
            "相同客户端订单重试未幂等返回 / retry was not idempotent");
        require(count("SELECT COUNT(*) FROM trade_execution WHERE symbol=?", symbol)
            == beforeReplay, "重试生成了重复成交 / retry duplicated trades");

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
            count("SELECT COUNT(DISTINCT trade_sequence) FROM trade_execution WHERE symbol=?",
                symbol),
            count("""
                SELECT COUNT(*) FROM outbox_event
                WHERE event_type='MATCHING_TRADE_EXECUTED' AND payload LIKE ?
                """, "%\"symbol\":\"" + symbol + "\"%"),
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

    private TrafficOutcome runSellOff(String runId, String symbol) {
        List<PlaceOrderCommand> commands = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_SELLERS; i++) {
            commands.add(new PlaceOrderCommand(
                "crash-taker-" + runId + "-" + i,
                "panic-seller-" + runId + "-" + i,
                symbol, OrderSide.SELL, OrderType.MARKET,
                null, new BigDecimal("5")));
        }
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_SELLERS);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("暴跌流量实验被中断 / interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("暴跌流量实验失败 / failed",
                e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

    private void verifyMatching(String symbol, TrafficOutcome traffic) {
        require(traffic.trades().size() == MAKERS,
            "成交数量错误 / unexpected trade count");
        require(count("SELECT COUNT(*) FROM trade_execution WHERE symbol=?", symbol)
            == MAKERS, "成交落库数量错误 / stored trade count mismatch");
        require(count("""
            SELECT COUNT(DISTINCT trade_sequence)
            FROM trade_execution WHERE symbol=?
            """, symbol) == MAKERS,
            "成交序列不唯一 / duplicate trade sequence");
        require(count("""
            SELECT COUNT(*) FROM matching_order
            WHERE symbol=? AND original_quantity
                <> executed_quantity + remaining_quantity
            """, symbol) == 0,
            "订单数量不守恒 / order quantity not conserved");
        require(count("""
            SELECT COUNT(*) FROM outbox_event
            WHERE event_type='MATCHING_TRADE_EXECUTED' AND payload LIKE ?
            """, "%\"symbol\":\"" + symbol + "\"%") == MAKERS,
            "成交 Outbox 不完整 / trade outbox incomplete");
    }

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
        jdbc.update("""
            UPDATE shard_lease SET lease_until=now() - interval '1 second'
            WHERE shard_id=?
            """, shardId);
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
        long ledgerTransactions = count("""
            SELECT COUNT(*) FROM ledger_transaction
            WHERE business_key LIKE ?
            """, "crash-settle-" + runId + "-%");
        require(ledgerTransactions == SETTLEMENTS,
            "账本交易数量错误 / ledger transaction count mismatch");
        require(count("""
            SELECT COUNT(*) FROM settlement_order
            WHERE business_key LIKE ? AND status='SUCCESS'
            """, "crash-settle-" + runId + "-%") == SETTLEMENTS,
            "结算终态数量错误 / settlement terminal count mismatch");

        return new SettlementEvidence(payer.accountId(), payee.accountId(),
            fee.accountId(), oldLease.epoch(), newLease.epoch(),
            staleRejected, storm.effects(), storm.duplicates(),
            ledgerTransactions, symbol);
    }

    private SettlementCommand settlementCommand(
        String runId, int index, TradeView trade,
        UUID payerId, UUID payeeId, UUID feeId) {
        return new SettlementCommand(
            "crash-msg-" + runId + "-" + index,
            "crash-settle-" + runId + "-" + index,
            payerId, payeeId, feeId, "USDT",
            trade.quoteAmount(), new BigDecimal("0.01"));
    }

    private SettlementStorm runSettlementStorm(
        SettlementCommand command, FenceToken fence) {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<SettlementOutcome>> tasks = new ArrayList<>();
            for (int i = 0; i < DUPLICATE_DELIVERIES; i++) {
                tasks.add(() -> settlements.settle(command, fence));
            }
            List<Future<SettlementOutcome>> futures = pool.invokeAll(tasks);
            int effects = 0;
            int duplicates = 0;
            for (Future<SettlementOutcome> future : futures) {
                if (future.get().duplicate()) duplicates++;
                else effects++;
            }
            return new SettlementStorm(effects, duplicates);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("结算重投实验被中断 / interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("结算重投实验失败 / failed",
                e.getCause());
        } finally {
            pool.shutdownNow();
        }
    }

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
            if (trade.tradeId().equals(missing.tradeId())) continue;
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

        jdbc.update("""
            UPDATE trade_projection
            SET quantity=quantity+1, updated_at=now()
            WHERE trade_id=? AND status='ACTIVE'
            """, mismatch.tradeId());

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

    private TruthSnapshot truth(String symbol) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS trade_count,
                   COALESCE(SUM(trade_sequence), 0) AS sequence_sum,
                   COALESCE(SUM(quantity), 0) AS quantity_sum,
                   COALESCE(SUM(quote_amount), 0) AS quote_sum
            FROM trade_execution WHERE symbol=?
            """, (rs, row) -> new TruthSnapshot(
                rs.getLong("trade_count"),
                rs.getLong("sequence_sum"),
                rs.getBigDecimal("quantity_sum"),
                rs.getBigDecimal("quote_sum")), symbol);
    }

    private boolean sameTruth(TruthSnapshot left, TruthSnapshot right) {
        return left != null && right != null
            && left.tradeCount() == right.tradeCount()
            && left.sequenceSum() == right.sequenceSum()
            && left.quantitySum().compareTo(right.quantitySum()) == 0
            && left.quoteSum().compareTo(right.quoteSum()) == 0;
    }

    private long ledgerMismatchCount(UUID payer, UUID payee, UUID fee) {
        return count("""
            SELECT COUNT(*) FROM (
                SELECT a.account_id
                FROM account a
                LEFT JOIN ledger_entry e ON e.account_id=a.account_id
                WHERE a.account_id IN (?, ?, ?)
                GROUP BY a.account_id, a.opening_balance, a.balance
                HAVING a.balance <> a.opening_balance + COALESCE(SUM(
                    CASE WHEN e.direction='CREDIT'
                         THEN e.amount ELSE -e.amount END), 0)
            ) mismatches
            """, payer, payee, fee);
    }

    private List<String> designBasis() {
        return List.of(
            "高波动与惊群：并发市价单、客户端集中重试和深度耗尽",
            "错误版本或旧节点：Lease 接管后用 Epoch Fencing 拒绝迟到写入",
            "接管链路失败：显式排空、过期、领取新 Epoch，并在数据面校验",
            "信息同步异常：乱序、重复、缺失、错值、幽灵数据与幂等修复",
            "边界：未模拟 DNS、真实网络分区、存储硬件故障或生产容量");
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record TrafficOutcome(List<PlaceOrderCommand> commands,
                                  List<TradeView> trades,
                                  long elapsedMs,
                                  BigDecimal rate) {}
    private record SettlementStorm(int effects, int duplicates) {}
    private record TruthSnapshot(long tradeCount, long sequenceSum,
                                 BigDecimal quantitySum, BigDecimal quoteSum) {}

    public record Phase(String relativeTime, String businessState,
                        String injectedFailure, String systemResponse,
                        String evidence) {}

    public record CrashMetrics(int makerOrders, int concurrentMarketSellers,
                               long tradeCount, long uniqueTradeSequences,
                               long tradeOutboxEvents, int settlementCommands,
                               int duplicateSettlementDeliveries,
                               long ledgerTransactions,
                               int repairedProjections,
                               int quarantinedProjections,
                               long matchingElapsedMs,
                               BigDecimal observedTradesPerSecond,
                               long endToEndElapsedMs) {}

    public record SettlementEvidence(UUID payerId, UUID payeeId, UUID feeId,
                                     long oldEpoch, long newEpoch,
                                     boolean staleWorkerRejected,
                                     int financialEffects, int duplicateReturns,
                                     long ledgerTransactions, String symbol) {}

    public record RecoveryEvidence(UUID dirtyRunId, int missingCount,
                                   int mismatchCount, int extraCount,
                                   UUID repairId, int repairedCount,
                                   int quarantinedCount,
                                   boolean duplicateRepairDetected,
                                   UUID cleanRunId, String finalStatus) {}

    public record MarketCrashReport(String scenario, String runId, String symbol,
                                    Instant startedAt, Instant completedAt,
                                    String finalStatus, List<String> designBasis,
                                    List<Phase> timeline, CrashMetrics metrics,
                                    RecoveryEvidence recovery,
                                    Map<String, String> checks,
                                    String boundary) {}
}
