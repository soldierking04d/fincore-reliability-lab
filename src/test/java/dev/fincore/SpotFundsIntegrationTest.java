package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.fincore.application.MatchingService;
import dev.fincore.application.TradingLifecycleService;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
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
 * 委托预占、在途和双资产交割的数据库验收；先建立防止余额重复使用的失败测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.task.scheduling.enabled=false"
})
class SpotFundsIntegrationTest {
    /** 每个套件使用独立数据库，不接触线上资金。 */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 注入测试容器连接。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** 受控交易入口。 */
    @Autowired TradingLifecycleService trading;
    /** 原有撤单入口也必须释放委托预占。 */
    @Autowired MatchingService matching;
    /** 独立核对数据库事实。 */
    @Autowired JdbcTemplate jdbc;

    /** 未成交委托也必须占用余额，第二张订单不能重复花同一笔钱。 */
    @Test
    void openOrdersCannotReuseTheSameAvailableBalance() {
        String id = user("USDT", "100");
        String symbol = quote();
        assertEquals("APPROVED", trading.place(order(id, symbol, OrderSide.BUY, "100", "0.6"))
            .preTradeDecision().decision());
        assertEquals("INSUFFICIENT_BALANCE", trading.place(order(id, symbol, OrderSide.BUY, "100", "0.6"))
            .preTradeDecision().reasonCode());
    }

    /** 撤单只恢复一次可用资金，重复撤单与重放下单都不重新冻结。 */
    @Test
    void cancellationReleasesOnlyOnce() {
        String id = user("USDT", "100");
        PlaceOrderCommand command = order(id, quote(), OrderSide.BUY, "100", "0.6");
        var placed = trading.place(command);
        amount("60", "SELECT reserved_balance FROM account WHERE owner_id=? AND asset='USDT'", id);
        matching.cancel(placed.matching().order().orderId(), id);
        matching.cancel(placed.matching().order().orderId(), id);
        trading.place(command);
        amount("0", "SELECT reserved_balance FROM account WHERE owner_id=? AND asset='USDT'", id);
        amount("100", "SELECT balance FROM account WHERE owner_id=? AND asset='USDT'", id);
    }

    /** 受控资金市场不接受超过八位精度的价格，不能由数据库悄悄舍入。 */
    @Test
    void unsupportedPrecisionIsRejectedBeforeFinancialWrites() {
        String id = user("USDT", "100");
        String symbol = quote();
        assertThrows(IllegalArgumentException.class, () -> trading.place(
            order(id, symbol, OrderSide.BUY, "100.000000001", "0.6")));
    }

    /** 为每次测试建立独立已验证用户及初始资金。 */
    private String user(String asset, String balance) {
        String id = "spot-" + UUID.randomUUID();
        trading.registerCustomer(id, id, "CN");
        trading.reviewKyc(id, "VERIFIED");
        trading.openTradingAccount(id, asset, new BigDecimal(balance));
        trading.configureRisk(id, "LOW", true, new BigDecimal("100000"),
            new BigDecimal("1000000"), new BigDecimal("0.20"));
        return id;
    }

    /** 每个测试使用不与历史实验混用的资金市场。 */
    private String quote() {
        String symbol = "SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()
            + "-USDT";
        trading.publishQuote(symbol, new BigDecimal("100"), "LAB", Instant.now());
        return symbol;
    }

    /** 构造独立限价委托。 */
    private static PlaceOrderCommand order(String user, String symbol, OrderSide side,
                                           String price, String quantity) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(), user, symbol, side,
            OrderType.LIMIT, new BigDecimal(price), new BigDecimal(quantity));
    }

    /** 金额比较使用数值语义，不依赖 BigDecimal 的 scale。 */
    private void amount(String expected, String sql, Object... arguments) {
        assertEquals(0, new BigDecimal(expected).compareTo(
            jdbc.queryForObject(sql, BigDecimal.class, arguments)));
    }
}
