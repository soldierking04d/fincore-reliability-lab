package dev.fincore.web;

import dev.fincore.application.TradeReliabilityService;
import dev.fincore.domain.TradeSyncCommand;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trade-reliability")
public class TradeReliabilityController {
    private final TradeReliabilityService reliability;

    public TradeReliabilityController(TradeReliabilityService reliability) {
        this.reliability = reliability;
    }

    @PostMapping("/sync-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TradeReliabilityService.SyncOutcome sync(
        @RequestBody TradeSyncCommand command) {
        return reliability.apply(command);
    }

    @PostMapping("/reconciliation-runs")
    public TradeReliabilityService.ReconciliationReport reconcile(
        @RequestParam String symbol) {
        return reliability.reconcile(symbol);
    }

    @GetMapping("/reconciliation-runs/{runId}")
    public TradeReliabilityService.ReconciliationReport getRun(
        @PathVariable UUID runId) {
        return reliability.loadRun(runId);
    }

    @PostMapping("/reconciliation-runs/{runId}/repairs")
    public TradeReliabilityService.RepairOutcome repair(
        @PathVariable UUID runId,
        @RequestBody RepairRequest request) {
        return reliability.repair(runId, request.idempotencyKey());
    }

    public record RepairRequest(String idempotencyKey) {}
}
