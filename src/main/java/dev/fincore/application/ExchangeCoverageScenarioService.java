package dev.fincore.application;

import dev.fincore.exchange.DerivativeRiskEngine;
import dev.fincore.exchange.DigitalAssetWorkflow;
import dev.fincore.exchange.FeeEngine;
import dev.fincore.exchange.FixOmsReconciler;
import dev.fincore.exchange.MarketDataReliability;
import dev.fincore.exchange.MarketSurveillanceEngine;
import dev.fincore.exchange.MatchingRecoveryLog;
import dev.fincore.exchange.OrderPolicyEngine;
import dev.fincore.exchange.TradingApiSecurity;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 交易所高频面试能力补全的一键实验编排服务。
 *
 * <p>该服务只在 {@code lab} Profile 下运行，组合九个互相隔离的纯领域模型，并在返回报告前执行
 * 业务断言。它不连接真实交易所、公链或机构FIX网关，不会改变现有资金账本。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
@Profile("lab")
@Service
public class ExchangeCoverageScenarioService {

    /**
     * 执行行情、订单、机构接入、市场监察、安全、费用、恢复、合约和链上状态九组实验。
     *
     * @return 全部检查通过后的结构化证据报告
     */
    public CoverageReport run() {
        String runId = "exchange-" + System.currentTimeMillis();
        Map<String, DomainReport> domains = new LinkedHashMap<>();
        domains.put("marketData", marketData());
        domains.put("orderSemantics", orderSemantics());
        domains.put("fixOms", fixOms());
        domains.put("surveillance", surveillance());
        domains.put("apiSecurity", apiSecurity());
        domains.put("fees", fees());
        domains.put("matchingRecovery", matchingRecovery());
        domains.put("derivatives", derivatives());
        domains.put("digitalAssets", digitalAssets());
        require(domains.values().stream().allMatch(report -> "PASS".equals(report.status())),
            "交易所能力补全实验存在未通过检查");
        return new CoverageReport(runId, Instant.now(), "PASS", domains.size(),
            Map.copyOf(domains),
            "九组确定性实验全部通过；结果证明模型语义，不代表生产容量、协议认证或真实资产运行");
    }

    /** 行情序列缺口、快照恢复、多源择优和慢消费者实验。 */
    private DomainReport marketData() {
        MarketDataReliability market = new MarketDataReliability();
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        require(market.onDelta(quote("A", 1, 0, "99", "101", time))
            == MarketDataReliability.DeltaOutcome.APPLIED, "首条行情未应用");
        require(market.onDelta(quote("A", 3, 2, "100", "102", time.plusMillis(2)))
            == MarketDataReliability.DeltaOutcome.GAP, "行情缺口未识别");
        require(market.recover(quote("A", 2, 0, "99.5", "100.5", time.plusMillis(1)),
            List.of(quote("A", 3, 2, "100", "102", time.plusMillis(2))))
            == MarketDataReliability.SourceHealth.HEALTHY, "快照恢复未收敛");
        market.onDelta(quote("B", 1, 0, "100", "102", time.plusMillis(3)));
        market.onDelta(quote("C", 1, 0, "999", "1001", time.plusMillis(4)));
        var reference = market.selectReference(time.plusSeconds(1),
            Duration.ofSeconds(5), n("100"));
        var buffer = new MarketDataReliability.LatestValueBuffer(1);
        buffer.offer("BTC-USDT", reference);
        buffer.offer("BTC-USDT", reference);
        boolean secondSymbolAccepted = buffer.offer("ETH-USDT", reference);
        require(reference.acceptedSources() == 2 && !secondSymbolAccepted
            && buffer.coalesced() == 1 && buffer.dropped() == 1,
            "多源择优或慢消费者保护结果错误");
        return report("行情完整性与分发", "序列缺口→STALE→快照+增量恢复→多源剔除→有界合并",
            Map.of("referenceSource", reference.source(), "acceptedSources", 2,
                "coalescedUpdates", 1, "droppedSymbols", 1),
            List.of("缺口来源停止参与风控", "快照之后只回放连续增量", "异常源不污染参考价",
                "慢消费者不形成无界内存"),
            "未连接真实Feed、SBE、Aeron、ITCH/OUCH或多播网络");
    }

