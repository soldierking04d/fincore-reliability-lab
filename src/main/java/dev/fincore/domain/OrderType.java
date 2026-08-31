package dev.fincore.domain;

/**
 * 订单类型。
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public enum OrderType {
    /** 只有在对手价格满足指定限价时才允许成交。 */
    LIMIT,

    /** 按当前可用深度依次成交，不携带限价。 */
    MARKET
}
