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
import java.util.function.Supplier;
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
    /** 计时使用同一个注册表时钟，标签只允许固定业务类型、阶段和结果。 */
    private final MeterRegistry registry;

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
        this.registry = registry;
        Gauge.builder("fincore.settlement.consumer.inflight", inFlight, AtomicInteger::get)
            .description("当前处于路由、获取租约或事务处理中的金融 Kafka 消息数")
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
        Timer.Sample sample = Timer.start(registry);
        inFlight.incrementAndGet();
        String type = "unsupported";
        String outcome = "failure";
        try {
            Object command = record.value();
            type = command instanceof SettlementCommand ? "settlement"
                : command instanceof SpotDeliveryCommand ? "spot" : "unsupported";
            int shardId = timedStage(type, "routing", () -> shardFor(command));
            FenceToken fence = timedStage(type, "lease", () -> leases.currentFence(shardId, workerId));
            timedStage(type, "transaction", () -> {
                try {
                    if (command instanceof SettlementCommand settlement) {
                        service.settle(settlement, fence);
                    } else {
                        spot.settle((SpotDeliveryCommand) command, fence);
                    }
                    return null;
                } catch (FenceRejectedException exception) {
                    // 只有已获取令牌后的明确围栏拒绝才使对应 Epoch 缓存失效。
                    leases.invalidate(shardId, fence.epoch());
                    throw exception;
                }
            });
            // 这里表示同步消费成功返回，包含已提交重复/业务失败结果；新增金融成功看事务完成计数。
            outcome = "success";
        } catch (RuntimeException | Error exception) {
            outcome = outcome(exception);
            throw exception;
        } finally {
            inFlight.decrementAndGet();
            sample.stop(registry.timer("fincore.settlement.consumer.processing", "type", type, "outcome", outcome));
        }
    }

    /** 路由包含现货权威事实查询；未知类型也必须保留失败耗时。 */
    private int shardFor(Object command) {
        if (command instanceof SettlementCommand settlement) {
            return router.shardFor(settlement.payerAccountId().toString());
        }
        if (command instanceof SpotDeliveryCommand delivery) {
            return spot.shardFor(delivery.tradeId());
        }
        throw new IllegalArgumentException("unsupported financial command type");
    }

    /** 每个阶段的失败独立计时，不使用账户、成交、消息编号或异常文案作标签。 */
    private <T> T timedStage(String type, String stage, Supplier<T> action) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "failure";
        try {
            T result = action.get();
            outcome = "success";
            return result;
        } catch (RuntimeException | Error exception) {
            outcome = outcome(exception);
            throw exception;
        } finally {
            sample.stop(registry.timer("fincore.settlement.consumer.stage",
                "type", type, "stage", stage, "outcome", outcome));
        }
    }

    /** 固定结果集合避免异常类名、错误消息或业务键导致时序基数无限增长。 */
    private static String outcome(Throwable exception) {
        return exception instanceof FenceRejectedException ? "fence_rejected" : "failure";
    }
}