    /** 高级订单类型、交易模式、STP和撤改单部分结果实验。 */
    private DomainReport orderSemantics() {
        OrderPolicyEngine engine = new OrderPolicyEngine();
        var config = new OrderPolicyEngine.InstrumentConfig("BTC-USDT", 7L,
            OrderPolicyEngine.TradingMode.FULL_TRADING, n("0.1"), n("0.1"), n("10"));
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        var ioc = engine.plan(order("ioc", OrderPolicyEngine.TimeInForce.IOC, false),
            config, n("100"), n("0.4"), time);
        var fok = engine.plan(order("fok", OrderPolicyEngine.TimeInForce.FOK, false),
            config, n("100"), n("0.4"), time);
        var postOnly = engine.plan(order("post", OrderPolicyEngine.TimeInForce.GTC, true),
            config, n("100"), n("1"), time);
        var stp = engine.preventSelfTrade(n("2"), n("1"),
            OrderPolicyEngine.SelfTradePolicy.DECREMENT_AND_CANCEL);
        var partialReplace = engine.cancelReplace(false, true);
        require(ioc.canceledQuantity().compareTo(n("0.6")) == 0
            && "FOK_DEPTH_INSUFFICIENT".equals(fok.rejectionReason())
            && "POST_ONLY_WOULD_TAKE".equals(postOnly.rejectionReason())
            && stp.takerRemaining().signum() == 0
            && "NEW_ACCEPTED_CANCEL_FAILED".equals(partialReplace.status()),
            "订单语义实验结果错误");
        return report("订单语义与产品配置", "版本化规则→模式/精度→TIF→STP→撤改单组合终态",
            Map.of("instrumentVersion", 7, "iocExecuted", "0.4",
                "iocCanceled", "0.6", "cancelReplace", partialReplace.status()),
            List.of("IOC剩余量明确取消", "FOK深度不足全拒绝", "Post-only不会意外吃单",
                "STP不生成自成交", "撤单与新单结果分别记录"),
            "尚未接入现有数据库撮合状态机和条件单触发器");
    }

    /** FIX会话、未知结果、掉线撤单和Drop Copy实验。 */
    private DomainReport fixOms() {
        FixOmsReconciler oms = new FixOmsReconciler();
        require(oms.acceptSequence(1L, false) == FixOmsReconciler.SequenceOutcome.ACCEPTED,
            "FIX首序号错误");
        require(oms.acceptSequence(3L, false) == FixOmsReconciler.SequenceOutcome.GAP_DETECTED,
            "FIX缺口未发现");
        require(oms.acceptSequence(2L, false) == FixOmsReconciler.SequenceOutcome.ACCEPTED,
            "FIX补发未接收");
        oms.submit("client-1", n("1"));
        oms.markUnknown("client-1");
        var fill = new FixOmsReconciler.ExecutionReport("exec-1", "client-1", "exchange-1",
            FixOmsReconciler.OrderState.FILLED, n("1"), BigDecimal.ZERO,
            FixOmsReconciler.ReportSource.DROP_COPY);
        require(!oms.apply(fill).duplicateExecution() && oms.apply(fill).duplicateExecution(),
            "Drop Copy重复执行未去重");
        oms.submit("client-2", n("2"));
        List<String> pendingCancel = oms.onDisconnect(true);
        require(pendingCancel.equals(List.of("client-2"))
            && oms.order("client-2").state() == FixOmsReconciler.OrderState.PENDING_CANCEL,
            "掉线撤单被错误提前标记成功");
        return report("FIX / OMS / Drop Copy", "序号缺口→补发→未知订单→权威回报→执行去重",
            Map.of("expectedSequence", oms.expectedSequence(), "filledOrders", 1,
                "pendingCancelConfirmations", pendingCancel.size()),
            List.of("序号与业务幂等分离", "PossDup不重复产生业务效果", "超时先进入UNKNOWN",
                "Drop Copy按execId去重", "掉线撤单等待权威回报"),
            "使用协议模型而非真实QuickFIX连接或交易所认证会话");
    }

