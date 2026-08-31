package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.MatchingService;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * 价格时间优先、幂等重放、并发订单和自成交保护的撮合集成测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.task.scheduling.enabled=false"
})
class MatchingIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MatchingService matching;
    @Autowired JdbcTemplate jdbc;

    @Test
    void pricePriorityBeatsTimeAndTradeUsesMakerPrice() {
        MatchingResult worse = matching.place(limit("sell-worse", "maker-a", "BTC-USDT",
            OrderSide.SELL, "101", "100"));
        MatchingResult best = matching.place(limit("sell-best", "maker-b", "BTC-USDT",
            OrderSide.SELL, "100", "200"));

        MatchingResult buy = matching.place(limit("buy-taker", "buyer", "BTC-USDT",
            OrderSide.BUY, "101", "250"));

        assertEquals(2, buy.trades().size());
        assertEquals(best.order().orderId(), buy.trades().get(0).makerOrderId());
        assertEquals(0, buy.trades().get(0).price().compareTo(new BigDecimal("100")));
        assertEquals(0, buy.trades().get(0).quantity().compareTo(new BigDecimal("200")));
        assertEquals(worse.order().orderId(), buy.trades().get(1).makerOrderId());
        assertEquals(0, buy.trades().get(1).price().compareTo(new BigDecimal("101")));
        assertEquals(OrderStatus.FILLED, buy.order().status());

        var book = matching.book("btc-usdt", 20);
        assertTrue(book.bids().isEmpty());
        assertEquals(1, book.asks().size());
        assertEquals(0, book.asks().get(0).quantity().compareTo(new BigDecimal("50")));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM outbox_event
            WHERE event_type='MATCHING_TRADE_EXECUTED'
              AND payload LIKE '%"symbol":"BTC-USDT"%'
            """, Integer.class));
    }

    @Test
    void samePriceUsesEarlierOrderFirst() {
        MatchingResult first = matching.place(limit("eth-sell-1", "maker-c", "ETH-USDT",
            OrderSide.SELL, "10", "10"));
        MatchingResult second = matching.place(limit("eth-sell-2", "maker-d", "ETH-USDT",
            OrderSide.SELL, "10", "10"));

        MatchingResult buy = matching.place(limit("eth-buy", "buyer-2", "ETH-USDT",
            OrderSide.BUY, "10", "15"));

        assertEquals(first.order().orderId(), buy.trades().get(0).makerOrderId());
        assertEquals(second.order().orderId(), buy.trades().get(1).makerOrderId());
        assertEquals(0, matching.get(second.order().orderId()).remainingQuantity()
            .compareTo(new BigDecimal("5")));
    }

    @Test
    void replayIsIdempotentAndConflictingPayloadIsRejected() {
        PlaceOrderCommand command = limit("idem-1", "idem-user", "SOL-USDT",
            OrderSide.BUY, "50", "3");

        MatchingResult first = matching.place(command);
        MatchingResult replay = matching.place(command);

        assertFalse(first.order().duplicate());
        assertTrue(replay.order().duplicate());
        assertEquals(first.order().orderId(), replay.order().orderId());
        assertThrows(IllegalArgumentException.class, () -> matching.place(limit(
            "idem-1", "idem-user", "SOL-USDT", OrderSide.BUY, "51", "3")));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM matching_order
            WHERE user_id='idem-user' AND client_order_id='idem-1'
            """, Integer.class));
    }

    @Test
    void duplicateClientOrderIsSerializedAcrossThreads() throws Exception {
        int workers = 8;
        var pool = Executors.newFixedThreadPool(workers);
        var start = new CountDownLatch(1);
        try {
            List<Future<MatchingResult>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return matching.place(limit("race-1", "race-user", "XRP-USDT",
                        OrderSide.BUY, "1", "100"));
                }));
            }
            start.countDown();
            int original = 0;
            int duplicate = 0;
            for (Future<MatchingResult> future : futures) {
                if (future.get(20, TimeUnit.SECONDS).order().duplicate()) duplicate++;
                else original++;
            }
            assertEquals(1, original);
            assertEquals(workers - 1, duplicate);
            assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM matching_order
                WHERE user_id='race-user' AND client_order_id='race-1'
                """, Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void marketRemainderAndSelfTradeAreCanceledSafely() {
        MatchingResult emptyMarket = matching.place(new PlaceOrderCommand(
            "market-empty", "market-user", "ADA-USDT", OrderSide.BUY,
            OrderType.MARKET, null, new BigDecimal("5")));
        assertEquals(OrderStatus.REJECTED, emptyMarket.order().status());
        assertTrue(emptyMarket.trades().isEmpty());

        MatchingResult maker = matching.place(limit("self-sell", "same-user", "DOGE-USDT",
            OrderSide.SELL, "1", "10"));
        MatchingResult taker = matching.place(limit("self-buy", "same-user", "DOGE-USDT",
            OrderSide.BUY, "1", "10"));
        assertEquals(OrderStatus.CANCELED, taker.order().status());
        assertTrue(taker.trades().isEmpty());
        assertEquals(OrderStatus.OPEN, matching.get(maker.order().orderId()).status());
    }

    @Test
    void openOrderCanBeCanceledOnlyByItsOwner() {
        MatchingResult order = matching.place(limit("cancel-1", "owner-1", "BNB-USDT",
            OrderSide.BUY, "500", "2"));

        assertThrows(IllegalArgumentException.class,
            () -> matching.cancel(order.order().orderId(), "other-user"));
        assertEquals(OrderStatus.CANCELED,
            matching.cancel(order.order().orderId(), "owner-1").status());
        assertEquals(OrderStatus.CANCELED,
            matching.cancel(order.order().orderId(), "owner-1").status());
    }

    private static PlaceOrderCommand limit(String clientOrderId, String userId, String symbol,
                                           OrderSide side, String price, String quantity) {
        return new PlaceOrderCommand(clientOrderId, userId, symbol, side, OrderType.LIMIT,
            new BigDecimal(price), new BigDecimal(quantity));
    }
}
