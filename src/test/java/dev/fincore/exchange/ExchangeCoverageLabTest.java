package dev.fincore.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.ExchangeCoverageScenarioService;
import dev.fincore.exchange.DerivativeRiskEngine.AdlCandidate;
import dev.fincore.exchange.DerivativeRiskEngine.MarginMode;
import dev.fincore.exchange.DerivativeRiskEngine.MarginTier;
import dev.fincore.exchange.DerivativeRiskEngine.Position;
import dev.fincore.exchange.DerivativeRiskEngine.RiskStatus;
import dev.fincore.exchange.DigitalAssetWorkflow.DepositStatus;
import dev.fincore.exchange.DigitalAssetWorkflow.WithdrawalStatus;
import dev.fincore.exchange.FeeEngine.FeeTier;
import dev.fincore.exchange.FeeEngine.LiquidityRole;
import dev.fincore.exchange.FixOmsReconciler.ExecutionReport;
import dev.fincore.exchange.FixOmsReconciler.OrderState;
import dev.fincore.exchange.FixOmsReconciler.ReportSource;
import dev.fincore.exchange.FixOmsReconciler.SequenceOutcome;
import dev.fincore.exchange.MarketDataReliability.DeltaOutcome;
import dev.fincore.exchange.MarketDataReliability.LatestValueBuffer;
import dev.fincore.exchange.MarketDataReliability.SourceHealth;
import dev.fincore.exchange.MarketDataReliability.SourceQuote;
import dev.fincore.exchange.MarketSurveillanceEngine.OrderAction;
import dev.fincore.exchange.MarketSurveillanceEngine.OrderEvent;
import dev.fincore.exchange.MarketSurveillanceEngine.Side;
import dev.fincore.exchange.MarketSurveillanceEngine.SignalType;
import dev.fincore.exchange.MarketSurveillanceEngine.TradeEvent;
import dev.fincore.exchange.MatchingRecoveryLog.Command;
import dev.fincore.exchange.MatchingRecoveryLog.CommandType;
import dev.fincore.exchange.OrderPolicyEngine.InstrumentConfig;
import dev.fincore.exchange.OrderPolicyEngine.Kind;
import dev.fincore.exchange.OrderPolicyEngine.OrderRequest;
import dev.fincore.exchange.OrderPolicyEngine.SelfTradePolicy;
import dev.fincore.exchange.OrderPolicyEngine.TimeInForce;
import dev.fincore.exchange.OrderPolicyEngine.TradingMode;
import dev.fincore.exchange.TradingApiSecurity.Decision;
import dev.fincore.exchange.TradingApiSecurity.Scope;
import dev.fincore.exchange.TradingApiSecurity.SignedRequest;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 交易所面试高频能力补全实验的纯领域自动测试。
 *
 * <p>测试覆盖序列缺口、FIX重放、订单语义、市场监察、API防重放、手续费、撮合恢复、
 * 合约风险和数字资产状态机。全部数据均为虚构实验数据。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
class ExchangeCoverageLabTest {
    /** 分层挂撤单信号需要命中的不同价格层数量。 */
    private static final int LAYERING_LEVELS = 3;

    /** 一键实验必须汇总九个能力域，并且每项都有指标、断言和诚实边界。 */
    @Test
    void aggregateScenarioReturnsNinePassingEvidenceDomains() {
        var report = new ExchangeCoverageScenarioService().run();
        assertEquals("PASS", report.status());
        assertEquals(9, report.domainCount());
        assertEquals(9, report.domains().size());
        assertTrue(report.domains().values().stream().allMatch(domain ->
            "PASS".equals(domain.status()) && !domain.metrics().isEmpty()
                && !domain.checks().isEmpty() && !domain.boundary().isBlank()));
    }

