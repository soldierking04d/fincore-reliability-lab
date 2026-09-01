package dev.fincore.application;

import dev.fincore.domain.MatchingPolicy;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderBookView;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.infrastructure.persistence.mapper.TradingLifecycleMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户、KYC、账户、行情、盘前风控与撮合的完整交易生命周期服务。
 *
 * <p>受控下单入口先验证用户和 KYC，再锁定用户风控档案，读取新鲜参考行情、校验价格偏离、
 * 单笔/日累计额度与交易账户余额，持久化不可覆盖的盘前决定，最后加入现有撮合事务。相同
 * {@code userId + clientOrderId} 重试复用原决定和原订单，不会再次消耗风控额度。</p>
 *
 * <p>当前账户校验是保守的盘前可用余额检查，尚未实现开放委托资金冻结；因此该入口用于可靠性实验，
 * 不宣称已经达到生产交易账户的可用/冻结/在途资金完整模型。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.2.0
 */
@Service
public class TradingLifecycleService {
    /** 参考行情最大允许年龄。 */
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(30);
    /** 用户、风控、行情和盘前决定持久化接口。 */
    private final TradingLifecycleMapper lifecycleMapper;
    /** 账户生命周期服务。 */
    private final AccountService accountService;
    /** 已有确定性撮合服务。 */
    private final MatchingService matchingService;
    /** 批准盘前决定计数器。 */
    private final Counter approvedCounter;
    /** 拒绝盘前决定计数器。 */
    private final Counter rejectedCounter;

    /**
     * 创建完整交易生命周期服务。
     *
     * @param lifecycleMapper 用户、风控、行情和盘前决定持久化接口
     * @param accountService 账户服务
     * @param matchingService 撮合服务
     * @param meterRegistry 指标注册表
     */
    public TradingLifecycleService(TradingLifecycleMapper lifecycleMapper,
                                   AccountService accountService,
                                   MatchingService matchingService,
                                   MeterRegistry meterRegistry) {
        this.lifecycleMapper = lifecycleMapper;
        this.accountService = accountService;
        this.matchingService = matchingService;
        this.approvedCounter = meterRegistry.counter("fincore.pretrade.decisions", "decision", "approved");
        this.rejectedCounter = meterRegistry.counter("fincore.pretrade.decisions", "decision", "rejected");
    }

    /** 创建待 KYC 审核的用户。 */
    @Transactional
    public CustomerView registerCustomer(String userId, String displayName, String countryCode) {
        String normalizedUserId = required(userId, "userId");
        String normalizedName = required(displayName, "displayName");
        String normalizedCountry = required(countryCode, "countryCode").toUpperCase(Locale.ROOT);
        if (!normalizedUserId.matches("[A-Za-z0-9][A-Za-z0-9._-]{1,99}")) {
            throw new IllegalArgumentException("userId format is invalid");
        }
        if (normalizedCountry.length() != 2) {
            throw new IllegalArgumentException("countryCode must contain two letters");
        }
        lifecycleMapper.insertCustomer(normalizedUserId, normalizedName, normalizedCountry);
        return customer(normalizedUserId);
    }

    /** 查询用户当前状态。 */
    public CustomerView customer(String userId) {
        TradingLifecycleMapper.CustomerRow row = lifecycleMapper.findCustomer(required(userId, "userId"));
        if (row == null) {
            throw new IllegalArgumentException("customer does not exist");
        }
        return toCustomer(row);
    }

    /** 更新 KYC 审核结果。 */
    @Transactional
    public CustomerView reviewKyc(String userId, String kycStatus) {
        String normalized = required(kycStatus, "kycStatus").toUpperCase(Locale.ROOT);
        requireOneOf(normalized, "kycStatus", "PENDING", "VERIFIED", "REJECTED");
        if (lifecycleMapper.updateKyc(required(userId, "userId"), normalized) != 1) {
            throw new IllegalArgumentException("customer does not exist");
        }
        return customer(userId);
    }

    /** 更新用户生命周期状态。 */
    @Transactional
    public CustomerView changeCustomerStatus(String userId, String status) {
        String normalized = required(status, "status").toUpperCase(Locale.ROOT);
        requireOneOf(normalized, "status", "ACTIVE", "SUSPENDED", "CLOSED");
        if (lifecycleMapper.updateCustomerStatus(required(userId, "userId"), normalized) != 1) {
            throw new IllegalArgumentException("customer does not exist");
        }
        return customer(userId);
    }

    /** 为已经存在的用户创建交易账户。 */
    @Transactional
    public AccountService.AccountView openTradingAccount(String userId, String asset,
                                                         BigDecimal openingBalance) {
        customer(userId);
        return accountService.create(required(userId, "userId"), normalizeAsset(asset),
            "TRADING", openingBalance);
    }