    /** 市场公平、异常撤单和最佳执行偏差实验。 */
    private DomainReport surveillance() {
        MarketSurveillanceEngine engine = new MarketSurveillanceEngine();
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        for (int index = 0; index < 3; index++) {
            String id = "layer-" + index;
            BigDecimal price = n(Integer.toString(100 + index));
            engine.onOrder(new MarketSurveillanceEngine.OrderEvent(id, "owner-A",
                MarketSurveillanceEngine.Side.SELL, price, n("10"),
                MarketSurveillanceEngine.OrderAction.NEW, time), n("5"), Duration.ofSeconds(1));
            engine.onOrder(new MarketSurveillanceEngine.OrderEvent(id, "owner-A",
                MarketSurveillanceEngine.Side.SELL, price, n("10"),
                MarketSurveillanceEngine.OrderAction.CANCEL, time.plusMillis(100)),
                n("5"), Duration.ofSeconds(1));
        }
        engine.closeWindow(3, n("0.8"));
        engine.onTrade(new MarketSurveillanceEngine.TradeEvent("wash-1", "owner-A", "owner-A",
            n("101"), n("1"), time));
        engine.checkExecution(MarketSurveillanceEngine.Side.BUY, n("100"), n("102"),
            n("100"), "owner-A", "slippage-1");
        Map<MarketSurveillanceEngine.SignalType, Long> summary = engine.summary();
        require(summary.size() == 5 && engine.signals().size() == 7,
            "市场监察信号数量错误");
        return report("市场公平与最佳执行", "订单/成交审计→规则信号→人工调查队列",
            Map.of("signals", engine.signals().size(), "signalTypes", summary.size(),
                "rapidLargeCancels", summary.get(
                    MarketSurveillanceEngine.SignalType.RAPID_LARGE_CANCEL)),
            List.of("同受益所有人成交被识别", "快速大单撤销形成线索", "三层挂撤形成Layering信号",
                "高撤单率独立统计", "成交滑点相对决策时参考价复算"),
            "信号不是违规定罪；仍需关联账户、盘口上下文、模型评估和人工复核");
    }

