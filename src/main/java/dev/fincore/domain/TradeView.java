package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record TradeView(
    UUID tradeId,
    String symbol,
    UUID makerOrderId,
    UUID takerOrderId,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal quoteAmount,
    long sequence
) {}
