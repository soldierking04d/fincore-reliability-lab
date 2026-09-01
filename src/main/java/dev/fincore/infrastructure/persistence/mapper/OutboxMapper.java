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

    /** 原子抢占有界数量的待发布事件，并返回事件载荷。 */
    @Select("""
        UPDATE outbox_event
        SET status='PROCESSING', claimed_at=now(), publisher_id=#{publisherId}
        WHERE event_id IN (
            SELECT event_id
            FROM outbox_event
            WHERE status='PENDING' AND next_attempt_at<=now()
            ORDER BY created_at
            LIMIT #{batchSize}
            FOR UPDATE SKIP LOCKED
        )
        RETURNING event_id AS "eventId", aggregate_id AS "aggregateId",
                  event_type AS "eventType", payload
        """)
    List<OutboxEventRow> claimBatch(@Param("publisherId") String publisherId,
                                    @Param("batchSize") int batchSize);

    /** 仅允许当前抢占者批量把已确认事件标记为已发布。 */
    @Update("""
        <script>
        UPDATE outbox_event
        SET status='PUBLISHED', published_at=now(), claimed_at=NULL, publisher_id=NULL
        WHERE event_id IN
        <foreach collection="eventIds" item="eventId" open="(" separator="," close=")">
          #{eventId}
        </foreach>
        AND status='PROCESSING' AND publisher_id=#{publisherId}
        </script>
        """)
    int markPublishedBatch(@Param("eventIds") List<UUID> eventIds,
                           @Param("publisherId") String publisherId);

    /** 发布失败后批量释放抢占，并使用指数退避和确定性抖动错开重试。 */
    @Update("""
        <script>
        UPDATE outbox_event
        SET status='PENDING', attempts=attempts+1, claimed_at=NULL, publisher_id=NULL,
            next_attempt_at=now() + make_interval(secs =&gt;
                LEAST(300, power(2, LEAST(attempts, 8))::int)
                + mod(hashtext(event_id::text)::bigint + 2147483648, 3)::int)
        WHERE event_id IN
        <foreach collection="eventIds" item="eventId" open="(" separator="," close=")">
          #{eventId}
        </foreach>
        AND status='PROCESSING' AND publisher_id=#{publisherId}
        </script>
        """)
    int releaseForRetryBatch(@Param("eventIds") List<UUID> eventIds,
                             @Param("publisherId") String publisherId);

    /** 查询当前已到重试时间的 Outbox 积压数量，用于监控而不影响抢占。 */
    @Select("""
        SELECT COUNT(*) FROM outbox_event
        WHERE status='PENDING' AND next_attempt_at<=now()
        """)
    long countReadyBacklog();

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
