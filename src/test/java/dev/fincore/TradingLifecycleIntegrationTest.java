package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.TradingLifecycleService;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 用户、KYC、风控、账户、行情与撮合完整链路的集成测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.2.0
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.task.scheduling.enabled=false"
})
class TradingLifecycleIntegrationTest {
    /** PostgreSQL 16 测试容器。 */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 将 Spring 数据源连接到隔离测试容器。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** 完整交易生命周期服务。 */
    @Autowired TradingLifecycleService trading;
    /** 用于核对数据库唯一结果的测试查询工具。 */
    @Autowired JdbcTemplate jdbc;

    /** 验证两个用户通过完整盘前链路后形成唯一成交。 */
    @Test
    void verifiedUsersPassRiskAccountMarketAndMatchingChain() {
        prepareUser("journey-seller", "BTC", "10");
        prepareUser("journey-buyer", "USDT", "1000");
        trading.publishQuote("BTC-USDT", new BigDecimal("100"),
            "LAB-CONSOLIDATED", Instant.now());

        var maker = trading.place(limit("journey-sell-1", "journey-seller", "BTC-USDT",
            OrderSide.SELL, "100", "2"));
        assertEquals("APPROVED", maker.preTradeDecision().decision());
        assertEquals(OrderStatus.OPEN, maker.matching().order().status());

        PlaceOrderCommand buy = limit("journey-buy-1", "journey-buyer", "BTC-USDT",
            OrderSide.BUY, "100", "2");
        var taker = trading.place(buy);
        assertEquals("APPROVED", taker.preTradeDecision().decision());
        assertEquals(OrderStatus.FILLED, taker.matching().order().status());
        assertEquals(1, taker.matching().trades().size());

        var replay = trading.place(buy);
        assertTrue(replay.preTradeDecision().duplicate());
        assertTrue(replay.matching().order().duplicate());
        assertEquals(taker.preTradeDecision().decisionId(), replay.preTradeDecision().decisionId());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM pre_trade_decision
            WHERE user_id='journey-buyer' AND client_order_id='journey-buy-1'
            """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM matching_order
            WHERE user_id='journey-buyer' AND client_order_id='journey-buy-1'
            """, Integer.class));

        var market = trading.market("BTC-USDT", 20, 20);
        assertEquals(0, market.reference().price().compareTo(new BigDecimal("100")));
        assertEquals(1, market.recentTrades().size());
    }

    /** 验证 KYC 和价格笼子拒绝会留痕但不会创建订单。 */
    @Test
    void rejectedDecisionIsAuditableAndCannotBeChangedOnReplay() {
        trading.registerCustomer("journey-pending", "待审核用户", "CN");
        trading.openTradingAccount("journey-pending", "USDT", new BigDecimal("1000"));
        trading.publishQuote("RISK-USDT", new BigDecimal("100"), "LAB", Instant.now());

        PlaceOrderCommand pending = limit("pending-1", "journey-pending", "RISK-USDT",
            OrderSide.BUY, "100", "1");
        var rejected = trading.place(pending);
        assertEquals("REJECTED", rejected.preTradeDecision().decision());
        assertEquals("KYC_NOT_VERIFIED", rejected.preTradeDecision().reasonCode());
        assertNull(rejected.matching());
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM matching_order
            WHERE user_id='journey-pending' AND client_order_id='pending-1'
            """, Integer.class));

        var replay = trading.place(pending);
        assertTrue(replay.preTradeDecision().duplicate());
        assertNull(replay.matching());
        assertThrows(IllegalArgumentException.class, () -> trading.place(limit(
            "pending-1", "journey-pending", "RISK-USDT", OrderSide.BUY, "101", "1")));

        prepareUser("journey-price", "USDT", "1000");
        var priceRejected = trading.place(limit("price-1", "journey-price", "RISK-USDT",
            OrderSide.BUY, "150", "1"));
        assertEquals("PRICE_DEVIATION", priceRejected.preTradeDecision().reasonCode());
        assertNotNull(priceRejected.preTradeDecision().orderNotional());
        assertNull(priceRejected.matching());
    }

    /** 验证过期行情会关闭下单而不是沿用陈旧价格。 */
    @Test
    void staleMarketQuoteFailsClosed() {
        prepareUser("journey-stale", "USDT", "1000");
        trading.publishQuote("STALE-USDT", new BigDecimal("10"),
            "DELAYED-FEED", Instant.now().minusSeconds(31));

        var result = trading.place(limit("stale-1", "journey-stale", "STALE-USDT",
            OrderSide.BUY, "10", "1"));

        assertEquals("REJECTED", result.preTradeDecision().decision());
        assertEquals("MARKET_QUOTE_STALE", result.preTradeDecision().reasonCode());
        assertNull(result.matching());
    }

    /** 创建已通过 KYC、已开户并启用风控的用户。 */
    private void prepareUser(String userId, String asset, String openingBalance) {
        trading.registerCustomer(userId, userId, "CN");
        trading.reviewKyc(userId, "VERIFIED");
        trading.openTradingAccount(userId, asset, new BigDecimal(openingBalance));
        trading.configureRisk(userId, "LOW", true, new BigDecimal("10000"),
            new BigDecimal("20000"), new BigDecimal("0.20"));
    }

    /** 构造限价单命令。 */
    private static PlaceOrderCommand limit(String clientOrderId, String userId, String symbol,
                                           OrderSide side, String price, String quantity) {
        return new PlaceOrderCommand(clientOrderId, userId, symbol, side, OrderType.LIMIT,
            new BigDecimal(price), new BigDecimal(quantity));
    }
}
