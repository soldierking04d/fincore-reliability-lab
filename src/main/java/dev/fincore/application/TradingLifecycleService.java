package dev.fincore.application;

import dev.fincore.domain.MatchingPolicy;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderBookView;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.domain.TradingIdentifiers;
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
 * <p><strong>解决的问题：</strong>让订单在进入撮合前同时满足用户资格、KYC、交易权限、行情新鲜度、
 * 价格笼子、单笔/日累计额度和账户余额，并留下不可覆盖的盘前决定。</p>
 *
 * <p><strong>执行与 CPU：</strong>请求已经在交易对 Lane 内受控，本类不再创建异步任务。只读取一份
 * 用户/风控/参考价/账户快照，金额和偏离计算使用 BigDecimal；用户风险行锁负责同用户并发额度，
 * 避免先在 JVM 汇总再写数据库产生竞态。静态格式校验放在加锁前，缩短持锁时间。</p>
 *
 * <p><strong>事务边界：</strong>批准决定、撮合订单和资金预占共享事务；相同
 * {@code userId + clientOrderId} 重试复用原决定和原订单，不会再次消耗额度。成交转在途后由有效
 * Worker 通过 Kafka 执行双资产交割。</p>
 *
 * <p><strong>范围边界：</strong>本模型仍是不含手续费的限价现货实验，不宣称具备完整生产认证、
 * 外部 KYC/行情供应商或真实资金通道。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.2.0
 */
