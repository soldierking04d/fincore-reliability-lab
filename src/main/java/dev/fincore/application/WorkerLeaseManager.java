package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

/**
 * Kafka Worker Lease 的进程内短期缓存与续期协调器。
 *
 * <p><strong>解决的问题：</strong>若每条消息都续期，会把 {@code shard_lease} 变成数据库写热点，
 * 持续消耗行锁、WAL 和数据库 CPU；若完全信任本地缓存，旧 Worker 又可能越过接管后的新 Epoch。</p>
 *
 * <p><strong>CPU 与并发优化：</strong>正常命中只做一次 ConcurrentHashMap 读取、时间比较和指标原子
 * 累加。需要续期时使用 {@code compute} 仅合并同一 shard 的并发续期，其他 shard 可并行；缓存规模
 * 上限受分片数约束，不会随消息量增长。</p>
 *
 * <p><strong>正确性边界：</strong>缓存只减少控制面数据库访问，不参与最终资金判定。每笔结算仍在
 * 自己的数据库事务内共享锁定 {@code shard_lease} 并校验 owner、epoch、状态和过期时间；缓存过期
 * 或节点被排空时只会导致明确拒绝，不会让旧 Worker 越过数据面 Fencing。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
@Service
public class WorkerLeaseManager {
    /** 真实 Lease 事务服务。 */
    private final ShardLeaseService leases;
    /** 进程内按分片缓存的 Lease 快照。 */
    private final ConcurrentMap<Integer, ShardLeaseService.Lease> cache = new ConcurrentHashMap<>();
    /** Lease 有效期。 */
    private final Duration ttl;
    /** 提前续期窗口。 */
    private final Duration renewAhead;
    /** 未访问数据库的缓存命中次数。 */
    private final Counter cacheHits;
    /** 需要创建或续期 Lease 的次数。 */
    private final Counter renewals;
    /** 围栏失败后主动清理缓存的次数。 */
    private final Counter invalidations;

    /** 创建 Lease 缓存协调器。 */
    public WorkerLeaseManager(ShardLeaseService leases, ConcurrencyProperties properties,
                              MeterRegistry registry) {
        this.leases = leases;
        this.ttl = properties.getWorkerLeaseTtl();
        this.renewAhead = properties.getWorkerLeaseRenewAhead();
        this.cacheHits = registry.counter("fincore.worker.lease.cache.hit");
        this.renewals = registry.counter("fincore.worker.lease.renewal");
        this.invalidations = registry.counter("fincore.worker.lease.invalidation");
    }

    /**
     * 返回当前可用于数据面校验的围栏令牌。
     *
     * @param shardId 结算分片
     * @param workerId 当前 Worker 标识
     * @return owner 和 epoch 不可变快照
     */
    public FenceToken currentFence(int shardId, String workerId) {
        Instant renewalBoundary = Instant.now().plus(renewAhead);
        // 绝大多数消息走无锁读热路径，不访问数据库，也不创建续期事务。
        ShardLeaseService.Lease cached = cache.get(shardId);
        if (reusable(cached, workerId, renewalBoundary)) {
            cacheHits.increment();
            return toFence(cached);
        }
        // compute 只串行化相同 shard 的续期，避免多线程同时把同一 Lease 写成数据库热点。
        ShardLeaseService.Lease current = cache.compute(shardId, (key, existing) -> {
            if (reusable(existing, workerId, Instant.now().plus(renewAhead))) {
                cacheHits.increment();
                return existing;
            }
            renewals.increment();
            return leases.acquireOrRenew(shardId, workerId, ttl);
        });
        return toFence(current);
    }

    /** 围栏校验失败后只清理匹配旧 Epoch 的缓存，避免误删已经完成的并发续期。 */
    public void invalidate(int shardId, long rejectedEpoch) {
        AtomicBoolean removed = new AtomicBoolean();
        cache.computeIfPresent(shardId, (key, existing) -> {
            if (existing.epoch() == rejectedEpoch) {
                removed.set(true);
                return null;
            }
            return existing;
        });
        if (removed.get()) {
            invalidations.increment();
        }
    }

    /** 判断缓存 Lease 是否仍适合继续交给资金事务做最终校验。 */
    private static boolean reusable(ShardLeaseService.Lease lease, String workerId,
                                    Instant renewalBoundary) {
        return lease != null
            && lease.ownerId().equals(workerId)
            && "RUNNING".equals(lease.state())
            && lease.leaseUntil().isAfter(renewalBoundary);
    }

    /** 把 Lease 快照收敛为资金事务只需要的 FenceToken。 */
    private static FenceToken toFence(ShardLeaseService.Lease lease) {
        return new FenceToken(lease.shardId(), lease.ownerId(), lease.epoch());
    }
}