    /** 交易API签名、防重放、权限和限频实验。 */
    private DomainReport apiSecurity() {
        TradingApiSecurity security = new TradingApiSecurity(Duration.ofSeconds(5),
            2, Duration.ofMinutes(1));
        byte[] secret = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8);
        security.register("key-1", secret, Set.of(TradingApiSecurity.Scope.TRADE),
            Set.of("203.0.113.10"));
        Instant time = Instant.parse("2026-09-04T00:00:00Z");
        var first = signed(secret, "nonce-1", time);
        require(security.verify(first, time) == TradingApiSecurity.Decision.ALLOWED,
            "合法签名请求未通过");
        require(security.verify(first, time) == TradingApiSecurity.Decision.REPLAYED_NONCE,
            "重复随机数未拒绝");
        require(security.verify(signed(secret, "nonce-2", time), time)
            == TradingApiSecurity.Decision.ALLOWED, "第二个合法请求未通过");
        require(security.verify(signed(secret, "nonce-3", time), time)
            == TradingApiSecurity.Decision.RATE_LIMITED, "超限请求未拒绝");
        return report("交易API安全", "Key→IP→权限→时间→HMAC→Nonce→限频",
            Map.of("allowed", 2, "replayRejected", 1, "rateLimited", 1),
            List.of("签名前规范化请求", "时钟窗口失败关闭", "Nonce只在验签后消费",
                "权限和IP白名单独立", "超限不进入交易核心"),
            "未接入KMS、分布式限频、WAF、DDoS或账户接管检测");
    }

    /** VIP费率、Maker返佣和历史冲正实验。 */
    private DomainReport fees() {
        FeeEngine engine = new FeeEngine(8, List.of(
            new FeeEngine.FeeTier("VIP0", BigDecimal.ZERO, n("1.0"), n("2.0")),
            new FeeEngine.FeeTier("VIP1", n("1000000"), n("-0.2"), n("1.5"))));
        var charge = engine.calculate("trade-1", n("60000"), n("2000000"),
            FeeEngine.LiquidityRole.MAKER);
        var reversal = engine.reverse(charge, "trade-bust-1");
        require(charge.balanced() && reversal.balanced()
            && charge.fee().compareTo(n("-1.2")) == 0
            && reversal.userDelta().compareTo(charge.userDelta().negate()) == 0,
            "手续费或冲正不平衡");
        return report("手续费与经济正确性", "成交快照→VIP阶梯→一次舍入→双腿分录→原额冲正",
            Map.of("tier", charge.tierName(), "makerRateBps", charge.rateBasisPoints(),
                "fee", charge.fee(), "balanced", true),
            List.of("Maker负费率支持返佣", "Taker与Maker使用独立费率", "只舍入一次",
                "用户与手续费账户严格平衡", "成交撤销引用原额而非当前费率"),
            "计算模型尚未写入现货交割账本或接入真实VIP快照");
    }

    /** 单写者、快照、日志重放和旧Epoch拒写实验。 */
    private DomainReport matchingRecovery() {
        MatchingRecoveryLog log = new MatchingRecoveryLog();
        log.append("command-1", 1L, MatchingRecoveryLog.CommandType.PLACE,
            "order-1", n("2"));
        var snapshot = log.snapshot();
        log.append("command-2", 1L, MatchingRecoveryLog.CommandType.CANCEL,
            "order-1", BigDecimal.ZERO);
        long newEpoch = log.takeover();
        var stale = log.append("command-old", 1L, MatchingRecoveryLog.CommandType.PLACE,
            "order-2", n("1"));
        var recovered = MatchingRecoveryLog.recover(snapshot, log.journal());
        require(newEpoch == 2L && "STALE_EPOCH".equals(stale.status())
            && recovered.checksum().equals(log.checksum()), "撮合恢复或围栏实验失败");
        return report("撮合快照与确定性重放", "单写命令→顺序日志→快照→接管→连续重放→摘要核验",
            Map.of("snapshotSequence", snapshot.lastSequence(),
                "recoveredSequence", recovered.lastSequence(), "epoch", newEpoch,
                "checksumMatches", true),
            List.of("业务命令重复时核对载荷", "快照摘要防损坏", "日志缺口立即停止",
                "同序列输入得到同状态摘要", "旧Epoch永久拒写"),
            "未实现磁盘刷写、复制共识、热备选举和微秒级内存订单簿");
    }

    /** 全仓/逐仓、保证金阶梯、资金费、部分强平和损失瀑布实验。 */
    private DomainReport derivatives() {
        DerivativeRiskEngine risk = new DerivativeRiskEngine(List.of(
            new DerivativeRiskEngine.MarginTier(n("100000"), n("0.10"), n("0.005")),
            new DerivativeRiskEngine.MarginTier(n("1000000"), n("0.15"), n("0.01"))));
        var position = new DerivativeRiskEngine.Position("BTC-USDT", n("1"),
            n("60000"), n("59000"));
        var cross = risk.assess(n("2000"), List.of(position),
            DerivativeRiskEngine.MarginMode.CROSS, Map.of());
        var isolated = risk.assess(BigDecimal.ZERO, List.of(position),
            DerivativeRiskEngine.MarginMode.ISOLATED, Map.of("BTC-USDT", n("1100")));
        var waterfall = risk.coverLoss(n("100"), n("60"), List.of(
            new DerivativeRiskEngine.AdlCandidate("B", n("2"), n("15")),
            new DerivativeRiskEngine.AdlCandidate("A", n("3"), n("30"))));
        require(cross.buckets().get("CROSS").status() == DerivativeRiskEngine.RiskStatus.SAFE
            && isolated.buckets().get("BTC-USDT").status()
            == DerivativeRiskEngine.RiskStatus.LIQUIDATION_REQUIRED
            && waterfall.uncoveredLoss().signum() == 0,
            "合约风险域或损失瀑布结果错误");
        return report("合约风险生命周期", "仓位→阶梯IM/MM→全仓/逐仓权益→部分强平→保险基金→ADL",
            Map.of("crossStatus", "SAFE", "isolatedStatus", "LIQUIDATION_REQUIRED",
                "fundingLongDelta", risk.fundingCashDelta(
                    new DerivativeRiskEngine.Position("BTC-USDT", n("1"), n("60000"),
                        n("60000")), n("0.0001")),
                "insuranceUsed", waterfall.insuranceUsed(), "adlAccounts", 2),
            List.of("全仓和逐仓风险域分离", "名义金额选择保证金阶梯", "正资金费多头支付",
                "部分强平不穿越零仓位", "保险基金不足后才进入ADL排名"),
            "纯计算补全；强平成交、仓位账本和事件状态机仍需生产化串联");
    }

    /** 充值重组、提现未知结果、Nonce围栏和替换交易实验。 */
    private DomainReport digitalAssets() {
        DigitalAssetWorkflow workflow = new DigitalAssetWorkflow();
        workflow.detectDeposit("1:tx:0", "ETH", "USDT", BigInteger.valueOf(1_000_000L),
            100L, "block-A");
        workflow.confirmDeposit("1:tx:0", "block-A", 12L, 12L);
        var reorg = workflow.markReorg("1:tx:0");
        workflow.requestWithdrawal("withdrawal-1", "ETH", "USDT",
            BigInteger.valueOf(5_000_000L), "0xabc");
        workflow.approveWithNonce("withdrawal-1", "ETH:hot-1", 1L);
        workflow.markUnknown("withdrawal-1");
        workflow.broadcast("withdrawal-1", "tx-old", null);
        var replaced = workflow.broadcast("withdrawal-1", "tx-new", "tx-old");
        var completed = workflow.complete("withdrawal-1", "tx-new");
        require(reorg.status() == DigitalAssetWorkflow.DepositStatus.ADJUSTMENT_REQUIRED
            && replaced.transactionHashes().size() == 2
            && completed.status() == DigitalAssetWorkflow.WithdrawalStatus.COMPLETED,
            "链上状态机实验失败");
        return report("数字资产状态机", "链事件→确认→入账→重组调整；提现→Nonce→未知→替换→确认",
            Map.of("depositAfterReorg", reorg.status(), "withdrawal", completed.status(),
                "replacementHashes", replaced.transactionHashes().size()),
            List.of("链上金额使用整数最小单位", "充值事件键防重复入账", "已入账重组不删除历史",
                "未知结果先查询原意图", "手续费替换保持一个提现业务身份"),
            "确定性模拟器；未连接测试网、多RPC、索引器或HSM/MPC");
    }

    /** 创建统一领域报告。 */
    private DomainReport report(String title, String flow, Map<String, Object> metrics,
                                List<String> checks, String boundary) {
        return new DomainReport(title, "PASS", flow, metrics, checks, boundary);
    }

    /** 创建测试行情。 */
    private MarketDataReliability.SourceQuote quote(String source, long sequence,
                                                    long previous, String bid,
                                                    String ask, Instant time) {
        return new MarketDataReliability.SourceQuote(source, "BTC-USDT", sequence,
            previous, n(bid), n(ask), time);
    }

    /** 创建一手限价买单。 */
    private OrderPolicyEngine.OrderRequest order(String id,
                                                 OrderPolicyEngine.TimeInForce timeInForce,
                                                 boolean postOnly) {
        return new OrderPolicyEngine.OrderRequest(id, "BTC-USDT",
            OrderPolicyEngine.Side.BUY, OrderPolicyEngine.Kind.LIMIT, timeInForce,
            n("100"), n("1"), postOnly, null);
    }

    /** 创建交易API签名请求。 */
    private TradingApiSecurity.SignedRequest signed(byte[] secret, String nonce,
                                                    Instant time) {
        var unsigned = new TradingApiSecurity.SignedRequest("key-1", "203.0.113.10",
            TradingApiSecurity.Scope.TRADE, "POST", "/api/orders", "body-hash",
            time, nonce, "pending");
        return new TradingApiSecurity.SignedRequest(unsigned.apiKey(), unsigned.sourceIp(),
            unsigned.requiredScope(), unsigned.method(), unsigned.path(), unsigned.bodyHash(),
            unsigned.timestamp(), unsigned.nonce(),
            TradingApiSecurity.sign(secret, unsigned.canonicalPayload()));
    }

    /** 使用字符串创建精确十进制数。 */
    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    /** 在报告生成前执行不可跳过的业务断言。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 一次完整补全实验报告。 */
    public record CoverageReport(String runId, Instant generatedAt, String status,
                                 int domainCount, Map<String, DomainReport> domains,
                                 String evidenceBoundary) {
        /** 防止调用方修改领域报告集合。 */
        public CoverageReport {
            domains = Map.copyOf(domains);
        }
    }

    /** 单个交易能力域的场景、数据、断言和边界。 */
    public record DomainReport(String title, String status, String flow,
                               Map<String, Object> metrics, List<String> checks,
                               String boundary) {
        /** 防止调用方修改数据和断言。 */
        public DomainReport {
            metrics = Map.copyOf(metrics);
            checks = List.copyOf(checks);
        }
    }
}