    /** 行情缺口必须经过快照和连续增量恢复，不能直接清除过期状态。 */
    @Test
    void marketDataGapRequiresSnapshotRecovery() {
        MarketDataReliability market = new MarketDataReliability();
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        assertEquals(DeltaOutcome.APPLIED, market.onDelta(
            quote("A", 1, 0, "99", "101", time)));
        assertEquals(DeltaOutcome.GAP, market.onDelta(
            quote("A", 3, 2, "100", "102", time.plusMillis(2))));
        assertEquals(SourceHealth.STALE, market.health("A"));

        assertEquals(SourceHealth.HEALTHY, market.recover(
            quote("A", 2, 0, "99.5", "100.5", time.plusMillis(1)),
            List.of(quote("A", 3, 2, "100", "102", time.plusMillis(2)))));
        assertEquals(new BigDecimal("101.00000000"), market.selectReference(
            time.plusSeconds(1), Duration.ofSeconds(5), new BigDecimal("100")).mid());
    }

    /** 多源参考价剔除异常值，并避免拼接出不存在的倒挂盘口。 */
    @Test
    void marketDataSelectsOneHealthySourceAndRejectsOutlier() {
        MarketDataReliability market = new MarketDataReliability();
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        market.onDelta(quote("A", 1, 0, "99", "101", time));
        market.onDelta(quote("B", 1, 0, "99.1", "101.1", time.plusMillis(1)));
        market.onDelta(quote("C", 1, 0, "999", "1001", time.plusMillis(2)));

        var selected = market.selectReference(time.plusSeconds(1),
            Duration.ofSeconds(5), new BigDecimal("100"));
        assertEquals("B", selected.source());
        assertEquals(2, selected.acceptedSources());
        assertTrue(selected.bid().compareTo(selected.ask()) < 0);
    }

    /** 慢消费者只合并可覆盖的行情，容量不足时明确记录丢弃。 */
    @Test
    void latestValueBufferIsBoundedAndCoalescesBySymbol() {
        LatestValueBuffer buffer = new LatestValueBuffer(1);
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        var first = new MarketDataReliability.ConsolidatedQuote("A", n("99"), n("101"),
            n("100"), 1, time, 2);
        var second = new MarketDataReliability.ConsolidatedQuote("A", n("100"), n("102"),
            n("101"), 2, time.plusMillis(1), 2);
        assertTrue(buffer.offer("BTC-USDT", first));
        assertTrue(buffer.offer("BTC-USDT", second));
        assertFalse(buffer.offer("ETH-USDT", first));
        assertEquals(1L, buffer.coalesced());
        assertEquals(1L, buffer.dropped());
    }

    /** IOC只成交现有深度，FOK在深度不足时完全拒绝。 */
    @Test
    void orderTimeInForceHasExplicitRemainderSemantics() {
        OrderPolicyEngine engine = new OrderPolicyEngine();
        InstrumentConfig instrument = instrument(TradingMode.FULL_TRADING);
        Instant now = Instant.parse("2026-09-04T00:00:00Z");
        var ioc = engine.plan(order("ioc", TimeInForce.IOC, false), instrument,
            n("100"), n("0.4"), now);
        assertEquals(n("0.4"), ioc.executableQuantity());
        assertEquals(n("0.6"), ioc.canceledQuantity());
        assertEquals(BigDecimal.ZERO, ioc.restingQuantity());

        var fok = engine.plan(order("fok", TimeInForce.FOK, false), instrument,
            n("100"), n("0.4"), now);
        assertEquals("REJECTED", fok.status());
        assertEquals("FOK_DEPTH_INSUFFICIENT", fok.rejectionReason());
    }

    /** Post-only、交易模式和精度配置必须在进入撮合前决定。 */
    @Test
    void productModeAndPrecisionFailClosed() {
        OrderPolicyEngine engine = new OrderPolicyEngine();
        Instant now = Instant.parse("2026-09-04T00:00:00Z");
        var postOnly = engine.plan(order("post", TimeInForce.GTC, true),
            instrument(TradingMode.FULL_TRADING), n("100"), n("1"), now);
        assertEquals("POST_ONLY_WOULD_TAKE", postOnly.rejectionReason());

        var cancelOnly = engine.plan(order("blocked", TimeInForce.GTC, false),
            instrument(TradingMode.CANCEL_ONLY), n("100"), BigDecimal.ZERO, now);
        assertEquals("TRADING_MODE_REJECTED", cancelOnly.rejectionReason());
    }

