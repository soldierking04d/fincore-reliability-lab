package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import dev.fincore.infrastructure.persistence.mapper.ShardLeaseMapper;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker 分片 Lease、排空和 Epoch Fencing 服务。
 *
 * <p><strong>解决的问题：</strong>Lease 表达“当前谁是所有者”，Epoch 阻止网络分区、长 GC 或暂停
 * 后的旧 Worker 恢复并迟到写入；排空状态用于发布和维护前停止接受新事务。</p>
 *
 * <p><strong>CPU 与锁说明：</strong>每次只锁一个 shard 的租约行，控制面可按分片并行；上层短期
 * 缓存降低正常续期写频率。这里不使用忙轮询或进程内定时自旋，过期和接管由事务按需判断。</p>
 *
 * <p><strong>正确性边界：</strong>接管时 Epoch 必须单调递增；资金事务必须调用
 * {@link #requireValidFenceForUpdate(FenceToken)} 在数据面再次锁定并校验，入口缓存不能替代授权。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class ShardLeaseService {
    /** 可续期且可接受数据面写入的 Lease 状态。 */
    private static final String LEASE_RUNNING = "RUNNING";
    /** 分片 Lease 和数据面围栏持久化接口。 */
    private final ShardLeaseMapper leaseMapper;

    /** @param leaseMapper 分片 Lease 持久化接口 */
    public ShardLeaseService(ShardLeaseMapper leaseMapper) {
        this.leaseMapper = leaseMapper;
    }

    /**
     * 获取或续期分片 Lease。
     *
     * @param shardId 分片编号
     * @param ownerId Worker 标识
     * @param ttl Lease 有效期
     * @return 当前 Lease 快照
     */
    @Transactional(rollbackFor = Exception.class)
    public Lease claim(int shardId, String ownerId, Duration ttl) {
        return acquireOrRenew(shardId, ownerId, ttl);
    }

    /**
     * 在行锁保护下创建、续期或接管 Lease。
     *
     * <p>有效 Lease 只能由当前 RUNNING 所有者续期；Lease 过期后其他 Worker 接管时
     * Epoch 必须加一。</p>
     *
     * @param shardId 分片编号
     * @param ownerId Worker 标识
     * @param ttl Lease 有效期
     * @return 更新后的 Lease
     */
    @Transactional(rollbackFor = Exception.class)
    public Lease acquireOrRenew(int shardId, String ownerId, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        ShardLeaseMapper.LeaseRow row = leaseMapper.lockForUpdate(shardId);
        Instant until = Instant.now().plus(ttl);
        if (row == null) {
            leaseMapper.insert(shardId, ownerId, until);
            return new Lease(shardId, ownerId, 1, LEASE_RUNNING, until);
        }
        Lease current = toLease(row);
        boolean live = current.leaseUntil().isAfter(Instant.now());
        if (live && current.ownerId().equals(ownerId) && LEASE_RUNNING.equals(current.state())) {
            leaseMapper.extendOwned(shardId, ownerId, current.epoch(), until);
            return new Lease(shardId, ownerId, current.epoch(), LEASE_RUNNING, until);
        }
        if (live) {
            throw new IllegalStateException("shard unavailable; owner=" + current.ownerId() + ", state=" + current.state());
        }
        // 只有旧 Lease 已过期时才允许接管，并通过递增 Epoch 使旧令牌永久失效。
        long nextEpoch = current.epoch() + 1;
        leaseMapper.takeOver(shardId, ownerId, nextEpoch, until);
        return new Lease(shardId, ownerId, nextEpoch, LEASE_RUNNING, until);
    }

    /**
     * 仅允许当前 RUNNING 所有者续期未过期 Lease。
     *
     * @param shardId 分片编号
     * @param ownerId Worker 标识
     * @param epoch 当前所有权世代
     * @param ttl 新的有效期
     * @return 是否成功续期
     */
    public boolean renew(int shardId, String ownerId, long epoch, Duration ttl) {
        return leaseMapper.renew(shardId, ownerId, epoch, Instant.now().plus(ttl)) == 1;
    }

    /**
     * 把当前分片切换为 DRAINING，阻止继续处理新资金写入。
     *
     * @param shardId 分片编号
     * @param ownerId Worker 标识
     * @param epoch 当前所有权世代
     * @return 是否成功进入排空状态
     */
    public boolean drain(int shardId, String ownerId, long epoch) {
        return leaseMapper.drain(shardId, ownerId, epoch) == 1;
    }

    /**
     * 只读检查围栏令牌是否匹配当前有效 Lease。
     *
     * @param shardId 分片编号
     * @param ownerId Worker 标识
     * @param epoch 待检查的所有权世代
     * @return 是否仍是当前 RUNNING 所有者
     */
    public boolean validFence(int shardId, String ownerId, long epoch) {
        return leaseMapper.countValidFence(shardId, ownerId, epoch) == 1;
    }

    /**
     * 在业务写事务内部强制校验围栏令牌。
     *
     * @param token Worker 提交的分片、所有者和 Epoch
     * @throws FenceRejectedException Lease 缺失、过期、排空或已经被新 Epoch 接管时抛出
     */
    public void requireValidFenceForUpdate(FenceToken token) {
        ShardLeaseMapper.LeaseRow row = leaseMapper.lockForFenceValidation(token.shardId());
        if (row == null) {
            throw new FenceRejectedException("fence rejected: shard lease missing");
        }
        Lease lease = toLease(row);
        if (!lease.ownerId().equals(token.ownerId()) || lease.epoch() != token.epoch() ||
            !"RUNNING".equals(lease.state()) || !lease.leaseUntil().isAfter(Instant.now())) {
            throw new FenceRejectedException("fence rejected: stale or draining worker");
        }
    }

    /** 把持久化记录转换为应用层不可变 Lease 快照。 */
    private static Lease toLease(ShardLeaseMapper.LeaseRow row) {
        return new Lease(row.shardId(), row.ownerId(), row.epoch(), row.state(), row.leaseUntil());
    }

    /**
     * 分片 Lease 快照。
     *
     * @param shardId 分片编号
     * @param ownerId 当前 Worker 标识
     * @param epoch 所有权世代
     * @param state RUNNING 或 DRAINING
     * @param leaseUntil 过期时间
     */
    public record Lease(int shardId, String ownerId, long epoch, String state, Instant leaseUntil) {
    }
}
