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

@Component
public class SettlementListener {
    private final SettlementService service;
    private final ShardLeaseService leases;
    private final ShardRouter router;
    private final String workerId;

    public SettlementListener(SettlementService service, ShardLeaseService leases,
                              @Value("${fincore.worker.shard-count:8}") int shardCount,
                              @Value("${fincore.worker.id:${HOSTNAME:local-worker}}") String workerId) {
        this.service = service;
        this.leases = leases;
        this.router = new ShardRouter(shardCount);
        this.workerId = workerId;
    }

    @KafkaListener(topics = "${fincore.kafka.settlement-topic}")
    public void onCommand(SettlementCommand command) {
        int shardId = router.shardFor(command.payerAccountId().toString());
        ShardLeaseService.Lease lease = leases.acquireOrRenew(shardId, workerId, Duration.ofSeconds(30));
        service.settle(command, new FenceToken(shardId, workerId, lease.epoch()));
    }
}
