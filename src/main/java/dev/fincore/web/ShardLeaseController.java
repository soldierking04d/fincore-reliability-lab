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

/**
 * Worker 分片 Lease 与 Fencing 控制面接口。
 *
 * <p>该接口用于实验 Lease 获取、排空和令牌校验。真正的资金写入仍会在
 * {@code SettlementService} 事务内部执行数据面 Fencing 校验。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/shards")
public class ShardLeaseController {
    /** 分片 Lease 服务。 */
    private final ShardLeaseService service;

    /** @param service 分片 Lease 服务 */
    public ShardLeaseController(ShardLeaseService service) {
        this.service = service;
    }

    /**
     * 获取或续期指定分片的 Lease。
     *
     * @param shardId 分片编号
     * @param request Worker 标识和 Lease 有效期
     * @return 当前 Lease 快照
     */
    @PostMapping("/{shardId}/claim")
    public ShardLeaseService.Lease claim(@PathVariable int shardId, @RequestBody LeaseRequest request) {
        return service.claim(shardId, request.ownerId(), Duration.ofSeconds(request.ttlSeconds()));
    }

    /**
     * 将当前所有者切换到排空状态。
     *
     * @param shardId 分片编号
     * @param request 当前所有者和 Epoch
     * @return 是否成功更新
     */
    @PostMapping("/{shardId}/drain")
    public Map<String, Boolean> drain(@PathVariable int shardId, @RequestBody FenceRequest request) {
        return Map.of("updated", service.drain(shardId, request.ownerId(), request.epoch()));
    }

    /**
     * 只读检查围栏令牌是否仍然有效。
     *
     * @param shardId 分片编号
     * @param ownerId Worker 标识
     * @param epoch 所有权世代
     * @return 令牌有效性
     */
    @GetMapping("/{shardId}/fence")
    public Map<String, Boolean> fence(@PathVariable int shardId, @RequestParam String ownerId, @RequestParam long epoch) {
        return Map.of("valid", service.validFence(shardId, ownerId, epoch));
    }

    /**
     * Lease 获取请求。
     *
     * @param ownerId Worker 标识
     * @param ttlSeconds Lease 有效秒数
     */
    public record LeaseRequest(String ownerId, long ttlSeconds) {
    }

    /**
     * 排空请求。
     *
     * @param ownerId 当前 Worker 标识
     * @param epoch 当前所有权世代
     */
    public record FenceRequest(String ownerId, long epoch) {
    }
}