    /** 新建或更新用户风控档案。 */
    @Transactional
    public RiskProfileView configureRisk(String userId, String riskLevel, boolean tradingEnabled,
                                         BigDecimal maxOrderNotional,
                                         BigDecimal maxDailyNotional,
                                         BigDecimal maxPriceDeviation) {
        customer(userId);
        String normalizedRisk = required(riskLevel, "riskLevel").toUpperCase(Locale.ROOT);
        requireOneOf(normalizedRisk, "riskLevel", "LOW", "MEDIUM", "HIGH");
        requirePositive(maxOrderNotional, "maxOrderNotional");
        requirePositive(maxDailyNotional, "maxDailyNotional");
        requirePositive(maxPriceDeviation, "maxPriceDeviation");
        if (maxDailyNotional.compareTo(maxOrderNotional) < 0) {
            throw new IllegalArgumentException("maxDailyNotional must not be lower than maxOrderNotional");
        }
        if (maxPriceDeviation.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("maxPriceDeviation must not exceed 1");
        }
        lifecycleMapper.upsertRiskProfile(userId, normalizedRisk, tradingEnabled,
            maxOrderNotional, maxDailyNotional, maxPriceDeviation);
        return riskProfile(userId);
    }

    /** 查询用户风控档案。 */
    public RiskProfileView riskProfile(String userId) {
        TradingLifecycleMapper.RiskProfileRow row =
            lifecycleMapper.findRiskProfile(required(userId, "userId"));
        if (row == null) {
            throw new IllegalArgumentException("risk profile does not exist");
        }
        return toRisk(row);
    }

    /** 发布单调更新的参考行情。 */
    @Transactional
    public MarketQuoteView publishQuote(String symbol, BigDecimal price, String source,
                                        Instant observedAt) {
        String normalizedSymbol = normalizeSymbol(symbol);
        requirePositive(price, "price");
        String normalizedSource = required(source, "source");
        Instant effectiveObservedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (effectiveObservedAt.isAfter(Instant.now().plusSeconds(5))) {
            throw new IllegalArgumentException("observedAt is too far in the future");
        }
        lifecycleMapper.upsertMarketQuote(normalizedSymbol, price, normalizedSource,
            effectiveObservedAt);
        return quote(normalizedSymbol);
    }

    /** 查询参考价、订单簿和最近成交组成的行情快照。 */
    public MarketView market(String symbol, int depth, int tradeLimit) {
        String normalizedSymbol = normalizeSymbol(symbol);
        MarketQuoteView reference = quote(normalizedSymbol);
        OrderBookView book = matchingService.book(normalizedSymbol, depth);
        List<TradeView> trades = matchingService.recentTrades(normalizedSymbol, tradeLimit);
        return new MarketView(reference, book, trades);
    }

    /** 查询参考行情。 */
    public MarketQuoteView quote(String symbol) {
        TradingLifecycleMapper.MarketQuoteRow row =
            lifecycleMapper.findMarketQuote(normalizeSymbol(symbol));
        if (row == null) {
            throw new IllegalArgumentException("market quote does not exist");
        }
        return toQuote(row);
    }

