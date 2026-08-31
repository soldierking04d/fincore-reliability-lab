package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 撮合订单的只读业务快照。
 *
 * @param orderId 系统订单编号
 * @param clientOrderId 客户端幂等订单号
 * @param userId 下单用户
 * @param symbol 交易对
 * @param side 买卖方向
 * @param type 订单类型
 * @param price 限价；市价单为空
 * @param originalQuantity 原始委托数量
 * @param executedQuantity 已成交数量
 * @param remainingQuantity 剩余数量
 * @param status 订单状态
 * @param sequence 交易对内持久化订单序列
 * @param version 乐观锁版本
 * @param duplicate 是否由幂等重放返回
 * @param detail 状态说明或拒绝原因
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
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
) {
}
