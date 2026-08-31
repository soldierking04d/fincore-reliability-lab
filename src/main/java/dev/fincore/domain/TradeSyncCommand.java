package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 将权威成交同步到查询投影的命令。
 *
 * <p>事件号用于投递幂等，成交号用于业务幂等。价格、数量、成交额和序列属于不可变
 * 字段；同一成交号收到不同载荷时必须拒绝，不能覆盖已有投影。</p>
 *
 * @param eventId 同步事件唯一编号
 * @param tradeId 权威成交编号
 * @param symbol 交易对
 * @param makerOrderId Maker 订单编号
 * @param takerOrderId Taker 订单编号
 * @param price 成交价格
 * @param quantity 成交数量
 * @param quoteAmount 计价资产成交额
 * @param tradeSequence 交易对内成交序列
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
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
    /** 校验成交事件的身份、金额守恒和序列约束。 */
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

    /**
     * 从权威成交快照创建同步命令。
     *
     * @param eventId 本次投递事件编号
     * @param trade 权威成交快照
     * @return 可交给投影同步服务处理的命令
     */
    public static TradeSyncCommand from(UUID eventId, TradeView trade) {
        return new TradeSyncCommand(eventId, trade.tradeId(), trade.symbol(),
            trade.makerOrderId(), trade.takerOrderId(), trade.price(), trade.quantity(),
            trade.quoteAmount(), trade.sequence());
    }
}
