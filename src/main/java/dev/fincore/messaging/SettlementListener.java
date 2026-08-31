package dev.fincore.messaging;

import dev.fincore.application.SettlementService;
import dev.fincore.application.ShardLeaseService;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.ShardRouter;
import dev.fincore.domain.SettlementCommand;
import java.time.Duration;
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
    private final ShardLeaseService leases;
    /** 付款账户到 Worker 分片的路由器。 */
    private final ShardRouter router;
    /** 当前 Worker 唯一标识。 */
    private final String workerId;

    /** 创建结算消息消费者。 */
    public SettlementListener(SettlementService service, ShardLeaseService leases,
                              @Value("${fincore.worker.shard-count:8}") int shardCount,
                              @Value("${fincore.worker.id:${HOSTNAME:local-worker}}") String workerId) {
        this.service = service;
        this.leases = leases;
        this.router = new ShardRouter(shardCount);
        this.workerId = workerId;
    }

    /**
     * 消费结算命令并在有效围栏保护下执行资金事务。
     *
     * @param command Kafka 反序列化后的结算命令
     */
    @KafkaListener(topics = "${fincore.kafka.settlement-topic}")
    public void onCommand(SettlementCommand command) {
        int shardId = router.shardFor(command.payerAccountId().toString());
        ShardLeaseService.Lease lease = leases.acquireOrRenew(shardId, workerId, Duration.ofSeconds(30));
        service.settle(command, new FenceToken(shardId, workerId, lease.epoch()));
    }
}