@Service
public class TradingLifecycleService {
    /** 参考行情最大允许年龄。 */
    private static final Duration MAX_QUOTE_AGE = Duration.ofSeconds(30);
    /** 参考行情允许领先本机时间的最大偏差。 */
    private static final Duration MAX_QUOTE_FUTURE_SKEW = Duration.ofSeconds(5);
    /** 用户编号允许的字符范围和长度。 */
    private static final String USER_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{1,99}";
    /** ISO 3166-1 alpha-2 国家代码长度。 */
    private static final int COUNTRY_CODE_LENGTH = 2;
    /** 参数校验错误中使用的用户编号字段名。 */
    private static final String USER_ID_FIELD = "userId";
    /** 可进入交易准入流程的用户状态。 */
    private static final String CUSTOMER_ACTIVE = "ACTIVE";
    /** 已完成 KYC 的状态。 */
    private static final String KYC_VERIFIED = "VERIFIED";
    /** 用户、风控、行情和盘前决定持久化接口。 */
    private final TradingLifecycleMapper lifecycleMapper;
    /** 账户生命周期服务。 */
    private final AccountService accountService;
    /** 已有确定性撮合服务。 */
    private final MatchingService matchingService;
    /** 现货资金精度校验和分桶服务。 */
    private final SpotFundsService spotFunds;
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
                                   MeterRegistry meterRegistry, SpotFundsService spotFunds) {
        this.lifecycleMapper = lifecycleMapper;
        this.accountService = accountService;
        this.matchingService = matchingService;
        this.spotFunds = spotFunds;
        this.approvedCounter = meterRegistry.counter("fincore.pretrade.decisions", "decision", "approved");
        this.rejectedCounter = meterRegistry.counter("fincore.pretrade.decisions", "decision", "rejected");
    }

    /** 创建待 KYC 审核的用户。 */
    @Transactional(rollbackFor = Exception.class)
    public CustomerView registerCustomer(String userId, String displayName, String countryCode) {
        String normalizedUserId = required(userId, USER_ID_FIELD);
        String normalizedName = required(displayName, "displayName");
        String normalizedCountry = required(countryCode, "countryCode").toUpperCase(Locale.ROOT);
        if (!normalizedUserId.matches(USER_ID_PATTERN)) {
            throw new IllegalArgumentException("userId format is invalid");
        }
        if (normalizedCountry.length() != COUNTRY_CODE_LENGTH) {
            throw new IllegalArgumentException("countryCode must contain two letters");
        }
        lifecycleMapper.insertCustomer(normalizedUserId, normalizedName, normalizedCountry);
        return customer(normalizedUserId);
    }

    /** 查询用户当前状态。 */
    public CustomerView customer(String userId) {
        TradingLifecycleMapper.CustomerRow row = lifecycleMapper.findCustomer(
            required(userId, USER_ID_FIELD));
        if (row == null) {
            throw new IllegalArgumentException("customer does not exist");
        }
        return toCustomer(row);
    }

    /** 更新 KYC 审核结果。 */
    @Transactional(rollbackFor = Exception.class)
    public CustomerView reviewKyc(String userId, String kycStatus) {
        String normalized = required(kycStatus, "kycStatus").toUpperCase(Locale.ROOT);
        requireOneOf(normalized, "kycStatus", "PENDING", "VERIFIED", "REJECTED");
        if (lifecycleMapper.updateKyc(required(userId, USER_ID_FIELD), normalized) != 1) {
            throw new IllegalArgumentException("customer does not exist");
        }
        return customer(userId);
    }

    /** 更新用户生命周期状态。 */
    @Transactional(rollbackFor = Exception.class)
    public CustomerView changeCustomerStatus(String userId, String status) {
        String normalized = required(status, "status").toUpperCase(Locale.ROOT);
        requireOneOf(normalized, "status", "ACTIVE", "SUSPENDED", "CLOSED");
        if (lifecycleMapper.updateCustomerStatus(required(userId, USER_ID_FIELD), normalized) != 1) {
            throw new IllegalArgumentException("customer does not exist");
        }
        return customer(userId);
    }

    /** 为已经存在的用户创建交易账户。 */
    @Transactional(rollbackFor = Exception.class)
    public AccountService.AccountView openTradingAccount(String userId, String asset,
                                                         BigDecimal openingBalance) {
        customer(userId);
        return accountService.create(required(userId, USER_ID_FIELD), normalizeAsset(asset),
            "TRADING", openingBalance);
    }

    /** 新建或更新用户风控档案。 */
    @Transactional(rollbackFor = Exception.class)
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
            lifecycleMapper.findRiskProfile(required(userId, USER_ID_FIELD));
        if (row == null) {
            throw new IllegalArgumentException("risk profile does not exist");
        }
        return toRisk(row);
    }

    /** 发布单调更新的参考行情。 */
    @Transactional(rollbackFor = Exception.class)
    public MarketQuoteView publishQuote(String symbol, BigDecimal price, String source,
                                        Instant observedAt) {
        String normalizedSymbol = normalizeSymbol(symbol);
        requirePositive(price, "price");
        String normalizedSource = required(source, "source");
        Instant effectiveObservedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (effectiveObservedAt.isAfter(Instant.now().plus(MAX_QUOTE_FUTURE_SKEW))) {
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
    @Transactional(rollbackFor = Exception.class)
    public TradingOrderResult place(PlaceOrderCommand command) {
        Objects.requireNonNull(command, "command");
        spotFunds.validate(command);
        lifecycleMapper.lockRequest(command.userId(), command.clientOrderId());
        matchingService.lockForTrading(command.symbol());
        TradingLifecycleMapper.PreTradeDecisionRow replay =
            lifecycleMapper.findDecision(command.userId(), command.clientOrderId());
        if (replay != null) {
            requireSameRequest(command, replay);
            PreTradeDecisionView decision = toDecision(replay, true);
            MatchingResult matching = "APPROVED".equals(replay.decision())
                ? matchingService.placeFunded(command) : null;
            return new TradingOrderResult(decision, matching);
        }

        CustomerRiskCheck customerRisk = checkCustomerAndRisk(command);
        if (customerRisk.rejection() != null) {
            return customerRisk.rejection();
        }
        MarketFundsCheck marketFunds = checkMarketAndFunds(command, customerRisk.risk());
        if (marketFunds.rejection() != null) {
            return marketFunds.rejection();
        }

        PreTradeDecisionView decision = saveDecision(command, marketFunds.quote().price(),
            marketFunds.orderNotional(), marketFunds.accountId(), "APPROVED", "PASS",
            "用户、KYC、风控、账户和行情检查全部通过");
        approvedCounter.increment();
        MatchingResult matching = matchingService.placeFunded(command);
        return new TradingOrderResult(decision, matching);
    }

    /** 按用户锁定风控档案，并完成不依赖行情和余额的准入检查。 */
    private CustomerRiskCheck checkCustomerAndRisk(PlaceOrderCommand command) {
        TradingLifecycleMapper.CustomerRow customer = lifecycleMapper.findCustomer(command.userId());
        if (customer == null) {
            return CustomerRiskCheck.reject(
                reject(command, null, null, null, "USER_NOT_FOUND", "用户不存在"));
        }
        if (!CUSTOMER_ACTIVE.equals(customer.status())) {
            return CustomerRiskCheck.reject(
                reject(command, null, null, null, "USER_NOT_ACTIVE", "用户不是可交易状态"));
        }
        if (!KYC_VERIFIED.equals(customer.kycStatus())) {
            return CustomerRiskCheck.reject(
                reject(command, null, null, null, "KYC_NOT_VERIFIED", "KYC 尚未通过"));
        }
        // 行锁使同一用户的并发订单不能同时越过日累计额度。
        TradingLifecycleMapper.RiskProfileRow risk =
            lifecycleMapper.lockRiskProfile(command.userId());
        if (risk == null) {
            return CustomerRiskCheck.reject(reject(command, null, null, null,
                "RISK_PROFILE_MISSING", "用户尚未配置风控档案"));
        }
        if (!risk.tradingEnabled()) {
            return CustomerRiskCheck.reject(reject(command, null, null, null,
                "TRADING_DISABLED", "风控已关闭该用户交易权限"));
        }
        if (command.type() == OrderType.MARKET) {
            return CustomerRiskCheck.reject(reject(command, null, null, null,
                "MARKET_ORDER_REQUIRES_PROTECTION", "受控入口暂不接受无价格保护的市价单"));
        }
        return CustomerRiskCheck.approve(risk);
    }

    /** 检查行情新鲜度、价格和额度限制，并读取实际用于预占的资金账户。 */
    private MarketFundsCheck checkMarketAndFunds(
        PlaceOrderCommand command, TradingLifecycleMapper.RiskProfileRow risk) {
        TradingLifecycleMapper.MarketQuoteRow quote =
            lifecycleMapper.findMarketQuote(command.symbol());
        if (quote == null) {
            return MarketFundsCheck.reject(reject(command, null, null, null,
                "MARKET_QUOTE_MISSING", "交易对缺少参考行情"));
        }
        if (quote.observedAt().isBefore(Instant.now().minus(MAX_QUOTE_AGE))) {
            return MarketFundsCheck.reject(reject(command, quote.price(), null, null,
                "MARKET_QUOTE_STALE", "参考行情超过 30 秒，停止接单"));
        }
        BigDecimal orderNotional = MatchingPolicy.quoteAmount(command.price(), command.quantity());
        BigDecimal deviation = command.price().subtract(quote.price()).abs()
            .divide(quote.price(), 18, RoundingMode.HALF_UP);
        if (deviation.compareTo(risk.maxPriceDeviation()) > 0) {
            return MarketFundsCheck.reject(reject(command, quote.price(), orderNotional, null,
                "PRICE_DEVIATION", "委托价格偏离参考价超过用户风控阈值"));
        }
        if (orderNotional.compareTo(risk.maxOrderNotional()) > 0) {
            return MarketFundsCheck.reject(reject(command, quote.price(), orderNotional, null,
                "SINGLE_ORDER_LIMIT", "订单名义金额超过单笔限额"));
        }
        BigDecimal approvedToday = lifecycleMapper.sumApprovedNotionalToday(command.userId());
        if (approvedToday.add(orderNotional).compareTo(risk.maxDailyNotional()) > 0) {
            return MarketFundsCheck.reject(reject(command, quote.price(), orderNotional, null,
                "DAILY_LIMIT", "订单会使当日累计批准金额超过限额"));
        }
        return checkFundingAccount(command, quote, orderNotional);
    }

    /** 验证订单需要的资产账户存在、未冻结且扣除预占后余额充足。 */
    private MarketFundsCheck checkFundingAccount(
        PlaceOrderCommand command, TradingLifecycleMapper.MarketQuoteRow quote,
        BigDecimal orderNotional) {
        String[] assets = command.symbol().split("-", 2);
        String requiredAsset = command.side() == OrderSide.BUY ? assets[1] : assets[0];
        BigDecimal requiredBalance = command.side() == OrderSide.BUY
            ? orderNotional : command.quantity();
        TradingLifecycleMapper.TradingAccountRow account =
            lifecycleMapper.findTradingAccount(command.userId(), requiredAsset);
        if (account == null) {
            return MarketFundsCheck.reject(reject(command, quote.price(), orderNotional, null,
                "ACCOUNT_MISSING", "用户缺少下单所需资产的交易账户"));
        }
        var money = spotFunds.view(account.accountId());
        if (money.financialHold()) {
            return MarketFundsCheck.reject(reject(command, quote.price(), orderNotional,
                account.accountId(), "ACCOUNT_FROZEN", "资金对账存在待复核问题"));
        }
        if (money.available().compareTo(requiredBalance) < 0) {
            return MarketFundsCheck.reject(reject(command, quote.price(), orderNotional,
                account.accountId(), "INSUFFICIENT_BALANCE",
                "交易账户可用余额不足（已扣除委托预占与成交在途）"));
        }
        return MarketFundsCheck.approve(quote, orderNotional, account.accountId());
    }

    /** 用户及风控阶段的唯一一种成功或拒绝结果。 */
    private record CustomerRiskCheck(TradingLifecycleMapper.RiskProfileRow risk,
                                     TradingOrderResult rejection) {
        private static CustomerRiskCheck approve(TradingLifecycleMapper.RiskProfileRow risk) {
            return new CustomerRiskCheck(risk, null);
        }

        private static CustomerRiskCheck reject(TradingOrderResult rejection) {
            return new CustomerRiskCheck(null, rejection);
        }
    }

    /** 行情、额度和资金账户阶段的唯一一种成功或拒绝结果。 */
    private record MarketFundsCheck(TradingLifecycleMapper.MarketQuoteRow quote,
                                    BigDecimal orderNotional, UUID accountId,
                                    TradingOrderResult rejection) {
        private static MarketFundsCheck approve(TradingLifecycleMapper.MarketQuoteRow quote,
                                                BigDecimal orderNotional, UUID accountId) {
            return new MarketFundsCheck(quote, orderNotional, accountId, null);
        }

        private static MarketFundsCheck reject(TradingOrderResult rejection) {
            return new MarketFundsCheck(null, null, null, rejection);
        }
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
        if (!TradingIdentifiers.isSymbol(normalized)) {
            throw new IllegalArgumentException("symbol must use BASE-QUOTE format");
        }
        return normalized;
    }

    /** 规范化资产代码。 */
    private static String normalizeAsset(String asset) {
        String normalized = required(asset, "asset").toUpperCase(Locale.ROOT);
        if (!TradingIdentifiers.isAsset(normalized)) {
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
