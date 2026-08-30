package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MatchingPolicyTest {
    @Test
    void limitOrdersCrossOnlyAtCompatiblePrices() {
        assertTrue(MatchingPolicy.crosses(OrderSide.BUY, OrderType.LIMIT,
            new BigDecimal("101"), new BigDecimal("100")));
        assertFalse(MatchingPolicy.crosses(OrderSide.BUY, OrderType.LIMIT,
            new BigDecimal("99"), new BigDecimal("100")));
        assertTrue(MatchingPolicy.crosses(OrderSide.SELL, OrderType.LIMIT,
            new BigDecimal("99"), new BigDecimal("100")));
        assertFalse(MatchingPolicy.crosses(OrderSide.SELL, OrderType.LIMIT,
            new BigDecimal("101"), new BigDecimal("100")));
    }

    @Test
    void marketOrderAlwaysCrossesAvailableMaker() {
        assertTrue(MatchingPolicy.crosses(
            OrderSide.BUY, OrderType.MARKET, null, new BigDecimal("100")));
    }

    @Test
    void commandRejectsFloatingPointStyleAndInvalidShape() {
        assertThrows(ArithmeticException.class, () -> new PlaceOrderCommand(
            "c-1", "u-1", "BTC-USDT", OrderSide.BUY, OrderType.LIMIT,
            new BigDecimal("1.0000000000000000001"), BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> new PlaceOrderCommand(
            "c-2", "u-1", "BTC-USDT", OrderSide.BUY, OrderType.MARKET,
            BigDecimal.TEN, BigDecimal.ONE));
    }

    @Test
    void quoteAmountUsesExactDecimalArithmetic() {
        assertEquals(0, MatchingPolicy.quoteAmount(
            new BigDecimal("123.45"), new BigDecimal("0.2"))
            .compareTo(new BigDecimal("24.690")));
    }
}