    /** 自成交保护策略不产生任何成交量，撤改单保留两个独立结果。 */
    @Test
    void selfTradeAndCancelReplaceDoNotCollapseBusinessResults() {
        OrderPolicyEngine engine = new OrderPolicyEngine();
        var stp = engine.preventSelfTrade(n("2"), n("1"),
            SelfTradePolicy.DECREMENT_AND_CANCEL);
        assertEquals(n("1"), stp.makerRemaining());
        assertEquals(BigDecimal.ZERO, stp.takerRemaining());
        var partial = engine.cancelReplace(false, true);
        assertEquals("NEW_ACCEPTED_CANCEL_FAILED", partial.status());
        assertTrue(partial.replacementAccepted());
        assertFalse(partial.cancelSucceeded());
    }

    /** FIX序号缺口、合法补发和无标记倒退必须产生不同结果。 */
    @Test
    void fixSequenceSeparatesGapFromPossibleDuplicate() {
        FixOmsReconciler oms = new FixOmsReconciler();
        assertEquals(SequenceOutcome.ACCEPTED, oms.acceptSequence(1, false));
        assertEquals(SequenceOutcome.GAP_DETECTED, oms.acceptSequence(3, false));
        assertEquals(SequenceOutcome.ACCEPTED, oms.acceptSequence(2, false));
        assertEquals(SequenceOutcome.POSSIBLE_DUPLICATE_IGNORED,
            oms.acceptSequence(1, true));
        assertEquals(SequenceOutcome.INVALID_REPLAY, oms.acceptSequence(2, false));
    }

    /** 超时订单由Drop Copy权威回报收敛，重复执行编号不重复累计成交。 */
    @Test
    void unknownOrderConvergesFromDropCopyExactlyOnce() {
        FixOmsReconciler oms = new FixOmsReconciler();
        oms.submit("client-1", n("1"));
        assertEquals(OrderState.UNKNOWN, oms.markUnknown("client-1").state());
        ExecutionReport fill = report("exec-1", "client-1", OrderState.FILLED,
            n("1"), BigDecimal.ZERO, ReportSource.DROP_COPY);
        assertFalse(oms.apply(fill).duplicateExecution());
        assertTrue(oms.apply(fill).duplicateExecution());
        assertEquals(OrderState.FILLED, oms.order("client-1").state());
    }

    /** 掉线撤单只是请求，必须等执行报告后才能展示已撤销。 */
    @Test
    void cancelOnDisconnectWaitsForAuthority() {
        FixOmsReconciler oms = new FixOmsReconciler();
        oms.submit("client-2", n("2"));
        assertEquals(List.of("client-2"), oms.onDisconnect(true));
        assertEquals(OrderState.PENDING_CANCEL, oms.order("client-2").state());
        assertEquals(Map.of("client-2", "MISSING_AUTHORITY"), oms.differences(List.of()));
    }