    /**
     * 在同一数据库事务内完成盘前决定与撮合写入。
     *
     * <p>该方法由 {@link TradingOrderCoordinator} 在交易对有界 Lane 内调用。拒绝属于明确业务结果，
     * 会保存决定但不会创建订单；批准后撮合异常会让决定与订单一起回滚，避免留下“批准但未受理”的假成功。</p>
     */
    @Transactional
    public TradingOrderResult place(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command");
        TradingLifecycleMapper.PreTradeDecisionRow replay =
            lifecycleMapper.findDecision(command.userId(), command.clientOrderId());
        if (replay != null) {
            requireSameRequest(command, replay);
            PreTradeDecisionView decision = toDecision(replay, true);
            MatchingResult matching = "APPROVED".equals(replay.decision())
                ? matchingService.place(command) : null;
            return new TradingOrderResult(decision, matching);
        }

        TradingLifecycleMapper.CustomerRow customer = lifecycleMapper.findCustomer(command.userId());
        if (customer == null) {
            return reject(command, null, null, null, "USER_NOT_FOUND", "用户不存在");
        }
        if (!"ACTIVE".equals(customer.status())) {
            return reject(command, null, null, null, "USER_NOT_ACTIVE", "用户不是可交易状态");
        }
        if (!"VERIFIED".equals(customer.kycStatus())) {
            return reject(command, null, null, null, "KYC_NOT_VERIFIED", "KYC 尚未通过");
        }

        // 按用户锁定风控档案，使并发订单不能同时越过日累计额度。
        TradingLifecycleMapper.RiskProfileRow risk =
            lifecycleMapper.lockRiskProfile(command.userId());
        if (risk == null) {
            return reject(command, null, null, null,
                "RISK_PROFILE_MISSING", "用户尚未配置风控档案");
        }
        if (!risk.tradingEnabled()) {
            return reject(command, null, null, null,
                "TRADING_DISABLED", "风控已关闭该用户交易权限");
        }
        if (command.type() == OrderType.MARKET) {
            return reject(command, null, null, null,
                "MARKET_ORDER_REQUIRES_PROTECTION", "受控入口暂不接受无价格保护的市价单");
        }

        TradingLifecycleMapper.MarketQuoteRow quote =
            lifecycleMapper.findMarketQuote(command.symbol());
        if (quote == null) {
            return reject(command, null, null, null,
                "MARKET_QUOTE_MISSING", "交易对缺少参考行情");
        }
        if (quote.observedAt().isBefore(Instant.now().minus(MAX_QUOTE_AGE))) {
            return reject(command, quote.price(), null, null,
                "MARKET_QUOTE_STALE", "参考行情超过 30 秒，停止接单");
        }

        BigDecimal orderNotional = MatchingPolicy.quoteAmount(command.price(), command.quantity());
        BigDecimal deviation = command.price().subtract(quote.price()).abs()
            .divide(quote.price(), 18, RoundingMode.HALF_UP);
        if (deviation.compareTo(risk.maxPriceDeviation()) > 0) {
            return reject(command, quote.price(), orderNotional, null,
                "PRICE_DEVIATION", "委托价格偏离参考价超过用户风控阈值");
        }
        if (orderNotional.compareTo(risk.maxOrderNotional()) > 0) {
            return reject(command, quote.price(), orderNotional, null,
                "SINGLE_ORDER_LIMIT", "订单名义金额超过单笔限额");
        }

        BigDecimal approvedToday = lifecycleMapper.sumApprovedNotionalToday(command.userId());
        if (approvedToday.add(orderNotional).compareTo(risk.maxDailyNotional()) > 0) {
            return reject(command, quote.price(), orderNotional, null,
                "DAILY_LIMIT", "订单会使当日累计批准金额超过限额");
        }

        String[] assets = command.symbol().split("-", 2);
        String requiredAsset = command.side() == OrderSide.BUY ? assets[1] : assets[0];
        BigDecimal requiredBalance = command.side() == OrderSide.BUY
            ? orderNotional : command.quantity();
        TradingLifecycleMapper.TradingAccountRow account =
            lifecycleMapper.findTradingAccount(command.userId(), requiredAsset);
        if (account == null) {
            return reject(command, quote.price(), orderNotional, null,
                "ACCOUNT_MISSING", "用户缺少下单所需资产的交易账户");
        }
        if (account.balance().compareTo(requiredBalance) < 0) {
            return reject(command, quote.price(), orderNotional, account.accountId(),
                "INSUFFICIENT_BALANCE", "交易账户余额不足");
        }

        PreTradeDecisionView decision = saveDecision(command, quote.price(), orderNotional,
            account.accountId(), "APPROVED", "PASS", "用户、KYC、风控、账户和行情检查全部通过");
        approvedCounter.increment();
        MatchingResult matching = matchingService.place(command);
        return new TradingOrderResult(decision, matching);
    }

    /** 保存拒绝决定并返回不包含订单的业务结果。 */
    private TradingOrderResult reject(PlaceOrderCommand command, BigDecimal referencePrice,
                                      BigDecimal orderNotional, UUID accountId,
                                      String reasonCode, String detail) {
        PreTradeDecisionView decision = saveDecision(command, referencePrice, orderNotional,
            accountId, "REJECTED", reasonCode, detail);
        rejectedCounter.increment();
        return new TradingOrderResult(decision, null);
    }

    /** 保存不可覆盖的盘前决定。 */
    private PreTradeDecisionView saveDecision(PlaceOrderCommand command,
                                              BigDecimal referencePrice,
                                              BigDecimal orderNotional,
                                              UUID accountId,
                                              String decision,
                                              String reasonCode,
                                              String detail) {
        UUID decisionId = UUID.randomUUID();
        lifecycleMapper.insertDecision(decisionId, command.userId(), command.clientOrderId(),
            command.symbol(), command.side().name(), command.type().name(), command.price(),
            command.quantity(), referencePrice, orderNotional, accountId, decision,
            reasonCode, detail);
        return toDecision(lifecycleMapper.findDecision(command.userId(), command.clientOrderId()),
            false);
    }

