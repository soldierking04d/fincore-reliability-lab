package dev.fincore.web;

import dev.fincore.application.MatchingService;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderBookView;
import dev.fincore.domain.OrderView;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/matching")
public class MatchingController {
    private final MatchingService matching;

    public MatchingController(MatchingService matching) {
        this.matching = matching;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public MatchingResult place(@RequestBody PlaceOrderCommand command) {
        return matching.place(command);
    }

    @GetMapping("/orders/{orderId}")
    public OrderView get(@PathVariable UUID orderId) {
        return matching.get(orderId);
    }

    @DeleteMapping("/orders/{orderId}")
    public OrderView cancel(@PathVariable UUID orderId, @RequestParam String userId) {
        return matching.cancel(orderId, userId);
    }

    @GetMapping("/books/{symbol}")
    public OrderBookView book(@PathVariable String symbol,
                              @RequestParam(defaultValue = "20") int depth) {
        return matching.book(symbol, depth);
    }

    @GetMapping("/trades/{symbol}")
    public List<TradeView> trades(@PathVariable String symbol,
                                  @RequestParam(defaultValue = "50") int limit) {
        return matching.recentTrades(symbol, limit);
    }
}