    /** 监察引擎生成线索，但不对用户资金执行自动处罚。 */
    @Test
    void surveillanceFindsWashRapidCancelLayeringAndSlippage() {
        MarketSurveillanceEngine surveillance = new MarketSurveillanceEngine();
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        for (int index = 0; index < LAYERING_LEVELS; index++) {
            String orderId = "order-" + index;
            BigDecimal price = n(Integer.toString(100 + index));
            surveillance.onOrder(new OrderEvent(orderId, "owner-A", Side.SELL,
                price, n("10"), OrderAction.NEW, time), n("5"), Duration.ofSeconds(1));
            surveillance.onOrder(new OrderEvent(orderId, "owner-A", Side.SELL,
                price, n("10"), OrderAction.CANCEL, time.plusMillis(100)),
                n("5"), Duration.ofSeconds(1));
        }
        surveillance.closeWindow(3, n("0.8"));
        surveillance.onTrade(new TradeEvent("trade-1", "owner-A", "owner-A",
            n("101"), n("1"), time));
        surveillance.checkExecution(Side.BUY, n("100"), n("102"),
            n("100"), "owner-A", "trade-2");

        var summary = surveillance.summary();
        assertEquals(3L, summary.get(SignalType.RAPID_LARGE_CANCEL));
        assertEquals(1L, summary.get(SignalType.LAYERING_PATTERN));
        assertEquals(1L, summary.get(SignalType.HIGH_CANCEL_RATIO));
        assertEquals(1L, summary.get(SignalType.WASH_TRADE));
        assertEquals(1L, summary.get(SignalType.EXECUTION_SLIPPAGE));
    }

    /** 小数位不同但数值相同的价格不能被误判为多个挂单层级。 */
    @Test
    void surveillanceNormalizesPriceLevelsBeforeLayeringDetection() {
        MarketSurveillanceEngine surveillance = new MarketSurveillanceEngine();
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        List<String> prices = List.of("100", "100.0", "100.00");
        for (int index = 0; index < prices.size(); index++) {
            String orderId = "same-level-" + index;
            BigDecimal price = n(prices.get(index));
            surveillance.onOrder(new OrderEvent(orderId, "owner-A", Side.SELL,
                price, n("10"), OrderAction.NEW, time), n("5"), Duration.ofSeconds(1));
            surveillance.onOrder(new OrderEvent(orderId, "owner-A", Side.SELL,
                price, n("10"), OrderAction.CANCEL, time.plusMillis(100)),
                n("5"), Duration.ofSeconds(1));
        }
        assertFalse(surveillance.summary().containsKey(SignalType.LAYERING_PATTERN));
    }

