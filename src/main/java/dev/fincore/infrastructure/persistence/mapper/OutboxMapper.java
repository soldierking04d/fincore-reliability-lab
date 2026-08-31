package dev.fincore.infrastructure.persistence.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 事务 Outbox MyBatis Mapper。
 *
 * <p>业务服务调用 {@link #insert(UUID, String, String, String)} 与业务数据同事务写入事件；
 * Publisher 通过带 {@code SKIP LOCKED} 的原子语句抢占批次。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface OutboxMapper {
    /** 在当前业务事务中追加待发布事件。 */
    @Insert("""
        INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload)
        VALUES (#{eventId}, #{aggregateId}, #{eventType}, #{payload})
        """)
    int insert(@Param("eventId") UUID eventId,
               @Param("aggregateId") String aggregateId,
               @Param("eventType") String eventType,
               @Param("payload") String payload);

    /** 原子抢占最多一百条待发布事件，并返回事件载荷。 */
    @Select("""
        UPDATE outbox_event
        SET status='PROCESSING', claimed_at=now(), publisher_id=#{publisherId}
        WHERE event_id IN (
            SELECT event_id
            FROM outbox_event
            WHERE status='PENDING' AND next_attempt_at<=now()
            ORDER BY created_at
            LIMIT 100
            FOR UPDATE SKIP LOCKED
        )
        RETURNING event_id AS "eventId", aggregate_id AS "aggregateId",
                  event_type AS "eventType", payload
        """)
    List<OutboxEventRow> claimBatch(@Param("publisherId") String publisherId);

    /** 仅允许当前抢占者把事件标记为已发布。 */
    @Update("""
        UPDATE outbox_event
        SET status='PUBLISHED', published_at=now(), claimed_at=NULL, publisher_id=NULL
        WHERE event_id=#{eventId} AND status='PROCESSING' AND publisher_id=#{publisherId}
        """)
    int markPublished(@Param("eventId") UUID eventId, @Param("publisherId") String publisherId);

    /** 发布失败后释放抢占并设置固定退避时间。 */
    @Update("""
        UPDATE outbox_event
        SET status='PENDING', attempts=attempts+1, claimed_at=NULL, publisher_id=NULL,
            next_attempt_at=now() + interval '5 seconds'
        WHERE event_id=#{eventId} AND status='PROCESSING' AND publisher_id=#{publisherId}
        """)
    int releaseForRetry(@Param("eventId") UUID eventId, @Param("publisherId") String publisherId);

    /** 回收已经超过六十秒的异常 PROCESSING 记录。 */
    @Update("""
        UPDATE outbox_event
        SET status='PENDING', publisher_id=NULL, claimed_at=NULL,
            attempts=attempts+1, next_attempt_at=now()
        WHERE status='PROCESSING'
          AND (claimed_at IS NULL OR claimed_at < now() - interval '60 seconds')
        """)
    int recoverAbandonedClaims();

    /** Publisher 使用的不可变事件快照。 */
    record OutboxEventRow(UUID eventId, String aggregateId, String eventType, String payload) {
    }
}
