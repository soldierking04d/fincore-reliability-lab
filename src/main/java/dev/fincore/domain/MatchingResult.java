package dev.fincore.domain;

import java.util.List;

public record MatchingResult(OrderView order, List<TradeView> trades) {
    public MatchingResult {
        trades = List.copyOf(trades);
    }
}
