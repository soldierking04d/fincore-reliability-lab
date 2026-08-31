package dev.fincore.domain;

/**
 * 撮合订单状态。
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public enum OrderStatus {
    /** 已受理但尚未成交。 */
    OPEN,

    /** 已发生部分成交，仍有剩余数量。 */
    PARTIALLY_FILLED,

    /** 全部数量已经成交。 */
    FILLED,

    /** 剩余数量已取消。 */
    CANCELED,

    /** 订单未形成成交并被明确拒绝。 */
    REJECTED;

    /** @return 当前状态是否仍允许继续撮合或取消 */
    public boolean isOpen() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }
}
