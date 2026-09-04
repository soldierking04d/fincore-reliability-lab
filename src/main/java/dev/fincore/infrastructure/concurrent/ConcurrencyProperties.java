package dev.fincore.infrastructure.concurrent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高并发执行器和批处理参数。
 *
 * <p>所有容量都必须是有界值。线程数、队列和数据库连接池不能独立无限放大，否则只会把
 * 背压从入口转移为锁等待、上下文切换和数据库连接等待。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
@ConfigurationProperties(prefix = "fincore.concurrency")
public class ConcurrencyProperties {
    /** 单实例撮合 Lane 数量。 */
    private int matchingLanes = 4;
    /** 每个撮合 Lane 的最大排队任务数。 */
    private int matchingQueueCapacityPerLane = 256;
    /** 每个撮合 Lane 专门保留给撤单命令的排队容量。 */
    private int matchingCancelQueueCapacityPerLane = 32;
    /** HTTP 等待撮合结果的最长时间。 */
    private Duration matchingWaitTimeout = Duration.ofSeconds(10);
    /** 单实例 Kafka 结算消费者数量。 */
    private int settlementConsumers = 4;
    /** HTTP 提交等待 Kafka Broker 确认的最长时间。 */
    private Duration kafkaSubmitTimeout = Duration.ofSeconds(3);
    /** Worker Lease 有效期。 */
    private Duration workerLeaseTtl = Duration.ofSeconds(30);
    /** 距离到期不足该时长时提前续期。 */
    private Duration workerLeaseRenewAhead = Duration.ofSeconds(10);
    /** Outbox 单次抢占上限。 */
    private int outboxBatchSize = 200;
    /** 等待一批 Kafka 发送确认的最长时间。 */
    private Duration outboxAwaitTimeout = Duration.ofSeconds(15);
    /** 平台线程调度器线程数。 */
    private int schedulerThreads = 2;

    /** 校验容量参数，拒绝可能造成无界排队或资源耗尽的启动配置。 */
    public void validate() {
        requireRange("matchingLanes", matchingLanes, 1, 64);
        requireRange("matchingQueueCapacityPerLane", matchingQueueCapacityPerLane, 1, 100_000);
        requireRange("matchingCancelQueueCapacityPerLane", matchingCancelQueueCapacityPerLane, 1, 10_000);
        requirePositive("matchingWaitTimeout", matchingWaitTimeout);
        requireRange("settlementConsumers", settlementConsumers, 1, 64);
        requirePositive("kafkaSubmitTimeout", kafkaSubmitTimeout);
        requirePositive("workerLeaseTtl", workerLeaseTtl);
        requirePositive("workerLeaseRenewAhead", workerLeaseRenewAhead);
        if (workerLeaseRenewAhead.compareTo(workerLeaseTtl) >= 0) {
            throw new IllegalArgumentException("workerLeaseRenewAhead must be shorter than workerLeaseTtl");
        }
        requireRange("outboxBatchSize", outboxBatchSize, 1, 2_000);
        requirePositive("outboxAwaitTimeout", outboxAwaitTimeout);
        requireRange("schedulerThreads", schedulerThreads, 1, 16);
    }

    /** 校验整数位于闭区间。 */
    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    /** 校验时长为正数。 */
    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    public int getMatchingLanes() {
        return matchingLanes;
    }

    public void setMatchingLanes(int matchingLanes) {
        this.matchingLanes = matchingLanes;
    }

    public int getMatchingQueueCapacityPerLane() {
        return matchingQueueCapacityPerLane;
    }

    public void setMatchingQueueCapacityPerLane(int matchingQueueCapacityPerLane) {
        this.matchingQueueCapacityPerLane = matchingQueueCapacityPerLane;
    }

    public int getMatchingCancelQueueCapacityPerLane() {
        return matchingCancelQueueCapacityPerLane;
    }

    public void setMatchingCancelQueueCapacityPerLane(int matchingCancelQueueCapacityPerLane) {
        this.matchingCancelQueueCapacityPerLane = matchingCancelQueueCapacityPerLane;
    }

    public Duration getMatchingWaitTimeout() {
        return matchingWaitTimeout;
    }

    public void setMatchingWaitTimeout(Duration matchingWaitTimeout) {
        this.matchingWaitTimeout = matchingWaitTimeout;
    }

    public int getSettlementConsumers() {
        return settlementConsumers;
    }

    /** 返回不超过 JVM 容器感知处理器数量的有效 Kafka Consumer 并发。 */
    public int effectiveSettlementConsumers() {
        return Math.min(settlementConsumers, Math.max(1, Runtime.getRuntime().availableProcessors()));
    }

    public void setSettlementConsumers(int settlementConsumers) {
        this.settlementConsumers = settlementConsumers;
    }

    public Duration getKafkaSubmitTimeout() {
        return kafkaSubmitTimeout;
    }

    public void setKafkaSubmitTimeout(Duration kafkaSubmitTimeout) {
        this.kafkaSubmitTimeout = kafkaSubmitTimeout;
    }

    public Duration getWorkerLeaseTtl() {
        return workerLeaseTtl;
    }

    public void setWorkerLeaseTtl(Duration workerLeaseTtl) {
        this.workerLeaseTtl = workerLeaseTtl;
    }

    public Duration getWorkerLeaseRenewAhead() {
        return workerLeaseRenewAhead;
    }

    public void setWorkerLeaseRenewAhead(Duration workerLeaseRenewAhead) {
        this.workerLeaseRenewAhead = workerLeaseRenewAhead;
    }

    public int getOutboxBatchSize() {
        return outboxBatchSize;
    }

    public void setOutboxBatchSize(int outboxBatchSize) {
        this.outboxBatchSize = outboxBatchSize;
    }

    public Duration getOutboxAwaitTimeout() {
        return outboxAwaitTimeout;
    }

    public void setOutboxAwaitTimeout(Duration outboxAwaitTimeout) {
        this.outboxAwaitTimeout = outboxAwaitTimeout;
    }

    public int getSchedulerThreads() {
        return schedulerThreads;
    }

    public void setSchedulerThreads(int schedulerThreads) {
        this.schedulerThreads = schedulerThreads;
    }
}
