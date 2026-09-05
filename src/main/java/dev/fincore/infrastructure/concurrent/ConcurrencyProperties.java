package dev.fincore.infrastructure.concurrent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高并发执行器和批处理参数。
 *
 * <p><strong>解决的问题：</strong>把线程、队列、等待时间、Lease 和批量大小变成可校验的容量合同，
 * 避免生产环境用临时参数把背压转移为内存、锁或连接耗尽。</p>
 *
 * <p><strong>CPU 关系：</strong>Lane、Consumer 和调度线程决定平台线程并行度，队列决定在途对象
 * 数量，批次决定每次数据库与 Kafka 调用的固定成本。三者必须与容器 CPU、连接池和下游能力一起
 * 压测，不能分别调大。配置上限只防止明显错误，不代表上限值适合生产。</p>
 *
 * <p><strong>边界：</strong>所有容量都必须有界；启动校验只验证结构合法，真实容量仍由 P95/P99、
 * 队列等待、拒绝率、GC、数据库 CPU/锁等待和 Kafka Lag 共同验收。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
@ConfigurationProperties(prefix = "fincore.concurrency")
public class ConcurrencyProperties {
    /** 单实例撮合平台线程数，也是不同交易对在本实例的最大 CPU 并行度。 */
    private int matchingLanes = 4;
    /** 每个 Lane 的普通命令上限；限制排队对象、引用链和堆占用。 */
    private int matchingQueueCapacityPerLane = 256;
    /** 每个 Lane 专门保留给撤单的容量，防止新单洪峰耗尽全部准入空间。 */
    private int matchingCancelQueueCapacityPerLane = 32;
    /** HTTP 等待撮合结果的最长时间；超时不取消可能已经开始的事务。 */
    private Duration matchingWaitTimeout = Duration.ofSeconds(10);
    /** 单实例 Kafka 平台线程数；运行时还会受容器可用处理器数量限制。 */
    private int settlementConsumers = 4;
    /** HTTP 提交等待 Kafka Broker 确认的最长时间。 */
    private Duration kafkaSubmitTimeout = Duration.ofSeconds(3);
    /** Worker Lease 有效期；过短增加续期写入，过长会延迟故障接管。 */
    private Duration workerLeaseTtl = Duration.ofSeconds(30);
    /** 提前续期窗口；把续期 I/O 从每条消息降为每个分片每个窗口一次。 */
    private Duration workerLeaseRenewAhead = Duration.ofSeconds(10);
    /** Outbox 单次抢占上限；平衡 JDBC/Kafka 固定成本、临时对象与确认尾延迟。 */
    private int outboxBatchSize = 200;
    /** 等待一批 Kafka 发送确认的最长时间。 */
    private Duration outboxAwaitTimeout = Duration.ofSeconds(15);
    /** 后台平台线程数；与撮合和 Kafka 隔离，避免后台扫描争抢热路径执行器。 */
    private int schedulerThreads = 2;

    /**
     * 校验容量参数，拒绝可能造成无界排队或明显资源耗尽的启动配置。
     *
     * <p>这里只做失败快速的静态检查，不按 CPU 自动放大 Lane、队列或批次；自动放大会使同一镜像
     * 在不同配额下产生不可预测的金融请求排队语义。</p>
     */
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

    /**
     * 返回不超过 JVM 容器感知处理器数量的有效 Kafka Consumer 并发。
     *
     * <p>{@code availableProcessors()} 在启用容器支持时读取 CPU quota；该值只限制平台线程过度订阅，
     * Topic 分区数和全部实例 Consumer 总数仍需在部署层共同规划。</p>
     */
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
