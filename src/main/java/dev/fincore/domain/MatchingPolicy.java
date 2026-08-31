package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * 撮合领域中的纯计算规则。
 *
 * <p>本类不访问数据库，也不改变订单状态，只负责判断价格是否可成交以及计算成交额，
 * 便于在单元测试中独立验证核心业务规则。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public final class MatchingPolicy {
    /** 工具类不允许实例化。 */
    private MatchingPolicy() {
    }

    /**
     * 判断 Taker 订单能否与当前 Maker 价格成交。
     *
     * @param takerSide Taker 买卖方向
     * @param orderType 订单类型
     * @param limitPrice 限价单价格；市价单时允许为空
     * @param makerPrice Maker 挂单价格
     * @return {@code true} 表示价格相交，可以继续成交
     */
    public static boolean crosses(OrderSide takerSide, OrderType orderType,
                                  BigDecimal limitPrice, BigDecimal makerPrice) {
        Objects.requireNonNull(takerSide, "takerSide");
        Objects.requireNonNull(orderType, "orderType");
        Objects.requireNonNull(makerPrice, "makerPrice");
        if (orderType == OrderType.MARKET) {
            return true;
        }
        Objects.requireNonNull(limitPrice, "limitPrice");
        return takerSide == OrderSide.BUY
            ? limitPrice.compareTo(makerPrice) >= 0
            : limitPrice.compareTo(makerPrice) <= 0;
    }

    /**
     * 计算成交的计价资产金额。
     *
     * @param price 成交价格
     * @param quantity 成交数量
     * @return 价格乘以数量得到的精确成交额
     */
    public static BigDecimal quoteAmount(BigDecimal price, BigDecimal quantity) {
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(quantity, "quantity");
        if (price.signum() <= 0 || quantity.signum() <= 0) {
            throw new IllegalArgumentException("price and quantity must be positive");
        }
        return price.multiply(quantity);
    }
}
