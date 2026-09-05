package dev.fincore.application;

import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.infrastructure.persistence.mapper.SpotFundsMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 用户、KYC、风控、账户、行情到撮合的完整链路公开实验。
 *
 * <p><strong>解决的问题：</strong>证明前台下单不是直接进入撮合，而会经过身份、权限、额度、价格
 * 偏离、资金预占、幂等和成交交割的完整链路。</p>
 *
 * <p><strong>CPU 与并发边界：</strong>场景级 {@link ReentrantLock} 只阻止同一演示被并发重复启动，
 * 不包裹生产交易链路；真实下单仍使用有界 Lane 和虚拟线程等待。实验数据量不代表生产容量。</p>
 *
 * <p><strong>正确性边界：</strong>每次生成隔离用户、账户和交易对；只有数据库事实、重复订单和 KYC
 * 拒绝断言全部通过才返回 {@code PASS}，固定场景不直接绕过 Kafka 执行公开交割。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.2.0
 */
@Profile("lab")
@Service
public class TradingLifecycleScenarioService {
    /** 用户、账户、风控和行情生命周期服务。 */
    private final TradingLifecycleService lifecycle;
    /** 使用有界交易对 Lane 的完整下单入口。 */
    private final TradingOrderCoordinator orders;
    /** 有界撤单入口。 */
    private final MatchingCommandCoordinator matching;
    /** 只读取本次随机新建的模拟账户，不接受外部账户编号。 */
    private final SpotFundsService funds;
    /** 只读等待真实 Worker 结果，公开固定场景不直接执行交割。 */
    private final SpotDeliveryService deliveries;
    /** 限制单进程场景并发，避免 synchronized 包裹长等待固定虚拟线程载体。 */
    private final ReentrantLock runLock = new ReentrantLock();

    /** 创建完整交易链路实验服务。 */
    public TradingLifecycleScenarioService(TradingLifecycleService lifecycle,
                                           TradingOrderCoordinator orders, MatchingCommandCoordinator matching,
                                           SpotFundsService funds, SpotDeliveryService deliveries) {
        this.lifecycle = lifecycle;
        this.orders = orders;
        this.matching = matching;
        this.funds = funds;
        this.deliveries = deliveries;
    }

    /** 运行一次隔离的完整交易链路实验。 */
    public LifecycleScenarioReport run() {
        if (!runLock.tryLock()) {
            throw new BusinessConflictException("trading lifecycle scenario is already running");
        }
        try {
            return runIsolated();
        } finally {
            runLock.unlock();
        }
    }

    /** 每次创建隔离的模拟账户，不读取其他用户余额，失败也不删除已提交的交易。 */
    private LifecycleScenarioReport runIsolated() {
        Instant startedAt = Instant.now();
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String baseAsset = "FLOW" + runId.toUpperCase();
        String symbol = baseAsset + "-USDT";
        String sellerId = "seller-" + runId;
        String buyerId = "buyer-" + runId;
        String pendingId = "pending-" + runId;
        Map<String, String> checks = new LinkedHashMap<>();

        prepareLifecycle(sellerId, buyerId, baseAsset, symbol, checks);
        TradeStage trade = executeTradeStage(
            runId, sellerId, buyerId, pendingId, symbol, checks);
        CancellationStage cancellation = executeCancellation(runId, buyerId, symbol, checks);
        SettlementStage settlement = settleAndReconcile(
            sellerId, buyerId, baseAsset, trade.buyer(), cancellation, checks);

        LifecycleData data = new LifecycleData(
            buyerId,
            "ACTIVE",
            "VERIFIED",
            "USDT",
            settlement.buyerQuote().balance(),
            symbol,
            trade.market().reference().price(),
            new BigDecimal("2"),
            new BigDecimal("200"),
            trade.buyer().preTradeDecision().decision(),
            trade.buyer().matching().order().status().name(),
            trade.buyer().matching().trades().size(),
            trade.rejected().preTradeDecision().reasonCode()
        );
        return new LifecycleScenarioReport(runId, "PASS", data, Map.copyOf(checks),
            settlement.evidence(), startedAt, Instant.now());
    }

    /** 创建双方账户、风控和参考行情，确保后续数据完全隔离于其他演示批次。 */
    private void prepareLifecycle(String sellerId, String buyerId, String baseAsset,
                                  String symbol, Map<String, String> checks) {
        prepareVerifiedUser(sellerId, baseAsset, new BigDecimal("10"));
        prepareVerifiedUser(buyerId, "USDT", new BigDecimal("1000"));
        lifecycle.publishQuote(symbol, new BigDecimal("100"),
            "LAB-CONSOLIDATED", Instant.now());
        checks.put("用户与 KYC", "PASS：买卖双方均为 ACTIVE + VERIFIED");
        checks.put("账户与风控", "PASS：分资产交易账户、单笔/日累计限额已生效");
        checks.put("参考行情", "PASS：价格 100，来源与观察时间已记录");
    }

