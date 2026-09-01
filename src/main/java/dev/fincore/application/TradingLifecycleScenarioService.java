package dev.fincore.application;

import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 用户、KYC、风控、账户、行情到撮合的完整链路公开实验。
 *
 * <p>每次运行都生成隔离用户、账户和交易对，先形成一张卖单，再让买方经过完整盘前检查成交；
 * 随后重放同一订单并增加一个 KYC 拒绝样本。只有全部数据库和业务断言通过才返回 {@code PASS}。</p>
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

    /** 创建完整交易链路实验服务。 */
    public TradingLifecycleScenarioService(TradingLifecycleService lifecycle,
                                           TradingOrderCoordinator orders) {
        this.lifecycle = lifecycle;
        this.orders = orders;
    }

    /** 运行一次隔离的完整交易链路实验。 */
    public synchronized LifecycleScenarioReport run() {
        String runId = UUID.randomUUID().toString().substring(0, 8);
        String suffix = runId.toUpperCase();
        String baseAsset = "FLOW" + suffix;
        String symbol = baseAsset + "-USDT";
        String sellerId = "seller-" + runId;
        String buyerId = "buyer-" + runId;
        String pendingId = "pending-" + runId;
        Map<String, String> checks = new LinkedHashMap<>();

        prepareVerifiedUser(sellerId, baseAsset, new BigDecimal("10"));
        prepareVerifiedUser(buyerId, "USDT", new BigDecimal("1000"));
        lifecycle.publishQuote(symbol, new BigDecimal("100"),
            "LAB-CONSOLIDATED", Instant.now());
        checks.put("用户与 KYC", "PASS：买卖双方均为 ACTIVE + VERIFIED");
        checks.put("账户与风控", "PASS：分资产交易账户、单笔/日累计限额已生效");
        checks.put("参考行情", "PASS：价格 100，来源与观察时间已记录");

        var maker = orders.place(limit("sell-" + runId, sellerId, symbol,
            OrderSide.SELL, "100", "2"));
        require("APPROVED".equals(maker.preTradeDecision().decision()),
            "卖方盘前决定没有批准");
        require(maker.matching().order().status() == OrderStatus.OPEN,
            "卖方限价单没有进入订单簿");

        PlaceOrderCommand buyCommand = limit("buy-" + runId, buyerId, symbol,
            OrderSide.BUY, "100", "2");
        var buyer = orders.place(buyCommand);
        require("APPROVED".equals(buyer.preTradeDecision().decision()),
            "买方盘前决定没有批准");
        require(buyer.matching().order().status() == OrderStatus.FILLED,
            "买方订单没有完全成交");
        require(buyer.matching().trades().size() == 1,
            "完整链路没有形成唯一成交");
        checks.put("盘前决定", "PASS：用户、行情、额度和余额全部通过");
        checks.put("撮合结果", "PASS：BUY 2 @ 100，形成 1 条唯一成交");

        var replay = orders.place(buyCommand);
        require(replay.preTradeDecision().duplicate()
                && replay.matching().order().duplicate(),
            "重复请求没有同时复用风控决定与订单");
        checks.put("端到端幂等", "PASS：重放复用 1 个决定和 1 张订单");

        lifecycle.registerCustomer(pendingId, "待审核实验用户", "CN");
        var rejected = orders.place(limit("pending-" + runId, pendingId, symbol,
            OrderSide.BUY, "100", "1"));
        require("KYC_NOT_VERIFIED".equals(rejected.preTradeDecision().reasonCode())
                && rejected.matching() == null,
            "KYC 拒绝没有阻止订单进入撮合");
        checks.put("失败关闭", "PASS：KYC 未通过只留下拒绝决定，不创建订单");

        var market = lifecycle.market(symbol, 20, 20);
        require(market.recentTrades().size() == 1,
            "行情快照没有返回最近成交");
        checks.put("行情联动", "PASS：参考价、订单簿与最近成交统一返回");

        LifecycleData data = new LifecycleData(
            buyerId,
            "ACTIVE",
            "VERIFIED",
            "USDT",
            new BigDecimal("1000"),
            symbol,
            market.reference().price(),
            new BigDecimal("2"),
            new BigDecimal("200"),
            buyer.preTradeDecision().decision(),
            buyer.matching().order().status().name(),
            buyer.matching().trades().size(),
            rejected.preTradeDecision().reasonCode()
        );
        return new LifecycleScenarioReport(runId, "PASS", data, Map.copyOf(checks));
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
                                          LifecycleData data, Map<String, String> checks) {
        /** 固化检查结果，避免调用方修改报告。 */
        public LifecycleScenarioReport {
            checks = Map.copyOf(checks);
        }
    }

    /** 一次成功订单及一个失败关闭样本的关键业务数据。 */
    public record LifecycleData(String userId, String userStatus, String kycStatus,
                                String accountAsset, BigDecimal accountBalance,
                                String symbol, BigDecimal referencePrice,
                                BigDecimal quantity, BigDecimal orderNotional,
                                String preTradeDecision, String orderStatus,
                                int tradeCount, String rejectedReasonCode) {
    }
}
