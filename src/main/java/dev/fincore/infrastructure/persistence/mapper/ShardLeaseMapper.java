package dev.fincore.infrastructure.persistence.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Worker 分片 Lease 与 Epoch Fencing Mapper。
 *
 * <p><strong>解决的问题：</strong>节点暂停、网络分区或接管后，旧 Worker 仍可能恢复执行；递增 Epoch
 * 让数据库能拒绝旧世代的写入。</p>
 *
 * <p><strong>CPU 与锁优化：</strong>控制面只锁目标 shard 的一行，不使用全局租约锁；续期使用 owner、
 * epoch 和状态条件一次 CAS 更新。上层短期缓存降低续期频率，但不会绕开数据面校验。</p>
 *
 * <p><strong>正确性边界：</strong>所有权读取必须与 PostgreSQL 行锁绑定，避免应用检查和接管之间的
 * 竞态；真正的资金写入还必须在同一事务中重新校验 RUNNING、过期时间、owner 与 epoch。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface ShardLeaseMapper {
    /**
     * 独占锁定 Lease，供创建、续期或接管事务使用。
     *
     * @param shardId 分片编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT shard_id AS "shardId", owner_id AS "ownerId", epoch, state,
               lease_until AS "leaseUntil"
        FROM shard_lease
        WHERE shard_id=#{shardId}
        FOR UPDATE
        """)
    LeaseRow lockForUpdate(@Param("shardId") int shardId);

    /**
     * 创建第一个所有权世代。
     *
     * @param shardId 分片编号
     * @param ownerId 账户所有者编号
     * @param leaseUntil 租约有效期截止时间
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO shard_lease(shard_id, owner_id, epoch, state, lease_until)
        VALUES (#{shardId}, #{ownerId}, 1, 'RUNNING', #{leaseUntil})
        """)
    int insert(@Param("shardId") int shardId,
               @Param("ownerId") String ownerId,
               @Param("leaseUntil") Instant leaseUntil);

    /**
     * 当前所有者在相同 Epoch 内续期。
     *
     * @param shardId 分片编号
     * @param ownerId 账户所有者编号
     * @param epoch Worker 所有权代次
     * @param leaseUntil 租约有效期截止时间
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE shard_lease
        SET lease_until=#{leaseUntil}, updated_at=now()
        WHERE shard_id=#{shardId} AND owner_id=#{ownerId} AND epoch=#{epoch}
        """)
    int extendOwned(@Param("shardId") int shardId,
                    @Param("ownerId") String ownerId,
                    @Param("epoch") long epoch,
                    @Param("leaseUntil") Instant leaseUntil);

    /**
     * Lease 过期后切换所有者并写入递增后的 Epoch。
     *
     * @param shardId 分片编号
     * @param ownerId 账户所有者编号
     * @param epoch Worker 所有权代次
     * @param leaseUntil 租约有效期截止时间
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE shard_lease
        SET owner_id=#{ownerId}, epoch=#{epoch}, state='RUNNING',
            lease_until=#{leaseUntil}, updated_at=now()
        WHERE shard_id=#{shardId}
        """)
    int takeOver(@Param("shardId") int shardId,
                 @Param("ownerId") String ownerId,
                 @Param("epoch") long epoch,
                 @Param("leaseUntil") Instant leaseUntil);

    /**
     * 仅允许有效的 RUNNING 所有者续期。
     *
     * @param shardId 分片编号
     * @param ownerId 账户所有者编号
     * @param epoch Worker 所有权代次
     * @param leaseUntil 租约有效期截止时间
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE shard_lease
        SET lease_until=#{leaseUntil}, updated_at=now()
        WHERE shard_id=#{shardId} AND owner_id=#{ownerId} AND epoch=#{epoch}
          AND state='RUNNING' AND lease_until>now()
        """)
    int renew(@Param("shardId") int shardId,
              @Param("ownerId") String ownerId,
              @Param("epoch") long epoch,
              @Param("leaseUntil") Instant leaseUntil);

    /**
     * 仅允许当前 RUNNING 所有者进入排空状态。
     *
     * @param shardId 分片编号
     * @param ownerId 账户所有者编号
     * @param epoch Worker 所有权代次
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE shard_lease
        SET state='DRAINING', updated_at=now()
        WHERE shard_id=#{shardId} AND owner_id=#{ownerId} AND epoch=#{epoch}
          AND state='RUNNING'
        """)
    int drain(@Param("shardId") int shardId,
              @Param("ownerId") String ownerId,
              @Param("epoch") long epoch);

    /**
     * 只读判断围栏是否仍然有效。
     *
     * @param shardId 分片编号
     * @param ownerId 账户所有者编号
     * @param epoch Worker 所有权代次
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Select("""
        SELECT COUNT(*)
        FROM shard_lease
        WHERE shard_id=#{shardId} AND owner_id=#{ownerId} AND epoch=#{epoch}
          AND state='RUNNING' AND lease_until>now()
        """)
    int countValidFence(@Param("shardId") int shardId,
                        @Param("ownerId") String ownerId,
                        @Param("epoch") long epoch);

    /**
     * 在资金事务内部共享锁定 Lease 并返回数据面校验快照。
     *
     * @param shardId 分片编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT shard_id AS "shardId", owner_id AS "ownerId", epoch, state,
               lease_until AS "leaseUntil"
        FROM shard_lease
        WHERE shard_id=#{shardId}
        FOR SHARE
        """)
    LeaseRow lockForFenceValidation(@Param("shardId") int shardId);

    /** Lease 持久化快照。 */
    record LeaseRow(int shardId, String ownerId, long epoch, String state, Instant leaseUntil) {
    }
}
