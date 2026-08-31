package dev.fincore.domain;

import java.util.List;

/**
 * 一次下单或幂等重放返回的撮合结果。
 *
 * @param order 订单的最终快照
 * @param trades 本次 Taker 订单形成的成交列表
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record MatchingResult(OrderView order, List<TradeView> trades) {
    /** 将成交列表复制为不可变快照，防止调用方修改返回结果。 */
    public MatchingResult {
        trades = List.copyOf(trades);
    }
}