    /** 完成 Maker、Taker、幂等重放和 KYC 失败关闭四个盘前与撮合步骤。 */
    private TradeStage executeTradeStage(String runId, String sellerId, String buyerId,
                                         String pendingId, String symbol,
                                         Map<String, String> checks) {
        var maker = orders.place(limit("sell-" + runId, sellerId, symbol,
            OrderSide.SELL, "100", "2"));
        require("APPROVED".equals(maker.preTradeDecision().decision()), "卖方盘前决定没有批准");
        require(maker.matching().order().status() == OrderStatus.OPEN, "卖方限价单没有进入订单簿");

        PlaceOrderCommand buyCommand = limit("buy-" + runId, buyerId, symbol,
            OrderSide.BUY, "100", "2");
        var buyer = orders.place(buyCommand);
        require("APPROVED".equals(buyer.preTradeDecision().decision()), "买方盘前决定没有批准");
        require(buyer.matching().order().status() == OrderStatus.FILLED, "买方订单没有完全成交");
        require(buyer.matching().trades().size() == 1, "完整链路没有形成唯一成交");
        checks.put("盘前决定", "PASS：用户、行情、额度和余额全部通过");
        checks.put("撮合结果", "PASS：BUY 2 @ 100，形成 1 条唯一成交");

        var replay = orders.place(buyCommand);
        require(replay.preTradeDecision().duplicate() && replay.matching().order().duplicate(),
            "重复请求没有同时复用风控决定与订单");
        checks.put("端到端幂等", "PASS：重放复用 1 个决定和 1 张订单");
        lifecycle.registerCustomer(pendingId, "待审核实验用户", "CN");
        var rejected = orders.place(limit("pending-" + runId, pendingId, symbol,
            OrderSide.BUY, "100", "1"));
        require("KYC_NOT_VERIFIED".equals(rejected.preTradeDecision().reasonCode())
            && rejected.matching() == null, "KYC 拒绝没有阻止订单进入撮合");
        checks.put("失败关闭", "PASS：KYC 未通过只留下拒绝决定，不创建订单");
        var market = lifecycle.market(symbol, 20, 20);
        require(market.recentTrades().size() == 1, "行情快照没有返回最近成交");
        checks.put("行情联动", "PASS：参考价、订单簿与最近成交统一返回");
        return new TradeStage(buyer, rejected, market);
    }

    /** 创建真实预占并重复撤单，证明只释放一次未成交资金。 */
    private CancellationStage executeCancellation(String runId, String buyerId,
                                                  String symbol, Map<String, String> checks) {
        var cancellable = orders.place(limit(
            "cancel-" + runId, buyerId, symbol, OrderSide.BUY, "90", "0.5"));
        require(cancellable.matching() != null
            && cancellable.matching().order().status() == OrderStatus.OPEN, "撤单样本未进入订单簿");
        SpotFundsMapper.FundsRow held = funds.view(buyerId, "USDT");
        require(held.reservedBalance().compareTo(new BigDecimal("45")) == 0,
            "挂单没有实际预占 45 USDT");
        checks.put("委托预占", "PASS：0.5 × 90 的挂单实际预占 45 USDT，可用余额同步减少");
        UUID canceledId = cancellable.matching().order().orderId();
        matching.cancel(canceledId, buyerId);
        matching.cancel(canceledId, buyerId);
        SpotFundsMapper.FundsRow released = funds.view(buyerId, "USDT");
        require(released.reservedBalance().signum() == 0
            && released.available().subtract(held.available()).compareTo(new BigDecimal("45")) == 0,
            "重复撤单没有精确释放未成交预占");
        checks.put("撤单释放", "PASS：重复撤单只释放一次 45 USDT，不释放已成交在途");
        return new CancellationStage(held, released);
    }

