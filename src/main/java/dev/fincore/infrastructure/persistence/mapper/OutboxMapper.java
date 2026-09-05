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
 * <p><strong>解决的问题：</strong>业务服务通过 {@link #insert(UUID, String, String, String)} 与业务
 * 数据同事务写入事件，消除数据库和 Kafka 之间的双写窗口。</p>
 *
 * <p><strong>CPU、锁与 I/O 优化：</strong>Publisher 用单条 UPDATE…RETURNING 抢占有界批次，减少
 * “先查再逐条更新”的往返；{@code SKIP LOCKED} 让多个发布者跳过彼此持有的行，而不是阻塞线程。
 * 成功和失败状态均批量回写，退避与抖动在 SQL 中计算，避免应用维护无界定时任务。</p>
 *
 * <p><strong>正确性边界：</strong>事件所有权由 publisherId 与 PROCESSING 状态共同约束。超时未知
 * 记录不能直接判定失败，必须等待异常抢占恢复；下游仍需幂等，因为崩溃窗口允许重复发送。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface OutboxMapper {
    /**
     * 在当前业务事务中追加待发布事件。
     *
     * @param eventId 事件幂等编号
     * @param aggregateId aggregateId 对应的持久化查询或写入参数
     * @param eventType eventType 对应的持久化查询或写入参数
     * @param payload 序列化后的事件载荷
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload)
        VALUES (#{eventId}, #{aggregateId}, #{eventType}, #{payload})
        """)
    int insert(@Param("eventId") UUID eventId,
               @Param("aggregateId") String aggregateId,
               @Param("eventType") String eventType,
               @Param("payload") String payload);

    /**
     * 原子抢占有界数量的待发布事件，并返回事件载荷。
     *
     * @param publisherId 发布者实例编号
     * @param batchSize 单批最大处理数量
     * @return 满足查询条件的只读结果列表；没有记录时返回空列表
     */
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

    /**
     * 仅允许当前抢占者批量把已确认事件标记为已发布。
     *
     * @param eventIds eventIds 对应的持久化查询或写入参数
     * @param publisherId 发布者实例编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        <script>
        UPDATE outbox_event
        SET status='PUBLISHED', published_at=now(), claimed_at=NULL, publisher_id=NULL
        WHERE event_id IN
        <foreach collection="eventIds" item="eventId" open="(" separator="," close=")">
          #{eventId,javaType=java.util.UUID,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler}
        </foreach>
        AND status='PROCESSING' AND publisher_id=#{publisherId}
        </script>
        """)
    int markPublishedBatch(@Param("eventIds") List<UUID> eventIds,
                           @Param("publisherId") String publisherId);

    /**
     * 发布失败后批量释放抢占，并使用指数退避和确定性抖动错开重试。
     *
     * @param eventIds eventIds 对应的持久化查询或写入参数
     * @param publisherId 发布者实例编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        <script>
        UPDATE outbox_event
        SET status='PENDING', attempts=attempts+1, claimed_at=NULL, publisher_id=NULL,
            next_attempt_at=now() + make_interval(secs =&gt;
                LEAST(300, power(2, LEAST(attempts, 8))::int)
                + mod(hashtext(event_id::text)::bigint + 2147483648, 3)::int)
        WHERE event_id IN
        <foreach collection="eventIds" item="eventId" open="(" separator="," close=")">
          #{eventId,javaType=java.util.UUID,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler}
        </foreach>
        AND status='PROCESSING' AND publisher_id=#{publisherId}
        </script>
        """)
    int releaseForRetryBatch(@Param("eventIds") List<UUID> eventIds,
                             @Param("publisherId") String publisherId);

    /**
     * 查询当前已到重试时间的 Outbox 积压数量，用于监控而不影响抢占。
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("""
        SELECT COUNT(*) FROM outbox_event
        WHERE status='PENDING' AND next_attempt_at<=now()
        """)
    long countReadyBacklog();

    /**
     * 回收已经超过六十秒的异常 PROCESSING 记录。
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
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
