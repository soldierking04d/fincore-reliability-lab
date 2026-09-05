package dev.fincore.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 创建撮合订单的领域命令。
 *
 * <p>构造时统一完成交易对格式、订单类型、价格和数量精度校验，使应用服务接收到的
 * 命令始终满足最基本的领域约束。</p>
 *
 * @param clientOrderId 客户端生成的幂等订单号
 * @param userId 下单用户
 * @param symbol BASE-QUOTE 格式的交易对
 * @param side 买卖方向
 * @param type 限价或市价
 * @param price 限价单价格；市价单必须为空
 * @param quantity 委托数量
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record PlaceOrderCommand(
    String clientOrderId,
    String userId,
    String symbol,
    OrderSide side,
    OrderType type,
    BigDecimal price,
    BigDecimal quantity
) {
    /** 规范化交易对和金额精度，并拒绝不完整或相互冲突的参数。 */
    public PlaceOrderCommand {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("clientOrderId is required");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        symbol = symbol.trim().toUpperCase(Locale.ROOT);
        if (!TradingIdentifiers.isSymbol(symbol)) {
            throw new IllegalArgumentException("symbol must use BASE-QUOTE format");
        }
        if (side == null || type == null) {
            throw new IllegalArgumentException("side and type are required");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        quantity = quantity.setScale(18, RoundingMode.UNNECESSARY);
        if (type == OrderType.LIMIT) {
            if (price == null || price.signum() <= 0) {
                throw new IllegalArgumentException("limit price must be positive");
            }
            price = price.setScale(18, RoundingMode.UNNECESSARY);
        } else if (price != null) {
            throw new IllegalArgumentException("market order must not contain price");
        }
    }
}
