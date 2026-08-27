package dev.fincore.web;

import dev.fincore.application.FeeAggregationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fees")
public class FeeController {
    private final FeeAggregationService fees;
    public FeeController(FeeAggregationService fees) { this.fees = fees; }

    @PostMapping("/shards")
    public List<FeeAggregationService.FeeAccount> ensureShards(@RequestParam String asset,
                                                               @RequestParam(defaultValue = "16") int count) {
        return fees.ensureShards(asset, count);
    }

    @GetMapping("/route")
    public FeeAggregationService.FeeAccount route(@RequestParam String asset,
                                                   @RequestParam(defaultValue = "16") int count,
                                                   @RequestParam String businessKey) {
        return fees.route(asset, count, businessKey);
    }

    @PostMapping("/treasury")
    public FeeAggregationService.FeeAccount createTreasury(@RequestParam String asset) {
        return fees.ensureTreasury(asset);
    }

    @PostMapping("/aggregate")
    public FeeAggregationService.AggregationOutcome aggregate(@RequestBody AggregationRequest request) {
        return fees.aggregate(request.aggregationKey(), request.asset(), request.treasuryAccountId());
    }

    public record AggregationRequest(String aggregationKey, String asset, UUID treasuryAccountId) {}
}
