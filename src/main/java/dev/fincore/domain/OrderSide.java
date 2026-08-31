package dev.fincore.domain;

/**
 * 订单买卖方向。
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public enum OrderSide {
    /** 买入基础资产。 */
    BUY,

    /** 卖出基础资产。 */
    SELL;

    /** @return 当前方向对应的反方向 */
    public OrderSide opposite() {
        return this == BUY ? SELL : BUY;
    }
}
