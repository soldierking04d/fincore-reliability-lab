package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用户、风控、参考行情和盘前决策的 MyBatis 持久化接口。
 *
 * <p>风控档案通过 {@code FOR UPDATE} 按用户串行校验日累计额度；参考行情只允许较新的观察时间覆盖
 * 旧快照；盘前决定使用用户和客户端订单号作为唯一业务键，保证重试不会重复消耗额度。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.2.0
 */
public interface TradingLifecycleMapper {
    /** 创建用户，重复用户编号由数据库唯一约束拒绝。 */
    @Insert("""
        INSERT INTO customer_profile(user_id, display_name, country_code)
        VALUES (#{userId}, #{displayName}, #{countryCode})
        """)
    int insertCustomer(@Param("userId") String userId,
                       @Param("displayName") String displayName,
                       @Param("countryCode") String countryCode);

    /** 查询用户当前状态。 */
    @Select("""
        SELECT user_id AS "userId", display_name AS "displayName", country_code AS "countryCode",
               status, kyc_status AS "kycStatus", created_at AS "createdAt", updated_at AS "updatedAt"
        FROM customer_profile
        WHERE user_id=#{userId}
        """)
    CustomerRow findCustomer(@Param("userId") String userId);

    /** 更新 KYC 审核结果。 */
    @Update("""
        UPDATE customer_profile
        SET kyc_status=#{kycStatus}, updated_at=now()
        WHERE user_id=#{userId}
        """)
    int updateKyc(@Param("userId") String userId, @Param("kycStatus") String kycStatus);

    /** 更新用户生命周期状态。 */
    @Update("""
        UPDATE customer_profile
        SET status=#{status}, updated_at=now()
        WHERE user_id=#{userId}
        """)
    int updateCustomerStatus(@Param("userId") String userId, @Param("status") String status);

    /** 新建或更新用户风控档案。 */
    @Insert("""
        INSERT INTO risk_profile(
            user_id, risk_level, trading_enabled, max_order_notional,
            max_daily_notional, max_price_deviation
        ) VALUES (
            #{userId}, #{riskLevel}, #{tradingEnabled}, #{maxOrderNotional},
            #{maxDailyNotional}, #{maxPriceDeviation}
        )
        ON CONFLICT (user_id) DO UPDATE SET
            risk_level=EXCLUDED.risk_level,
            trading_enabled=EXCLUDED.trading_enabled,
            max_order_notional=EXCLUDED.max_order_notional,
            max_daily_notional=EXCLUDED.max_daily_notional,
            max_price_deviation=EXCLUDED.max_price_deviation,
            version=risk_profile.version + 1,
            updated_at=now()
        """)
    int upsertRiskProfile(@Param("userId") String userId,
                          @Param("riskLevel") String riskLevel,
                          @Param("tradingEnabled") boolean tradingEnabled,
                          @Param("maxOrderNotional") BigDecimal maxOrderNotional,
                          @Param("maxDailyNotional") BigDecimal maxDailyNotional,
                          @Param("maxPriceDeviation") BigDecimal maxPriceDeviation);

    /** 查询风控档案。 */
    @Select("""
        SELECT user_id AS "userId", risk_level AS "riskLevel",
               trading_enabled AS "tradingEnabled",
               max_order_notional AS "maxOrderNotional",
               max_daily_notional AS "maxDailyNotional",
               max_price_deviation AS "maxPriceDeviation", version
        FROM risk_profile
        WHERE user_id=#{userId}
        """)
    RiskProfileRow findRiskProfile(@Param("userId") String userId);

    /** 锁定风控档案，使同一用户的并发订单按确定顺序消耗日累计额度。 */
    @Select("""
        SELECT user_id AS "userId", risk_level AS "riskLevel",
               trading_enabled AS "tradingEnabled",
               max_order_notional AS "maxOrderNotional",
               max_daily_notional AS "maxDailyNotional",
               max_price_deviation AS "maxPriceDeviation", version
        FROM risk_profile
        WHERE user_id=#{userId}
        FOR UPDATE
        """)
    RiskProfileRow lockRiskProfile(@Param("userId") String userId);

    /** 只允许相同或更新的观察时间写入参考行情。 */
    @Insert("""
        INSERT INTO market_reference_price(symbol, price, source, observed_at)
        VALUES (#{symbol}, #{price}, #{source}, #{observedAt})
        ON CONFLICT (symbol) DO UPDATE SET
            price=EXCLUDED.price,
            source=EXCLUDED.source,
            observed_at=EXCLUDED.observed_at,
            version=market_reference_price.version + 1,
            updated_at=now()
        WHERE EXCLUDED.observed_at >= market_reference_price.observed_at
        """)
    int upsertMarketQuote(@Param("symbol") String symbol,
                          @Param("price") BigDecimal price,
                          @Param("source") String source,
                          @Param("observedAt") Instant observedAt);