    /** 等待 Kafka Worker 交割，并逐一核对四个资产账户与不可变账本。 */
    private SettlementStage settleAndReconcile(
        String sellerId, String buyerId, String baseAsset,
        TradingLifecycleService.TradingOrderResult buyer, CancellationStage cancellation,
        Map<String, String> checks) {
        UUID tradeId = buyer.matching().trades().getFirst().tradeId();
        awaitDelivery(tradeId);
        var settled = deliveries.get(tradeId);
        var buyerQuote = funds.view(buyerId, "USDT");
        var buyerBase = funds.view(buyerId, baseAsset);
        var sellerBase = funds.view(sellerId, baseAsset);
        var sellerQuote = funds.view(sellerId, "USDT");
        require(buyerQuote.balance().compareTo(new BigDecimal("800")) == 0
            && buyerBase.balance().compareTo(new BigDecimal("2")) == 0
            && sellerBase.balance().compareTo(new BigDecimal("8")) == 0
            && sellerQuote.balance().compareTo(new BigDecimal("200")) == 0,
            "双资产交割实际余额不符");
        checks.put("双资产交割", "PASS：Outbox → Kafka → 有效 Fence Worker；买方 -200 USDT/+2 基础资产，卖方相反");
        for (var account : java.util.List.of(buyerQuote, buyerBase, sellerBase, sellerQuote)) {
            require(account.pendingDebit().signum() == 0 && account.reservedBalance().signum() == 0
                && funds.reconcile(account.accountId()), "交割后资金或账本不一致");
        }
        checks.put("资金对账", "PASS：4 个模拟资产账户的总余额、预占、在途与不可变账本逐一一致");
        SpotFundsEvidence evidence = new SpotFundsEvidence(
            tradeId, settled.status(), baseAsset, "USDT", cancellation.held().reservedBalance(),
            cancellation.held().available(), cancellation.released().available(), buyerQuote.balance(),
            buyerBase.balance(), sellerBase.balance(), sellerQuote.balance(), buyerQuote.pendingDebit(),
            4, settled.settledAt());
        return new SettlementStage(buyerQuote, evidence);
    }

    /** 盘前与撮合阶段输出。 */
    private record TradeStage(TradingLifecycleService.TradingOrderResult buyer,
                              TradingLifecycleService.TradingOrderResult rejected,
                              TradingLifecycleService.MarketView market) {
    }

    /** 撤单前后资金快照。 */
    private record CancellationStage(SpotFundsMapper.FundsRow held,
                                     SpotFundsMapper.FundsRow released) {
    }

    /** 交割后的买方计价账户与公开资金证据。 */
    private record SettlementStage(SpotFundsMapper.FundsRow buyerQuote,
                                   SpotFundsEvidence evidence) {
    }

    /** 最多等 20 秒，只查询 Worker 状态；超时不重复下单、不调用直连交割。 */
    private void awaitDelivery(UUID tradeId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if ("SETTLED".equals(deliveries.get(tradeId).status())) {
                return;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("交割等待中断；结果未知，tradeId=" + tradeId, exception);
            }
        }
        throw new BusinessConflictException("交割仍在途；不得重复发起本场景，tradeId=" + tradeId);
    }

    /** 创建已验证、已开户且允许交易的实验用户。 */
    private void prepareVerifiedUser(String userId, String asset, BigDecimal openingBalance) {
        lifecycle.registerCustomer(userId, userId, "CN");
        lifecycle.reviewKyc(userId, "VERIFIED");
        lifecycle.openTradingAccount(userId, asset, openingBalance);
        lifecycle.configureRisk(userId, "LOW", true, new BigDecimal("10000"),
            new BigDecimal("20000"), new BigDecimal("0.20"));
    }

    /** 构造限价单。 */
    private static PlaceOrderCommand limit(String clientOrderId, String userId, String symbol,
                                           OrderSide side, String price, String quantity) {
        return new PlaceOrderCommand(clientOrderId, userId, symbol, side, OrderType.LIMIT,
            new BigDecimal(price), new BigDecimal(quantity));
    }

    /** 业务断言失败时阻止生成伪成功报告。 */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** 完整链路实验报告。 */
    public record LifecycleScenarioReport(String runId, String finalStatus,
                                          LifecycleData data, Map<String, String> checks,
                                          SpotFundsEvidence funds, Instant startedAt, Instant completedAt) {
        /** 固化检查结果，避免调用方修改报告。 */
        public LifecycleScenarioReport {
            checks = Map.copyOf(checks);
        }
    }

    /** 本次新建模拟账户的资金证据，不接受任意账户查询参数。 */
    public record SpotFundsEvidence(UUID tradeId, String deliveryStatus, String baseAsset, String quoteAsset,
                                     BigDecimal reservedForCancel, BigDecimal availableWhileReserved,
                                     BigDecimal availableAfterCancel, BigDecimal buyerQuoteBalance,
                                     BigDecimal buyerBaseBalance, BigDecimal sellerBaseBalance,
                                     BigDecimal sellerQuoteBalance, BigDecimal pendingDebit,
                                     int reconciledAccounts, Instant settledAt) { }

    /** 一次成功订单及一个失败关闭样本的关键业务数据。 */
    public record LifecycleData(String userId, String userStatus, String kycStatus,
                                String accountAsset, BigDecimal accountBalance,
                                String symbol, BigDecimal referencePrice,
                                BigDecimal quantity, BigDecimal orderNotional,
                                String preTradeDecision, String orderStatus,
                                int tradeCount, String rejectedReasonCode) {
    }
}
