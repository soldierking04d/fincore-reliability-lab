package dev.fincore.messaging;

import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事务 Outbox 事件发布器。
 *
 * <p>业务事务只负责把事件写入 {@code outbox_event}。发布器使用
 * {@code FOR UPDATE SKIP LOCKED} 原子抢占待发送事件，成功后标记 PUBLISHED，失败后
 * 恢复为 PENDING 并退避重试，从而避免“业务已提交但消息丢失”的双写问题。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Component
public class OutboxPublisher {
    /** 发布失败日志记录器。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Outbox 抢占与状态更新持久化接口。 */
    private final OutboxMapper outboxMapper;
    /** Kafka 发送客户端。 */
    private final KafkaTemplate<String, Object> kafka;
    /** 结算事件 Topic。 */
    private final String settlementTopic;
    /** 撮合事件 Topic。 */
    private final String matchingTopic;
    /** 当前 Publisher 唯一标识。 */
    private final String publisherId;

    /** 创建 Outbox 发布器并注入 Topic 与 Worker 标识。 */
    public OutboxPublisher(OutboxMapper outboxMapper, KafkaTemplate<String, Object> kafka,
                           @Value("${fincore.kafka.outbox-topic}") String settlementTopic,
                           @Value("${fincore.kafka.matching-topic}") String matchingTopic,
                           @Value("${fincore.worker.id:${HOSTNAME:local-worker}}") String publisherId) {
        this.outboxMapper = outboxMapper;
        this.kafka = kafka;
        this.settlementTopic = settlementTopic;
        this.matchingTopic = matchingTopic;
        this.publisherId = publisherId;
    }

    /**
     * 原子抢占并发布一批待发送事件。
     *
     * <p>每个事件只有抢占它的 Publisher 才能更新最终状态；Kafka 发送异常不会被
     * 当作成功，而是增加重试次数并设置下一次可尝试时间。</p>
     */
    @Scheduled(fixedDelayString = "${fincore.outbox-delay-ms:1000}")
    public void publishBatch() {
        List<OutboxMapper.OutboxEventRow> events = outboxMapper.claimBatch(publisherId);
        for (OutboxMapper.OutboxEventRow event : events) {
            UUID id = event.eventId();
            String eventType = event.eventType();
            String topic = eventType.startsWith("MATCHING_") ? matchingTopic : settlementTopic;
            try {
                kafka.send(topic, event.aggregateId(), event.payload()).get();
                outboxMapper.markPublished(id, publisherId);
            } catch (Exception exception) {
                // 发送失败必须保留事件并重试，不能删除或伪装成已发布。
                LOGGER.warn("Outbox 事件发布失败，稍后重试：eventId={}, publisherId={}",
                    id, publisherId, exception);
                outboxMapper.releaseForRetry(id, publisherId);
            }
        }
    }

    /**
     * 回收因 Publisher 崩溃而长时间停留在 PROCESSING 的抢占记录。
     *
     * <p>只恢复超过 60 秒的记录，避免与仍在正常发送的 Publisher 竞争。</p>
     */
    @Scheduled(fixedDelayString = "${fincore.outbox-recovery-delay-ms:30000}")
    public void recoverAbandonedClaims() {
        outboxMapper.recoverAbandonedClaims();
    }
}
