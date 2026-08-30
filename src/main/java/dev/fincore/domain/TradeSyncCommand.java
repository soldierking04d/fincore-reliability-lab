package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record TradeSyncCommand(
    UUID eventId,
    UUID tradeId,
    String symbol,
    UUID makerOrderId,
    UUID takerOrderId,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal quoteAmount,
    long tradeSequence
) {
    public TradeSyncCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(makerOrderId, "makerOrderId");
        Objects.requireNonNull(takerOrderId, "takerOrderId");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(quoteAmount, "quoteAmount");
        if (makerOrderId.equals(takerOrderId)) {
            throw new IllegalArgumentException("Maker 与 Taker 订单不能相同 / distinct orders required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("交易对不能为空 / symbol is required");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9]{2,20}-[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("交易对必须使用 BASE-QUOTE 格式 / invalid symbol");
        }
        if (price.signum() <= 0 || quantity.signum() <= 0 || quoteAmount.signum() <= 0) {
            throw new IllegalArgumentException("价格、数量和成交额必须为正数 / positive values required");
        }
        if (tradeSequence <= 0) {
            throw new IllegalArgumentException("成交序列必须为正数 / positive sequence required");
        }
        if (price.multiply(quantity).compareTo(quoteAmount) != 0) {
            throw new IllegalArgumentException("成交额与价格乘数量不一致 / quote amount mismatch");
        }
    }

    public static TradeSyncCommand from(UUID eventId, TradeView trade) {
        return new TradeSyncCommand(eventId, trade.tradeId(), trade.symbol(),
            trade.makerOrderId(), trade.takerOrderId(), trade.price(), trade.quantity(),
            trade.quoteAmount(), trade.sequence());
    }
}