    /** 查询交易对参考行情。 */
    @Select("""
        SELECT symbol, price, source, observed_at AS "observedAt", version
        FROM market_reference_price
        WHERE symbol=#{symbol}
        """)
    MarketQuoteRow findMarketQuote(@Param("symbol") String symbol);

    /** 查询用户某资产的交易账户。 */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset,
               account_type AS "accountType", balance, version
        FROM account
        WHERE owner_id=#{userId} AND asset=#{asset} AND account_type='TRADING'
        """)
    TradingAccountRow findTradingAccount(@Param("userId") String userId,
                                         @Param("asset") String asset);

    /** 查询用户当天已经批准的订单名义金额。 */
    @Select("""
        SELECT COALESCE(SUM(order_notional), 0)
        FROM pre_trade_decision
        WHERE user_id=#{userId}
          AND decision='APPROVED'
          AND created_at >= date_trunc('day', now())
        """)
    BigDecimal sumApprovedNotionalToday(@Param("userId") String userId);

    /** 按幂等业务键查询既有盘前决定。 */
    @Select("""
        SELECT decision_id AS "decisionId", user_id AS "userId",
               client_order_id AS "clientOrderId", symbol, side,
               order_type AS "orderType", limit_price AS "limitPrice", quantity,
               reference_price AS "referencePrice", order_notional AS "orderNotional",
               account_id AS "accountId", decision, reason_code AS "reasonCode",
               reason_detail AS "reasonDetail", created_at AS "createdAt"
        FROM pre_trade_decision
        WHERE user_id=#{userId} AND client_order_id=#{clientOrderId}
        """)
    PreTradeDecisionRow findDecision(@Param("userId") String userId,
                                     @Param("clientOrderId") String clientOrderId);

    /** 保存一条不可覆盖的盘前决定。 */
    @Insert("""
        INSERT INTO pre_trade_decision(
            decision_id, user_id, client_order_id, symbol, side, order_type,
            limit_price, quantity, reference_price, order_notional, account_id,
            decision, reason_code, reason_detail
        ) VALUES (
            #{decisionId}, #{userId}, #{clientOrderId}, #{symbol}, #{side}, #{orderType},
            #{limitPrice}, #{quantity}, #{referencePrice}, #{orderNotional}, #{accountId},
            #{decision}, #{reasonCode}, #{reasonDetail}
        )
        """)
    int insertDecision(@Param("decisionId") UUID decisionId,
                       @Param("userId") String userId,
                       @Param("clientOrderId") String clientOrderId,
                       @Param("symbol") String symbol,
                       @Param("side") String side,
                       @Param("orderType") String orderType,
                       @Param("limitPrice") BigDecimal limitPrice,
                       @Param("quantity") BigDecimal quantity,
                       @Param("referencePrice") BigDecimal referencePrice,
                       @Param("orderNotional") BigDecimal orderNotional,
                       @Param("accountId") UUID accountId,
                       @Param("decision") String decision,
                       @Param("reasonCode") String reasonCode,
                       @Param("reasonDetail") String reasonDetail);

    /** 用户状态持久化快照。 */
    record CustomerRow(String userId, String displayName, String countryCode, String status,
                       String kycStatus, Instant createdAt, Instant updatedAt) {
    }

    /** 风控配置持久化快照。 */
    record RiskProfileRow(String userId, String riskLevel, boolean tradingEnabled,
                          BigDecimal maxOrderNotional, BigDecimal maxDailyNotional,
                          BigDecimal maxPriceDeviation, long version) {
    }

    /** 参考行情持久化快照。 */
    record MarketQuoteRow(String symbol, BigDecimal price, String source,
                          Instant observedAt, long version) {
    }

    /** 交易账户盘前检查快照。 */
    record TradingAccountRow(UUID accountId, String ownerId, String asset,
                             String accountType, BigDecimal balance, long version) {
    }

    /** 盘前决定持久化快照。 */
    record PreTradeDecisionRow(UUID decisionId, String userId, String clientOrderId,
                               String symbol, String side, String orderType,
                               BigDecimal limitPrice, BigDecimal quantity,
                               BigDecimal referencePrice, BigDecimal orderNotional,
                               UUID accountId, String decision, String reasonCode,
                               String reasonDetail, Instant createdAt) {
    }
}
