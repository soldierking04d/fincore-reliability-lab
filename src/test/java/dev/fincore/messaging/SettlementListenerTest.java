package dev.fincore.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
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
        SettlementListener listener = new SettlementListener(
            settlements,
            leases,
            1,
            WORKER_ID,
            new SimpleMeterRegistry(),
            deliveries
        );
        ConsumerRecord<String, Object> record = new ConsumerRecord<>(
            "settlement.commands.v1",
            0,
            0L,
            command.businessKey(),
            command
        );
        return new Fixture(listener, settlements, leases, command, fence, record);
    }

    /** 监听器测试所需的不可变依赖集合。 */
    private record Fixture(SettlementListener listener, SettlementService settlements,
                           WorkerLeaseManager leases, SettlementCommand command, FenceToken fence,
                           ConsumerRecord<String, Object> record) {
    }
}
