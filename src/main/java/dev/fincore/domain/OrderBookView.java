package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 聚合订单簿只读快照。
 *
 * @param symbol 交易对
 * @param bids 买方档位，按价格从高到低排列
 * @param asks 卖方档位，按价格从低到高排列
 * @param lastTradeSequence 当前最新成交序列
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record OrderBookView(
    String symbol,
    List<BookLevel> bids,
    List<BookLevel> asks,
    long lastTradeSequence
) {
    /**
     * 同一价格上的订单聚合档位。
     *
     * @param price 档位价格
     * @param quantity 剩余数量合计
     * @param orderCount 档位中的有效订单数
     */
    public record BookLevel(BigDecimal price, BigDecimal quantity, long orderCount) {
    }
}
