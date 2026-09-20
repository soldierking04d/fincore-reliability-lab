package dev.fincore.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.fincore.domain.FenceToken;
import dev.fincore.domain.SpotDeliveryCommand;
import dev.fincore.infrastructure.persistence.mapper.LedgerMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.infrastructure.persistence.mapper.SpotFundsMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 现货交割的事务提交计量与已锁账户快照复用回归。 */
class SpotDeliveryServiceTest {
    /** 避免单元测试手动事务同步状态泄漏到后续用例。 */
    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    /** A fence checked without an actual transaction cannot protect later financial writes. */
    @Test
    void missingTransactionRejectsBeforeFinancialWrites() {
        Fixture fixture = fixture("PENDING");

        assertThrows(IllegalStateException.class,
            () -> fixture.service().settle(fixture.command(), fixture.fence()));

        verify(fixture.funds(), never()).lockDelivery(any());
        verify(fixture.ledger(), never()).insertTransaction(any(), anyString(), anyString(), anyString());
    }

    /** 已完成业务方法也不能在真实事务提交之前记成完成。 */
    @Test
    void successfulDeliveryIsCountedOnlyAfterCommit() {
        Fixture fixture = fixture("PENDING");
        beginTransaction();

        fixture.service().settle(fixture.command(), fixture.fence());

        assertEquals(0, completed(fixture));
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, completed(fixture));
    }

    /** 外层事务回滚，即使交割方法正常返回，也没有已完成金融结果。 */
    @Test
    void outerRollbackDoesNotCountCompletedDelivery() {
        Fixture fixture = fixture("PENDING");
        beginTransaction();

        fixture.service().settle(fixture.command(), fixture.fence());
        TransactionSynchronizationManager.getSynchronizations().forEach(
            synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertEquals(0, completed(fixture));
    }

    /** 重放的成功确认也只在事务提交后计数，不增加真实完成数。 */
    @Test
    void duplicateIsCountedOnlyAfterCommit() {
        Fixture fixture = fixture("SETTLED");
        beginTransaction();

        fixture.service().settle(fixture.command(), fixture.fence());

        assertEquals(0, fixture.registry().get("fincore.spot.delivery.duplicate").counter().count());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertEquals(1, fixture.registry().get("fincore.spot.delivery.duplicate").counter().count());
        assertEquals(0, completed(fixture));
    }

    /** 四账户已经在当前事务锁定，其资产检查不应再发四次非锁定查询。 */
    @Test
    void assetValidationReusesLockedAccountSnapshots() {
        Fixture fixture = fixture("PENDING");
        beginTransaction();

        fixture.service().settle(fixture.command(), fixture.fence());

        verify(fixture.funds(), never()).funds(any());
        verify(fixture.funds()).lockFunds(fixture.row().buyerQuoteId());
        verify(fixture.funds()).lockFunds(fixture.row().buyerBaseId());
        verify(fixture.funds()).lockFunds(fixture.row().sellerQuoteId());
        verify(fixture.funds()).lockFunds(fixture.row().sellerBaseId());
    }

    /** 快照复用不能绕过冻结与资产错误校验。 */
    @Test
    void incorrectAssetInLockedSnapshotRejectsBeforeLedgerWrites() {
        Fixture fixture = fixture("PENDING");
        when(fixture.funds().lockFunds(fixture.row().buyerQuoteId()))
            .thenReturn(account(fixture.row().buyerQuoteId(), "WRONG"));
        beginTransaction();

        assertThrows(IllegalStateException.class,
            () -> fixture.service().settle(fixture.command(), fixture.fence()));

        verify(fixture.ledger(), never()).insertTransaction(any(), anyString(), anyString(), anyString());
        assertEquals(0, completed(fixture));
    }

    /** 仅模拟事务同步回调；真实数据库回滚另由 PostgreSQL 测试覆盖。 */
    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    /** 当前登记的已提交完成计数。 */
    private static double completed(Fixture fixture) {
        return fixture.registry().get("fincore.spot.delivery.completed").counter().count();
    }

    /** 固定余额便于聚焦交割编排与计量，数据库约束由集成测试负责。 */
    private static SpotFundsMapper.FundsRow account(UUID id, String asset) {
        return new SpotFundsMapper.FundsRow(id, asset, BigDecimal.TEN, BigDecimal.ZERO,
            BigDecimal.ONE, false, new BigDecimal("9"));
    }

    /** 为合法交割提供各写入返回值与四个真实资产方向。 */
    private static Fixture fixture(String status) {
        SpotFundsMapper funds = mock(SpotFundsMapper.class);
        LedgerMapper ledger = mock(LedgerMapper.class);
        OutboxMapper outbox = mock(OutboxMapper.class);
        SpotFundsMapper.DeliveryRow row = new SpotFundsMapper.DeliveryRow(UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "BTC", "USDT", BigDecimal.ONE,
            BigDecimal.TEN, status, null, null);
        when(funds.delivery(row.tradeId())).thenReturn(row);
        when(funds.lockDelivery(row.tradeId())).thenReturn(row);
        when(funds.inbox(anyString(), any())).thenReturn(1);
        for (UUID id : new UUID[]{row.buyerQuoteId(), row.sellerQuoteId()}) {
            when(funds.lockFunds(id)).thenReturn(account(id, "USDT"));
            when(funds.funds(id)).thenReturn(account(id, "USDT"));
        }
        for (UUID id : new UUID[]{row.buyerBaseId(), row.sellerBaseId()}) {
            when(funds.lockFunds(id)).thenReturn(account(id, "BTC"));
            when(funds.funds(id)).thenReturn(account(id, "BTC"));
        }
        when(ledger.insertTransaction(any(), anyString(), anyString(), anyString())).thenReturn(1);
        when(ledger.insertEntries(any(), any())).thenReturn(2);
        when(funds.changeFunds(any(), any(), any(), any())).thenReturn(1);
        when(funds.changeReservation(any(), any(), any(), any(), any())).thenReturn(1);
        when(funds.journal(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        when(funds.complete(any())).thenReturn(1);
        when(outbox.insert(any(), anyString(), anyString(), anyString())).thenReturn(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SpotDeliveryService service = new SpotDeliveryService(funds, ledger, outbox,
            mock(ShardLeaseService.class), registry, 1);
        return new Fixture(service, funds, ledger, registry, row,
            new SpotDeliveryCommand("message", row.tradeId()), new FenceToken(0, "test-worker", 1));
    }

    /** 单元测试所需依赖。 */
    private record Fixture(SpotDeliveryService service, SpotFundsMapper funds, LedgerMapper ledger,
                           SimpleMeterRegistry registry, SpotFundsMapper.DeliveryRow row,
                           SpotDeliveryCommand command, FenceToken fence) { }
}
