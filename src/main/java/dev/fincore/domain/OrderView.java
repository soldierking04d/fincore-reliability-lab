package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderView(
    UUID orderId,
    String clientOrderId,
    String userId,
    String symbol,
    OrderSide side,
    OrderType type,
    BigDecimal price,
    BigDecimal originalQuantity,
    BigDecimal executedQuantity,
    BigDecimal remainingQuantity,
    OrderStatus status,
    long sequence,
    long version,
    boolean duplicate,
    String detail
) {}
