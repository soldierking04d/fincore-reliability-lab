package dev.fincore.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.SpotDeliveryCommand;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(
    name = "spring.task.scheduling.enabled",
    havingValue = "true",
    matchIfMissing = true
)
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
    /** 成交驱动双资产交割命令 Topic。 */
    private final String spotTopic;
    /** 将已持久化交割通知还原为类型化命令，保留 Kafka JSON 类型头。 */
    private final ObjectMapper json;
    /** 当前 Publisher 唯一标识。 */
    private final String publisherId;
    /** 单次原子抢占的最大事件数。 */
    private final int batchSize;
    /** 一批 Kafka 异步确认的最长等待时间。 */
    private final Duration awaitTimeout;
    /** 最近一次观测的可发布积压数。 */
    private final AtomicLong readyBacklog = new AtomicLong();
    /** 已抢占事件计数。 */
    private final Counter claimed;
    /** 已确认发布事件计数。 */
    private final Counter published;
    /** 明确发送失败事件计数。 */
    private final Counter failed;
    /** 等待超时且结果未知事件计数。 */
    private final Counter uncertain;
    /** 整批发布耗时。 */
    private final Timer batchTimer;

    /** 创建 Outbox 发布器并注入 Topic 与 Worker 标识。 */
    public OutboxPublisher(OutboxMapper outboxMapper, KafkaTemplate<String, Object> kafka,
                           @Value("${fincore.kafka.outbox-topic}") String settlementTopic,
                           @Value("${fincore.kafka.matching-topic}") String matchingTopic,
                           @Value("${fincore.kafka.spot-topic:spot.delivery.commands.v1}") String spotTopic,
                           @Value("${fincore.worker.id:${HOSTNAME:local-worker}}") String publisherId,
                           ConcurrencyProperties properties, MeterRegistry registry, ObjectMapper json) {
        this.outboxMapper = outboxMapper;
        this.kafka = kafka;
        this.settlementTopic = settlementTopic;
        this.matchingTopic = matchingTopic;
        this.spotTopic = spotTopic;
        this.json = json;
        this.publisherId = publisherId;
        this.batchSize = properties.getOutboxBatchSize();
        this.awaitTimeout = properties.getOutboxAwaitTimeout();
        this.claimed = registry.counter("fincore.outbox.claimed");
        this.published = registry.counter("fincore.outbox.published");
        this.failed = registry.counter("fincore.outbox.failed");
        this.uncertain = registry.counter("fincore.outbox.uncertain");
        this.batchTimer = registry.timer("fincore.outbox.publish.batch");
        Gauge.builder("fincore.outbox.ready.backlog", readyBacklog, AtomicLong::get)
            .description("当前已到重试时间的 Outbox 事件数量")
            .register(registry);
    }

    /**
     * 原子抢占并发布一批待发送事件。
     *
     * <p>每个事件只有抢占它的 Publisher 才能更新最终状态；Kafka 发送异常不会被
     * 当作成功，而是增加重试次数并设置下一次可尝试时间。</p>
     */
    @Scheduled(fixedDelayString = "${fincore.outbox-delay-ms:1000}")
    public void publishBatch() {
        Timer.Sample sample = Timer.start();
        try {
            List<OutboxMapper.OutboxEventRow> events = outboxMapper.claimBatch(publisherId, batchSize);
            readyBacklog.set(outboxMapper.countReadyBacklog());
            if (events.isEmpty()) {
                return;
            }
            claimed.increment(events.size());
            publishClaimed(events);
        } finally {
            sample.stop(batchTimer);
        }
    }

    /** 异步发送整个批次，并把明确成功和明确失败分别批量回写数据库。 */
    private void publishClaimed(List<OutboxMapper.OutboxEventRow> events) {
        List<PendingSend> sends = new ArrayList<>(events.size());
        for (OutboxMapper.OutboxEventRow event : events) {
            String topic = event.eventType().startsWith("MATCHING_") ? matchingTopic : settlementTopic;
            CompletableFuture<SendResult> future;
            try {
                boolean delivery = "SPOT_DVP_REQUESTED".equals(event.eventType());
                Object payload = delivery ? deliveryCommand(event.payload()) : event.payload();
                future = kafka.send(delivery ? spotTopic : topic, event.aggregateId(), payload)
                    .handle((result, exception) -> new SendResult(event.eventId(), exception));
            } catch (RuntimeException exception) {
                future = CompletableFuture.completedFuture(new SendResult(event.eventId(), exception));
            }
            sends.add(new PendingSend(event.eventId(), future));
        }

        try {
            CompletableFuture.allOf(sends.stream()
                .map(PendingSend::future)
                .toArray(CompletableFuture[]::new))
                .get(awaitTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            LOGGER.warn("Outbox 批次等待 Kafka 确认超时，未完成记录留给异常抢占恢复：publisherId={}",
                publisherId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Outbox 发布线程被中断，未完成记录留给异常抢占恢复：publisherId={}", publisherId);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("Outbox send result aggregation failed", exception);
        }

        List<UUID> successes = new ArrayList<>(sends.size());
        List<UUID> failures = new ArrayList<>();
        int incomplete = 0;
        for (PendingSend send : sends) {
            if (!send.future().isDone()) {
                incomplete++;
                continue;
            }
            SendResult result = send.future().getNow(null);
            if (result.failure() == null) {
                successes.add(result.eventId());
            } else {
                failures.add(result.eventId());
                LOGGER.warn("Outbox 事件发布失败，稍后退避重试：eventId={}, publisherId={}",
                    result.eventId(), publisherId, result.failure());
            }
        }
        if (!successes.isEmpty()) {
            published.increment(outboxMapper.markPublishedBatch(successes, publisherId));
        }
        if (!failures.isEmpty()) {
            failed.increment(outboxMapper.releaseForRetryBatch(failures, publisherId));
        }
        if (incomplete > 0) {
            uncertain.increment(incomplete);
        }
    }

    /** 解析错误不能标记已发布，保留原事件供修正后重试。 */
    private SpotDeliveryCommand deliveryCommand(String payload) {
        try {
            return json.readValue(payload, SpotDeliveryCommand.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid persisted delivery command", exception);
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

    /** 单个已提交 Kafka 发送及其事件编号。 */
    private record PendingSend(UUID eventId, CompletableFuture<SendResult> future) {
    }

    /** Kafka 确认结果；failure 为空表示 Broker 已确认。 */
    private record SendResult(UUID eventId, Throwable failure) {
    }
}
