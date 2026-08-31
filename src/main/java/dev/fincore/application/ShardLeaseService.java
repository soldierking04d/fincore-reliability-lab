package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Worker 分片 Lease、排空和 Epoch Fencing 服务。
 *
 * <p>Lease 负责表达“当前谁是所有者”，Epoch 负责阻止旧所有者恢复后的迟到写入。
 * 接管时 Epoch 单调递增；资金事务必须调用 {@link #requireValidFenceForUpdate(FenceToken)}
 * 在数据面再次校验当前所有权。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class ShardLeaseService {
    /** 分片 Lease 数据库访问模板。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 数据库访问模板 */
    public ShardLeaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取或续期分片 Lease。
     *
     * @param shardId 分片编号
     * @param ownerId Worker 标识
     * @param ttl Lease 有效期
     * @return 当前 Lease 快照
     */
    @Transactional
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
    @Transactional
    public Lease acquireOrRenew(int shardId, String ownerId, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        List<Lease> rows = jdbc.query("""
            SELECT shard_id, owner_id, epoch, state, lease_until FROM shard_lease
            WHERE shard_id=? FOR UPDATE
            """, (rs, row) -> new Lease(rs.getInt("shard_id"), rs.getString("owner_id"),
                rs.getLong("epoch"), rs.getString("state"), rs.getTimestamp("lease_until").toInstant()), shardId);
        Instant until = Instant.now().plus(ttl);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO shard_lease(shard_id, owner_id, epoch, state, lease_until) VALUES (?, ?, 1, 'RUNNING', ?)",
                shardId, ownerId, Timestamp.from(until));
            return new Lease(shardId, ownerId, 1, "RUNNING", until);
        }
        Lease current = rows.get(0);
        boolean live = current.leaseUntil().isAfter(Instant.now());
        if (live && current.ownerId().equals(ownerId) && "RUNNING".equals(current.state())) {
            jdbc.update("UPDATE shard_lease SET lease_until=?, updated_at=now() WHERE shard_id=? AND owner_id=? AND epoch=?",
                Timestamp.from(until), shardId, ownerId, current.epoch());
            return new Lease(shardId, ownerId, current.epoch(), "RUNNING", until);
        }
        if (live) {
            throw new IllegalStateException("shard unavailable; owner=" + current.ownerId() + ", state=" + current.state());
        }
        // 只有旧 Lease 已过期时才允许接管，并通过递增 Epoch 使旧令牌永久失效。
        long nextEpoch = current.epoch() + 1;
        jdbc.update("""
            UPDATE shard_lease SET owner_id=?, epoch=?, state='RUNNING', lease_until=?, updated_at=now()
            WHERE shard_id=?
            """, ownerId, nextEpoch, Timestamp.from(until), shardId);
        return new Lease(shardId, ownerId, nextEpoch, "RUNNING", until);
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
        return jdbc.update("""
            UPDATE shard_lease SET lease_until=?, updated_at=now()
            WHERE shard_id=? AND owner_id=? AND epoch=? AND state='RUNNING' AND lease_until>now()
            """, Timestamp.from(Instant.now().plus(ttl)), shardId, ownerId, epoch) == 1;
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
        return jdbc.update("""
            UPDATE shard_lease SET state='DRAINING', updated_at=now()
            WHERE shard_id=? AND owner_id=? AND epoch=? AND state='RUNNING'
            """, shardId, ownerId, epoch) == 1;
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
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM shard_lease
            WHERE shard_id=? AND owner_id=? AND epoch=? AND state='RUNNING' AND lease_until>now()
            """, Integer.class, shardId, ownerId, epoch);
        return count != null && count == 1;
    }

    /**
     * 在业务写事务内部强制校验围栏令牌。
     *
     * @param token Worker 提交的分片、所有者和 Epoch
     * @throws IllegalStateException Lease 缺失、过期、排空或已经被新 Epoch 接管时抛出
     */
    public void requireValidFenceForUpdate(FenceToken token) {
        List<Lease> leases = jdbc.query("""
            SELECT shard_id, owner_id, epoch, state, lease_until FROM shard_lease
            WHERE shard_id=? FOR SHARE
            """, (rs, row) -> new Lease(rs.getInt("shard_id"), rs.getString("owner_id"),
                rs.getLong("epoch"), rs.getString("state"), rs.getTimestamp("lease_until").toInstant()), token.shardId());
        if (leases.size() != 1) {
            throw new IllegalStateException("fence rejected: shard lease missing");
        }
        Lease lease = leases.get(0);
        if (!lease.ownerId().equals(token.ownerId()) || lease.epoch() != token.epoch() ||
            !"RUNNING".equals(lease.state()) || !lease.leaseUntil().isAfter(Instant.now())) {
            throw new IllegalStateException("fence rejected: stale or draining worker");
        }
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
