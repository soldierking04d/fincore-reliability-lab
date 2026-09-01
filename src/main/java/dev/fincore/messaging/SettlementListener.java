package dev.fincore.messaging;

import dev.fincore.application.SettlementService;
import dev.fincore.application.WorkerLeaseManager;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.ShardRouter;
import dev.fincore.domain.SettlementCommand;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 结算命令消费者。
 *
 * <p>消费者先根据付款账户路由分片，再获取或续期 Lease，并把当前 Epoch 作为
 * {@link FenceToken} 传入资金事务。即使旧 Worker 在网络恢复后继续消费，也会在
 * 数据面 Fencing 校验中被拒绝。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Component
public class SettlementListener {
    /** 结算应用服务。 */
    private final SettlementService service;
    /** 分片 Lease 服务。 */
    private final WorkerLeaseManager leases;
    /** 付款账户到 Worker 分片的路由器。 */
    private final ShardRouter router;
    /** 当前 Worker 唯一标识。 */
    private final String workerId;
    /** 当前正在执行的结算消息数。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    /** 单条结算消息的端到端处理时长。 */
    private final Timer processingTimer;

    /** 创建结算消息消费者。 */
    public SettlementListener(SettlementService service, WorkerLeaseManager leases,
                              @Value("${fincore.worker.shard-count:8}") int shardCount,
                              @Value("${fincore.worker.id:${HOSTNAME:local-worker}}") String workerId,
                              MeterRegistry registry) {
        this.service = service;
        this.leases = leases;
        this.router = new ShardRouter(shardCount);
        this.workerId = workerId;
        this.processingTimer = registry.timer("fincore.settlement.consumer.processing");
        Gauge.builder("fincore.settlement.consumer.inflight", inFlight, AtomicInteger::get)
            .description("当前正在执行的结算 Kafka 消息数")
            .register(registry);
    }

    /**
     * 消费结算命令并在有效围栏保护下执行资金事务。
     *
     * @param command Kafka 反序列化后的结算命令
     */
    @KafkaListener(
        topics = "${fincore.kafka.settlement-topic}",
        containerFactory = "settlementKafkaListenerContainerFactory"
    )
    public void onCommand(SettlementCommand command) {
        int shardId = router.shardFor(command.payerAccountId().toString());
        FenceToken fence = leases.currentFence(shardId, workerId);
        Timer.Sample sample = Timer.start();
        inFlight.incrementAndGet();
        try {
            service.settle(command, fence);
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().startsWith("fence rejected:")) {
                leases.invalidate(shardId, fence.epoch());
            }
            throw exception;
        } finally {
            inFlight.decrementAndGet();
            sample.stop(processingTimer);
        }
    }
}
