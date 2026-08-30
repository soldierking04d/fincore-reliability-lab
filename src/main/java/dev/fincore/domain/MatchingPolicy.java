package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.Objects;

public final class MatchingPolicy {
    private MatchingPolicy() {}

    public static boolean crosses(OrderSide takerSide, OrderType orderType,
                                  BigDecimal limitPrice, BigDecimal makerPrice) {
        Objects.requireNonNull(takerSide, "takerSide");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(makerPrice, "makerPrice");
        if (orderType == OrderType.MARKET) return true;
        Objects.requireNonNull(limitPrice, "limitPrice");
        return takerSide == OrderSide.BUY
            ? limitPrice.compareTo(makerPrice) >= 0
            : limitPrice.compareTo(makerPrice) <= 0;
    }

    public static BigDecimal quoteAmount(BigDecimal price, BigDecimal quantity) {
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(quantity, "quantity");
        if (price.signum() <= 0 || quantity.signum() <= 0) {
            throw new IllegalArgumentException("price and quantity must be positive");
        }
        return price.multiply(quantity);
    }
}
