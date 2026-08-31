package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 权威成交的只读快照。
 *
 * @param tradeId 成交编号
 * @param symbol 交易对
 * @param makerOrderId Maker 订单编号
 * @param takerOrderId Taker 订单编号
 * @param price Maker 成交价格
 * @param quantity 成交数量
 * @param quoteAmount 计价资产成交额
 * @param sequence 交易对内持久化成交序列
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record TradeView(
    UUID tradeId,
    String symbol,
    UUID makerOrderId,
    UUID takerOrderId,
    BigDecimal price,
    BigDecimal quantity,
    BigDecimal quoteAmount,
    long sequence
) {
}
