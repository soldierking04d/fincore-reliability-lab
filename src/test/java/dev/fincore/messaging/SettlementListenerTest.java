package dev.fincore.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.fincore.application.FenceRejectedException;
import dev.fincore.application.SettlementService;
import dev.fincore.application.SpotDeliveryService;
import dev.fincore.application.WorkerLeaseManager;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SpotDeliveryCommand;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

/** 结算监听器围栏缓存失效行为测试。 */
class SettlementListenerTest {
    /** 测试 Worker 标识。 */
    private static final String WORKER_ID = "worker-test";
    /** 测试 Epoch。 */
    private static final long EPOCH = 7L;

    /** 明确的围栏拒绝必须清除相同 Epoch 的本地 Lease 缓存。 */
    @Test
    void typedFenceRejectionInvalidatesCachedLease() {
        Fixture fixture = fixture();
        doThrow(new FenceRejectedException("diagnostic text can change"))
            .when(fixture.settlements()).settle(fixture.command(), fixture.fence());

        assertThrows(FenceRejectedException.class, () -> fixture.listener().onCommand(fixture.record()));

        verify(fixture.leases()).invalidate(anyInt(), eq(EPOCH));
    }

    /** 文案恰好以 fence rejected 开头的普通故障不能改变 Lease 所有权缓存。 */
    @Test
    void similarExceptionMessageDoesNotDriveFenceControlFlow() {
        Fixture fixture = fixture();
        doThrow(new IllegalStateException("fence rejected: unrelated internal failure"))
            .when(fixture.settlements()).settle(fixture.command(), fixture.fence());

        assertThrows(IllegalStateException.class, () -> fixture.listener().onCommand(fixture.record()));

        verify(fixture.leases(), never()).invalidate(anyInt(), eq(EPOCH));
    }

    /** 路由查库、获取 Lease 和资金事务都属于同步消费者占用时长。 */
    @Test
    void spotRoutingLeaseAndTransactionAreIncludedInProcessingAndInflight() {
        Fixture fixture = fixture();
        SpotDeliveryCommand command = new SpotDeliveryCommand("spot-message", UUID.randomUUID());
        when(fixture.deliveries().shardFor(command.tradeId())).thenAnswer(call -> {
            assertEquals(1, fixture.registry().get("fincore.settlement.consumer.inflight").gauge().value());
            fixture.clock().add(Duration.ofMillis(3));
            return 0;
        });
        when(fixture.leases().currentFence(0, WORKER_ID)).thenAnswer(call -> {
            assertEquals(1, fixture.registry().get("fincore.settlement.consumer.inflight").gauge().value());
            fixture.clock().add(Duration.ofMillis(5));
            return fixture.fence();
        });
        when(fixture.deliveries().settle(command, fixture.fence())).thenAnswer(call -> {
            assertEquals(1, fixture.registry().get("fincore.settlement.consumer.inflight").gauge().value());
            fixture.clock().add(Duration.ofMillis(7));
            return null;
        });

        fixture.listener().onCommand(new ConsumerRecord<>("spot", 0, 0, "key", command));

        assertEquals(0, fixture.registry().get("fincore.settlement.consumer.inflight").gauge().value());
        Timer processing = fixture.registry().get("fincore.settlement.consumer.processing")
            .tags("type", "spot", "outcome", "success").timer();
        assertEquals(1, processing.count());
        assertEquals(15, processing.totalTime(TimeUnit.MILLISECONDS));
        assertStage(fixture, "spot", "routing", "success", 3);
        assertStage(fixture, "spot", "lease", "success", 5);
        assertStage(fixture, "spot", "transaction", "success", 7);
    }

    /** 路由失败仍被记录且不能进入租约和资金事务。 */
    @Test
    void routingFailureIsMeasuredAndInflightIsReleased() {
        Fixture fixture = fixture();
        SpotDeliveryCommand command = new SpotDeliveryCommand("spot-message", UUID.randomUUID());
        when(fixture.deliveries().shardFor(command.tradeId())).thenThrow(new IllegalStateException("missing trade"));

        assertThrows(IllegalStateException.class, () -> fixture.listener().onCommand(
            new ConsumerRecord<>("spot", 0, 0, "key", command)));

        assertEquals(0, fixture.registry().get("fincore.settlement.consumer.inflight").gauge().value());
        assertEquals(1, fixture.registry().get("fincore.settlement.consumer.processing")
            .tags("type", "spot", "outcome", "failure").timer().count());
        assertStage(fixture, "spot", "routing", "failure", 0);
        verify(fixture.leases(), never()).currentFence(anyInt(), eq(WORKER_ID));
    }

    /** 租约失败与资金围栏失败各自有低基数结果，不能误计成功消费。 */
    @Test
    void leaseRejectionIsMeasuredWithoutInvalidatingAnUnknownFence() {
        Fixture fixture = fixture();
        when(fixture.leases().currentFence(0, WORKER_ID)).thenThrow(new FenceRejectedException("no lease"));

        assertThrows(FenceRejectedException.class, () -> fixture.listener().onCommand(fixture.record()));

        assertEquals(0, fixture.registry().get("fincore.settlement.consumer.inflight").gauge().value());
        assertEquals(1, fixture.registry().get("fincore.settlement.consumer.processing")
            .tags("type", "settlement", "outcome", "fence_rejected").timer().count());
        assertStage(fixture, "settlement", "lease", "fence_rejected", 0);
        verify(fixture.leases(), never()).invalidate(anyInt(), eq(EPOCH));
        verify(fixture.settlements(), never()).settle(fixture.command(), fixture.fence());
    }

    /** 阶段断言使用可控时钟，不用 sleep 猜测时长。 */
    private static void assertStage(Fixture fixture, String type, String stage, String outcome, int millis) {
        Timer timer = fixture.registry().get("fincore.settlement.consumer.stage")
            .tags("type", type, "stage", stage, "outcome", outcome).timer();
        assertEquals(1, timer.count());
        assertEquals(millis, timer.totalTime(TimeUnit.MILLISECONDS));
    }

    /** 创建隔离的监听器依赖和命令。 */
    private static Fixture fixture() {
        SettlementService settlements = mock(SettlementService.class);
        SpotDeliveryService deliveries = mock(SpotDeliveryService.class);
        WorkerLeaseManager leases = mock(WorkerLeaseManager.class);
        SettlementCommand command = new SettlementCommand(
            "message-listener",
            "business-listener",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "USDT",
            BigDecimal.ONE,
            new BigDecimal("0.01")
        );
        FenceToken fence = new FenceToken(0, WORKER_ID, EPOCH);
        when(leases.currentFence(anyInt(), eq(WORKER_ID))).thenReturn(fence);
        MockClock clock = new MockClock();
        SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
        SettlementListener listener = new SettlementListener(
            settlements,
            leases,
            1,
            WORKER_ID,
            registry,
            deliveries
        );
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "settlement.commands.v1",
            0,
            0L,
            command.businessKey(),
            command
        );
        return new Fixture(listener, settlements, leases, command, fence, record, deliveries, registry, clock);
    }

    /** 监听器测试所需的不可变依赖集合。 */
    private record Fixture(SettlementListener listener, SettlementService settlements,
                           WorkerLeaseManager leases, SettlementCommand command, FenceToken fence,
                           ConsumerRecord<String, Object> record, SpotDeliveryService deliveries,
                           SimpleMeterRegistry registry, MockClock clock) {
    }
}
