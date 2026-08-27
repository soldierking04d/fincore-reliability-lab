package dev.fincore.web;

import dev.fincore.application.ShardLeaseService;
import java.time.Duration;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shards")
public class ShardLeaseController {
    private final ShardLeaseService service;
    public ShardLeaseController(ShardLeaseService service) { this.service = service; }

    @PostMapping("/{shardId}/claim")
    public ShardLeaseService.Lease claim(@PathVariable int shardId, @RequestBody LeaseRequest request) {
        return service.claim(shardId, request.ownerId(), Duration.ofSeconds(request.ttlSeconds()));
    }

    @PostMapping("/{shardId}/drain")
    public Map<String, Boolean> drain(@PathVariable int shardId, @RequestBody FenceRequest request) {
        return Map.of("updated", service.drain(shardId, request.ownerId(), request.epoch()));
    }

    @GetMapping("/{shardId}/fence")
    public Map<String, Boolean> fence(@PathVariable int shardId, @RequestParam String ownerId, @RequestParam long epoch) {
        return Map.of("valid", service.validFence(shardId, ownerId, epoch));
    }

    public record LeaseRequest(String ownerId, long ttlSeconds) {}
    public record FenceRequest(String ownerId, long epoch) {}
}

