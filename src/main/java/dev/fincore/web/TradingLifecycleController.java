package dev.fincore.web;

import dev.fincore.application.AccountService;
import dev.fincore.application.TradingLifecycleService;
import dev.fincore.application.TradingOrderCoordinator;
import dev.fincore.domain.PlaceOrderCommand;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户、KYC、账户、风控、行情和受控下单的完整交易链路接口。
 *
 * <p>本控制器只负责 HTTP 映射；受控订单必须经过 {@link TradingOrderCoordinator} 的有界 Lane，
 * 不允许接入层跳过用户、风险和账户检查直接修改撮合或资金状态。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.2.0
 */
@RestController
@RequestMapping("/api/trading")
public class TradingLifecycleController {
    /** 用户、风控、账户和行情服务。 */
    private final TradingLifecycleService lifecycle;
    /** 完整受控下单协调器。 */
    private final TradingOrderCoordinator orders;

    /** 创建完整交易链路控制器。 */
    public TradingLifecycleController(TradingLifecycleService lifecycle,
                                      TradingOrderCoordinator orders) {
        this.lifecycle = lifecycle;
        this.orders = orders;
    }

    /** 注册一个待 KYC 审核的实验用户。 */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public TradingLifecycleService.CustomerView register(@RequestBody CreateUserRequest request) {
        return lifecycle.registerCustomer(request.userId(), request.displayName(),
            request.countryCode());
    }

    /** 查询用户状态与 KYC 结果。 */
    @GetMapping("/users/{userId}")
    public TradingLifecycleService.CustomerView user(@PathVariable String userId) {
        return lifecycle.customer(userId);
    }

    /** 更新 KYC 审核结果。 */
    @PutMapping("/users/{userId}/kyc")
    public TradingLifecycleService.CustomerView reviewKyc(
        @PathVariable String userId, @RequestBody KycReviewRequest request) {
        return lifecycle.reviewKyc(userId, request.status());
    }

    /** 暂停、恢复或关闭用户。 */
    @PatchMapping("/users/{userId}/status")
    public TradingLifecycleService.CustomerView changeStatus(
        @PathVariable String userId, @RequestBody UserStatusRequest request) {
        return lifecycle.changeCustomerStatus(userId, request.status());
    }

    /** 为用户创建指定资产的交易账户。 */
    @PostMapping("/users/{userId}/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.AccountView openAccount(@PathVariable String userId,
                                                  @RequestBody OpenAccountRequest request) {
        return lifecycle.openTradingAccount(userId, request.asset(), request.openingBalance());
    }

    /** 新建或更新用户风控档案。 */
    @PutMapping("/risk/{userId}")
    public TradingLifecycleService.RiskProfileView configureRisk(
        @PathVariable String userId, @RequestBody RiskProfileRequest request) {
        return lifecycle.configureRisk(userId, request.riskLevel(), request.tradingEnabled(),
            request.maxOrderNotional(), request.maxDailyNotional(),
            request.maxPriceDeviation());
    }

    /** 查询用户风控档案。 */
    @GetMapping("/risk/{userId}")
    public TradingLifecycleService.RiskProfileView risk(@PathVariable String userId) {
        return lifecycle.riskProfile(userId);
    }

    /** 发布带来源和观察时间的参考行情。 */
    @PutMapping("/market/{symbol}/reference")
    public TradingLifecycleService.MarketQuoteView publishQuote(
        @PathVariable String symbol, @RequestBody MarketQuoteRequest request) {
        Instant observedAt = request.observedAt() == null ? Instant.now() : request.observedAt();
        return lifecycle.publishQuote(symbol, request.price(), request.source(), observedAt);
    }

    /** 查询参考价、订单簿与最近成交。 */
    @GetMapping("/market/{symbol}")
    public TradingLifecycleService.MarketView market(
        @PathVariable String symbol,
        @RequestParam(defaultValue = "20") int depth,
        @RequestParam(defaultValue = "50") int tradeLimit) {
        return lifecycle.market(symbol, depth, tradeLimit);
    }

    /** 经用户、KYC、风控、账户与行情检查后提交撮合。 */
    @PostMapping("/orders")
    public TradingLifecycleService.TradingOrderResult place(
        @RequestBody PlaceOrderCommand command) {
        return orders.place(command);
    }

    /** 注册用户请求。 */
    public record CreateUserRequest(String userId, String displayName, String countryCode) {
    }

    /** KYC 审核请求。 */
    public record KycReviewRequest(String status) {
    }

    /** 用户生命周期状态变更请求。 */
    public record UserStatusRequest(String status) {
    }

    /** 创建交易账户请求。 */
    public record OpenAccountRequest(String asset, BigDecimal openingBalance) {
    }

    /** 用户风控档案请求。 */
    public record RiskProfileRequest(String riskLevel, boolean tradingEnabled,
                                     BigDecimal maxOrderNotional,
                                     BigDecimal maxDailyNotional,
                                     BigDecimal maxPriceDeviation) {
    }

    /** 参考行情发布请求。 */
    public record MarketQuoteRequest(BigDecimal price, String source, Instant observedAt) {
    }
}