    /** API签名、权限、IP、时间、随机数和限频形成一条失败关闭链。 */
    @Test
    void tradingApiRejectsReplayAndRateOverflow() {
        TradingApiSecurity security = new TradingApiSecurity(Duration.ofSeconds(5),
            2, Duration.ofMinutes(1));
        byte[] secret = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);
        security.register("key-1", secret, Set.of(Scope.TRADE), Set.of("203.0.113.10"));
        Instant now = Instant.parse("2026-09-04T00:00:00Z");
        SignedRequest first = signed(secret, "nonce-1", now);
        assertEquals(Decision.ALLOWED, security.verify(first, now));
        assertEquals(Decision.REPLAYED_NONCE, security.verify(first, now));
        assertEquals(Decision.ALLOWED, security.verify(signed(secret, "nonce-2", now), now));
        assertEquals(Decision.RATE_LIMITED,
            security.verify(signed(secret, "nonce-3", now), now));
        assertEquals(Decision.TIMESTAMP_EXPIRED,
            security.verify(signed(secret, "old", now.minusSeconds(6)), now));
    }

    /** 固定窗口小于一秒会导致除零，配置阶段必须直接拒绝。 */
    @Test
    void tradingApiRejectsSubSecondFixedWindow() {
        assertThrows(IllegalArgumentException.class,
            () -> new TradingApiSecurity(Duration.ofSeconds(5), 2,
                Duration.ofMillis(500)));
    }

    /** Maker返佣与Taker收费均按一次舍入金额形成严格平衡双腿。 */
    @Test
    void feeCalculationAndReversalRemainBalanced() {
        FeeEngine fees = new FeeEngine(8, List.of(
            new FeeTier("VIP0", BigDecimal.ZERO, n("1.0"), n("2.0")),
            new FeeTier("VIP1", n("1000000"), n("-0.2"), n("1.5"))));
        var rebate = fees.calculate("trade-1", n("60000"), n("2000000"),
            LiquidityRole.MAKER);
        assertEquals(n("-1.20000000"), rebate.fee());
        assertTrue(rebate.balanced());
        var reversal = fees.reverse(rebate, "bust-1");
        assertTrue(reversal.balanced());
        assertEquals(rebate.userDelta().negate(), reversal.userDelta());
    }

    /** 快照后的命令可以重放到同一摘要，旧Epoch不能继续写入。 */
    @Test
    void matchingSnapshotReplayIsDeterministicAndFenced() {
        MatchingRecoveryLog log = new MatchingRecoveryLog();
        log.append("cmd-1", 1L, CommandType.PLACE, "order-1", n("2"));
        var snapshot = log.snapshot();
        log.append("cmd-2", 1L, CommandType.CANCEL, "order-1", BigDecimal.ZERO);
        long newEpoch = log.takeover();
        assertEquals(2L, newEpoch);
        assertEquals("STALE_EPOCH", log.append("cmd-old", 1L,
            CommandType.PLACE, "order-2", n("1")).status());

        var recovered = MatchingRecoveryLog.recover(snapshot, log.journal());
        assertEquals(log.checksum(), recovered.checksum());
        assertEquals(BigDecimal.ZERO, recovered.remainingByOrder().get("order-1"));
    }

    /** 重放日志出现序号缺口时必须停止恢复。 */
    @Test
    void matchingReplayStopsAtSequenceGap() {
        MatchingRecoveryLog log = new MatchingRecoveryLog();
        log.append("cmd-1", 1L, CommandType.PLACE, "order-1", n("2"));
        var snapshot = log.snapshot();
        Command sequenceThree = new Command(3L, 1L, "cmd-3", CommandType.PLACE,
            "order-3", n("1"));
        assertThrows(IllegalStateException.class,
            () -> MatchingRecoveryLog.recover(snapshot, List.of(sequenceThree)));
    }

    /** 全仓和逐仓风险域不会互相混淆，阶梯保证金按名义金额选择。 */
    @Test
    void derivativeRiskSeparatesCrossAndIsolatedBuckets() {
        DerivativeRiskEngine risk = derivativeRisk();
        Position losing = new Position("BTC-USDT", n("1"), n("60000"), n("59000"));
        var cross = risk.assess(n("2000"), List.of(losing), MarginMode.CROSS, Map.of());
        assertEquals(RiskStatus.SAFE, cross.buckets().get("CROSS").status());

        var isolated = risk.assess(BigDecimal.ZERO, List.of(losing), MarginMode.ISOLATED,
            Map.of("BTC-USDT", n("1100")));
        assertEquals(RiskStatus.LIQUIDATION_REQUIRED,
            isolated.buckets().get("BTC-USDT").status());
        assertEquals(n("5900.00000000"), risk.initialMargin(n("1"), n("59000")));
    }

    /** 资金费方向、部分强平和保险基金/ADL损失瀑布可以独立复算。 */
    @Test
    void derivativeFundingAndLiquidationWaterfallAreDeterministic() {
        DerivativeRiskEngine risk = derivativeRisk();
        Position longPosition = new Position("BTC-USDT", n("1"), n("60000"), n("60000"));
        assertEquals(n("-6.00000000"), risk.fundingCashDelta(longPosition, n("0.0001")));
        assertEquals(n("-0.70"), risk.partialLiquidation(n("1"), n("0.75"), n("0.1")));
        var waterfall = risk.coverLoss(n("100"), n("60"), List.of(
            new AdlCandidate("B", n("2"), n("15")),
            new AdlCandidate("A", n("3"), n("30"))));
        assertEquals(n("60"), waterfall.insuranceUsed());
        assertEquals(n("30"), waterfall.adlAllocations().get("A"));
        assertEquals(n("10"), waterfall.adlAllocations().get("B"));
        assertEquals(BigDecimal.ZERO, waterfall.uncoveredLoss());
    }

    /** 已入账充值发生重组时保留历史入账并进入授权调整。 */
    @Test
    void creditedDepositReorgRequiresAdjustmentInsteadOfDeletion() {
        DigitalAssetWorkflow workflow = new DigitalAssetWorkflow();
        workflow.detectDeposit("1:tx:0", "ETH", "USDT", BigInteger.valueOf(1_000_000L),
            100L, "block-A");
        assertEquals(DepositStatus.CREDITED, workflow.confirmDeposit("1:tx:0",
            "block-A", 12L, 12L).status());
        var reorged = workflow.markReorg("1:tx:0");
        assertEquals(DepositStatus.ADJUSTMENT_REQUIRED, reorged.status());
        assertTrue(reorged.creditPosted());
    }

    /** 提现未知结果、Nonce接管、手续费替换和最终确认保持同一业务身份。 */
    @Test
    void withdrawalReplacementChainKeepsOneBusinessIntent() {
        DigitalAssetWorkflow workflow = new DigitalAssetWorkflow();
        workflow.requestWithdrawal("wd-1", "ETH", "USDT", BigInteger.valueOf(5_000_000L),
            "0xabc");
        assertEquals(0L, workflow.approveWithNonce("wd-1", "ETH:hot-1", 1L).nonce());
        assertEquals(WithdrawalStatus.UNKNOWN, workflow.markUnknown("wd-1").status());
        workflow.broadcast("wd-1", "tx-old", null);
        assertEquals(WithdrawalStatus.REPLACED,
            workflow.broadcast("wd-1", "tx-new", "tx-old").status());
        assertEquals(WithdrawalStatus.COMPLETED,
            workflow.complete("wd-1", "tx-new").status());

        long newEpoch = workflow.takeoverNonceCoordinator();
        workflow.requestWithdrawal("wd-2", "ETH", "USDT", BigInteger.ONE, "0xdef");
        assertThrows(IllegalStateException.class,
            () -> workflow.approveWithNonce("wd-2", "ETH:hot-1", newEpoch - 1L));
    }

    /** 创建测试行情。 */
    private SourceQuote quote(String source, long sequence, long previous,
                              String bid, String ask, Instant time) {
        return new SourceQuote(source, "BTC-USDT", sequence, previous,
            n(bid), n(ask), time);
    }

    /** 创建测试交易对配置。 */
    private InstrumentConfig instrument(TradingMode mode) {
        return new InstrumentConfig("BTC-USDT", 7L, mode, n("0.1"),
            n("0.1"), n("10"));
    }

    /** 创建一手限价买单。 */
    private OrderRequest order(String id, TimeInForce timeInForce, boolean postOnly) {
        return new OrderRequest(id, "BTC-USDT", OrderPolicyEngine.Side.BUY,
            Kind.LIMIT, timeInForce, n("100"), n("1"), postOnly, null);
    }

    /** 创建执行报告。 */
    private ExecutionReport report(String executionId, String clientOrderId,
                                   OrderState state, BigDecimal cumulative,
                                   BigDecimal leaves, ReportSource source) {
        return new ExecutionReport(executionId, clientOrderId, "exchange-1", state,
            cumulative, leaves, source);
    }

    /** 创建签名请求。 */
    private SignedRequest signed(byte[] secret, String nonce, Instant time) {
        SignedRequest unsigned = new SignedRequest("key-1", "203.0.113.10", Scope.TRADE,
            "POST", "/api/orders", "body-hash", time, nonce, "pending");
        return new SignedRequest(unsigned.apiKey(), unsigned.sourceIp(),
            unsigned.requiredScope(), unsigned.method(), unsigned.path(), unsigned.bodyHash(),
            unsigned.timestamp(), unsigned.nonce(),
            TradingApiSecurity.sign(secret, unsigned.canonicalPayload()));
    }

    /** 创建测试合约风险阶梯。 */
    private DerivativeRiskEngine derivativeRisk() {
        return new DerivativeRiskEngine(List.of(
            new MarginTier(n("100000"), n("0.10"), n("0.005")),
            new MarginTier(n("1000000"), n("0.15"), n("0.01"))));
    }

    /** 使用字符串构造精确十进制数。 */
    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }
}