    /** 验证盘前决定重放没有偷换订单参数。 */
    private static void requireSameRequest(PlaceOrderCommand command,
                                           TradingLifecycleMapper.PreTradeDecisionRow existing) {
        boolean same = command.symbol().equals(existing.symbol())
            && command.side().name().equals(existing.side())
            && command.type().name().equals(existing.orderType())
            && sameAmount(command.price(), existing.limitPrice())
            && sameAmount(command.quantity(), existing.quantity());
        if (!same) {
            throw new IllegalArgumentException("clientOrderId was reused with different order data");
        }
    }

    /** 比较允许为空的精确金额。 */
    private static boolean sameAmount(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    /** 将用户持久化记录转换为只读视图。 */
    private static CustomerView toCustomer(TradingLifecycleMapper.CustomerRow row) {
        return new CustomerView(row.userId(), row.displayName(), row.countryCode(), row.status(),
            row.kycStatus(), row.createdAt(), row.updatedAt());
    }

    /** 将风控持久化记录转换为只读视图。 */
    private static RiskProfileView toRisk(TradingLifecycleMapper.RiskProfileRow row) {
        return new RiskProfileView(row.userId(), row.riskLevel(), row.tradingEnabled(),
            row.maxOrderNotional(), row.maxDailyNotional(), row.maxPriceDeviation(), row.version());
    }

    /** 将参考行情记录转换为只读视图。 */
    private static MarketQuoteView toQuote(TradingLifecycleMapper.MarketQuoteRow row) {
        return new MarketQuoteView(row.symbol(), row.price(), row.source(), row.observedAt(),
            row.version());
    }

    /** 将盘前决定记录转换为只读视图。 */
    private static PreTradeDecisionView toDecision(
        TradingLifecycleMapper.PreTradeDecisionRow row, boolean duplicate) {
        return new PreTradeDecisionView(row.decisionId(), row.userId(), row.clientOrderId(),
            row.symbol(), row.side(), row.orderType(), row.limitPrice(), row.quantity(),
            row.referencePrice(), row.orderNotional(), row.accountId(), row.decision(),
            row.reasonCode(), row.reasonDetail(), row.createdAt(), duplicate);
    }

    /** 规范化交易对。 */
    private static String normalizeSymbol(String symbol) {
        String normalized = required(symbol, "symbol").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,20}-[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("symbol must use BASE-QUOTE format");
        }
        return normalized;
    }

    /** 规范化资产代码。 */
    private static String normalizeAsset(String asset) {
        String normalized = required(asset, "asset").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("asset format is invalid");
        }
        return normalized;
    }

    /** 校验必填文本。 */
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    /** 校验正数金额。 */
    private static void requirePositive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    /** 校验枚举文本属于允许集合。 */
    private static void requireOneOf(String value, String field, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equals(value)) {
                return;
            }
        }
        throw new IllegalArgumentException(field + " is invalid");
    }

    /** 用户只读视图。 */
    public record CustomerView(String userId, String displayName, String countryCode,
                               String status, String kycStatus,
                               Instant createdAt, Instant updatedAt) {
    }

    /** 风控配置只读视图。 */
    public record RiskProfileView(String userId, String riskLevel, boolean tradingEnabled,
                                  BigDecimal maxOrderNotional, BigDecimal maxDailyNotional,
                                  BigDecimal maxPriceDeviation, long version) {
    }

    /** 参考行情只读视图。 */
    public record MarketQuoteView(String symbol, BigDecimal price, String source,
                                  Instant observedAt, long version) {
    }

    /** 参考价、订单簿和最近成交组成的行情视图。 */
    public record MarketView(MarketQuoteView reference, OrderBookView orderBook,
                             List<TradeView> recentTrades) {
        /** 固化最近成交列表，避免调用方修改返回值。 */
        public MarketView {
            recentTrades = List.copyOf(recentTrades);
        }
    }

    /** 可审计盘前决定视图。 */
    public record PreTradeDecisionView(UUID decisionId, String userId, String clientOrderId,
                                       String symbol, String side, String orderType,
                                       BigDecimal limitPrice, BigDecimal quantity,
                                       BigDecimal referencePrice, BigDecimal orderNotional,
                                       UUID accountId, String decision, String reasonCode,
                                       String reasonDetail, Instant createdAt, boolean duplicate) {
    }

    /** 受控下单结果；拒绝时 {@code matching} 为空且不会创建订单。 */
    public record TradingOrderResult(PreTradeDecisionView preTradeDecision,
                                     MatchingResult matching) {
    }
}
