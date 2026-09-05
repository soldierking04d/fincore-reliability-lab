package dev.fincore.messaging;

import dev.fincore.application.FenceRejectedException;
import dev.fincore.application.SettlementService;
import dev.fincore.application.SpotDeliveryService;
import dev.fincore.application.WorkerLeaseManager;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.ShardRouter;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SpotDeliveryCommand;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka 结算与现货交割命令的同步消费入口。
 *
 * <p><strong>解决的问题：</strong>消费重放、节点短暂失联和 Worker 接管都可能让旧节点继续处理
 * 消息。本组件先按稳定业务键路由分片，获取当前 Lease/Epoch，再把不可变 {@link FenceToken}
 * 传入资金事务；异常继续抛给容器，禁止“记录错误日志后仍提交 offset”。</p>
 *
 * <p><strong>线程与 CPU 优化：</strong>结算和现货交割共用固定的平台线程池，消费者并发度由
 * {@code min(配置值, 可用 CPU)} 限定。处理链保持同步，不再派生第二层异步任务，避免线程切换、
 * 顺序失真和无界 Future；Lease 快照短期缓存让正常消息只承担一次哈希路由、一次 Map 读取及资金事务。</p>
 *
 * <p><strong>正确性边界：</strong>缓存中的 Epoch 只是候选令牌，不是授权结果。余额、分录、inbox、
 * 状态和围栏必须在同一数据库事务中再次校验并提交；即使旧 Worker 在网络恢复后继续消费，也会被
 * 数据面 Fencing 拒绝。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Component
public class SettlementListener {
    /** 结算应用服务。 */
    private final SettlementService service;
    /** 现货交割复用同一有界消费者池，不为每类 Topic 再创建一组线程。 */
    private final SpotDeliveryService spot;
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
                              MeterRegistry registry, SpotDeliveryService spot) {
        this.service = service;
        this.spot = spot;
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
     * @param record Kafka 消息；明确从 value 读取类型化命令，避免 Object 参数被解析为消息包装器
     */
    @KafkaListener(
        topics = {"${fincore.kafka.settlement-topic}", "${fincore.kafka.spot-topic:spot.delivery.commands.v1}"},
        containerFactory = "settlementKafkaListenerContainerFactory"
    )
    public void onCommand(ConsumerRecord<String, Object> record) {
        // 监听器线程同步完成整笔事务；方法成功返回后容器才允许提交该记录的 offset。
        Object command = record.value();
        int shardId;
        if (command instanceof SettlementCommand settlement) {
            shardId = router.shardFor(settlement.payerAccountId().toString());
        } else if (command instanceof SpotDeliveryCommand delivery) {
            shardId = spot.shardFor(delivery.tradeId());
        } else {
            throw new IllegalArgumentException("unsupported financial command type");
        }
        FenceToken fence = leases.currentFence(shardId, workerId);
        Timer.Sample sample = Timer.start();
        inFlight.incrementAndGet();
        try {
            if (command instanceof SettlementCommand settlement) {
                service.settle(settlement, fence);
            } else {
                // 现货交割复用同一执行预算，避免新增 Topic 时按默认值再膨胀一组 CPU 竞争线程。
                spot.settle((SpotDeliveryCommand) command, fence);
            }
        } catch (FenceRejectedException exception) {
            // 只有明确的围栏异常可以改变 Lease 缓存；普通异常即使文案相似也不能触发所有权控制流。
            leases.invalidate(shardId, fence.epoch());
            throw exception;
        } finally {
            inFlight.decrementAndGet();
            sample.stop(processingTimer);
        }
    }
}
