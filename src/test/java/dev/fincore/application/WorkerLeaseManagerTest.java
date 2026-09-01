package dev.fincore.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.fincore.domain.FenceToken;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Worker Lease 缓存和数据面围栏令牌协调测试。 */
class WorkerLeaseManagerTest {

    /** 同一分片的并发消息只触发一次控制面续期，但每条消息都得到相同 Epoch。 */
    @Test
    void concurrentMessagesShareOneFreshLease() throws Exception {
        ShardLeaseService leases = Mockito.mock(ShardLeaseService.class);
        ConcurrencyProperties properties = properties();
        when(leases.acquireOrRenew(3, "worker-a", Duration.ofSeconds(30)))
            .thenReturn(new ShardLeaseService.Lease(
                3, "worker-a", 7, "RUNNING", Instant.now().plusSeconds(30)));
        WorkerLeaseManager manager = new WorkerLeaseManager(
            leases, properties, new SimpleMeterRegistry());

        List<FenceToken> tokens = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<FenceToken>> futures = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                futures.add(executor.submit(() -> manager.currentFence(3, "worker-a")));
            }
            for (java.util.concurrent.Future<FenceToken> future : futures) {
                tokens.add(future.get());
            }
        }

        assertEquals(64, tokens.size());
        assertEquals(1, tokens.stream().distinct().count());
        assertEquals(7, tokens.getFirst().epoch());
        verify(leases, times(1)).acquireOrRenew(3, "worker-a", Duration.ofSeconds(30));
    }

    /** 只允许被拒绝的旧 Epoch 清除缓存，下一次消息再重新获取所有权。 */
    @Test
    void invalidationIsCompareAndRemoveByEpoch() {
        ShardLeaseService leases = Mockito.mock(ShardLeaseService.class);
        ConcurrencyProperties properties = properties();
        when(leases.acquireOrRenew(1, "worker-a", Duration.ofSeconds(30)))
            .thenReturn(
                new ShardLeaseService.Lease(
                    1, "worker-a", 4, "RUNNING", Instant.now().plusSeconds(30)),
                new ShardLeaseService.Lease(
                    1, "worker-a", 5, "RUNNING", Instant.now().plusSeconds(30))
            );
        WorkerLeaseManager manager = new WorkerLeaseManager(
            leases, properties, new SimpleMeterRegistry());

        assertEquals(4, manager.currentFence(1, "worker-a").epoch());
        manager.invalidate(1, 3);
        assertEquals(4, manager.currentFence(1, "worker-a").epoch());
        manager.invalidate(1, 4);
        assertEquals(5, manager.currentFence(1, "worker-a").epoch());

        verify(leases, times(2)).acquireOrRenew(1, "worker-a", Duration.ofSeconds(30));
    }

    /** 构造测试所需的 Lease 时间参数。 */
    private static ConcurrencyProperties properties() {
        ConcurrencyProperties properties = new ConcurrencyProperties();
        properties.setWorkerLeaseTtl(Duration.ofSeconds(30));
        properties.setWorkerLeaseRenewAhead(Duration.ofSeconds(10));
        return properties;
    }
}
