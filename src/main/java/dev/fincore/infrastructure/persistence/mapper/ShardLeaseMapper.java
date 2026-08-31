package dev.fincore.infrastructure.persistence.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Worker 分片 Lease 与 Epoch Fencing Mapper。
 *
 * <p>所有权读取必须与相应的 PostgreSQL 行锁绑定，避免应用层检查和数据面写入之间出现竞态。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface ShardLeaseMapper {
    /** 独占锁定 Lease，供创建、续期或接管事务使用。 */
    @Select("""
        SELECT shard_id AS "shardId", owner_id AS "ownerId", epoch, state,
               lease_until AS "leaseUntil"
        FROM shard_lease
        WHERE shard_id=#{shardId}
        FOR UPDATE
        """)
    LeaseRow lockForUpdate(@Param("shardId") int shardId);

    /** 创建第一个所有权世代。 */
    @Insert("""
        INSERT INTO shard_lease(shard_id, owner_id, epoch, state, lease_until)
        VALUES (#{shardId}, #{ownerId}, 1, 'RUNNING', #{leaseUntil})
        """)
    int insert(@Param("shardId") int shardId,
               @Param("ownerId") String ownerId,
               @Param("leaseUntil") Instant leaseUntil);

    /** 当前所有者在相同 Epoch 内续期。 */
    @Update("""
        UPDATE shard_lease
        SET lease_until=#{leaseUntil}, updated_at=now()
        WHERE shard_id=#{shardId} AND owner_id=#{ownerId} AND epoch=#{epoch}
        """)
    int extendOwned(@Param("shardId") int shardId,
                    @Param("ownerId") String ownerId,
                    @Param("epoch") long epoch,
                    @Param("leaseUntil") Instant leaseUntil);

    /** Lease 过期后切换所有者并写入递增后的 Epoch。 */
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

    /** 仅允许有效的 RUNNING 所有者续期。 */
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

    /** 仅允许当前 RUNNING 所有者进入排空状态。 */
    @Update("""
        UPDATE shard_lease
        SET state='DRAINING', updated_at=now()
        WHERE shard_id=#{shardId} AND owner_id=#{ownerId} AND epoch=#{epoch}
          AND state='RUNNING'
        """)
    int drain(@Param("shardId") int shardId,
              @Param("ownerId") String ownerId,
              @Param("epoch") long epoch);

    /** 只读判断围栏是否仍然有效。 */
    @Select("""
        SELECT COUNT(*)
        FROM shard_lease
        WHERE shard_id=#{shardId} AND owner_id=#{ownerId} AND epoch=#{epoch}
          AND state='RUNNING' AND lease_until>now()
        """)
    int countValidFence(@Param("shardId") int shardId,
                        @Param("ownerId") String ownerId,
                        @Param("epoch") long epoch);

    /** 在资金事务内部共享锁定 Lease 并返回数据面校验快照。 */
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
