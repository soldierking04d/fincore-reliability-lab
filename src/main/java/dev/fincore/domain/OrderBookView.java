package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.List;

public record OrderBookView(
    String symbol,
    List<BookLevel> bids,
    List<BookLevel> asks,
    long lastTradeSequence
) {
    public record BookLevel(BigDecimal price, BigDecimal quantity, long orderCount) {}
}
