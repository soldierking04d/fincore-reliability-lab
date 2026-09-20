package dev.fincore.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.OrderView;
import dev.fincore.infrastructure.persistence.mapper.MatchingMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.infrastructure.persistence.mapper.SpotFundsMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 预占须使用取得行锁后的当前余额，并且避免再查询同一个账户。 */
class SpotFundsServiceTest {
    /** 并发变更后的不足余额不能被旧的非锁定快照放行。 */
    @Test
    void insufficientLockedSnapshotRejectsBeforeReservation() {
        Fixture fixture = fixture(BigDecimal.ZERO);

        assertThrows(IllegalStateException.class, () -> fixture.service().capture(fixture.result()));

        verify(fixture.funds(), never()).reserve(any(), any(), any(), any());
        verify(fixture.funds(), never()).funds(any());
    }

    /** 锁内余额充足时，直接使用锁定结果且保留一次预占。 */
    @Test
    void sufficientLockedSnapshotAvoidsRedundantRead() {
        Fixture fixture = fixture(BigDecimal.TEN);

        fixture.service().capture(fixture.result());

        verify(fixture.funds(), never()).funds(any());
        verify(fixture.funds()).reserve(fixture.result().order().orderId(), fixture.payer(),
            fixture.receiver(), BigDecimal.ONE);
    }

    /** 非锁定快照故意与锁定余额相反，便于检出误用读路径。 */
    private static Fixture fixture(BigDecimal available) {
        SpotFundsMapper funds = mock(SpotFundsMapper.class);
        UUID payer = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        OrderView order = new OrderView(UUID.randomUUID(), "client", "buyer", "BTC-USDT",
            OrderSide.BUY, OrderType.LIMIT, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO,
            BigDecimal.ONE, OrderStatus.OPEN, 1, 0, false, "open");
        when(funds.accountId("buyer", "USDT")).thenReturn(new SpotFundsMapper.AccountId(payer));
        when(funds.accountId("buyer", "BTC")).thenReturn(new SpotFundsMapper.AccountId(receiver));
        when(funds.lockFunds(payer)).thenReturn(new SpotFundsMapper.FundsRow(payer, "USDT", available,
            BigDecimal.ZERO, BigDecimal.ZERO, false, available));
        when(funds.funds(payer)).thenReturn(new SpotFundsMapper.FundsRow(payer, "USDT", BigDecimal.TEN,
            BigDecimal.ZERO, BigDecimal.ZERO, false, BigDecimal.TEN));
        when(funds.reserve(any(), any(), any(), any())).thenReturn(1);
        when(funds.changeFunds(any(), any(), any(), any())).thenReturn(1);
        when(funds.journal(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
        return new Fixture(new SpotFundsService(funds, mock(MatchingMapper.class), mock(OutboxMapper.class),
            new ObjectMapper()), funds, new MatchingResult(order, List.of()), payer, receiver);
    }

    /** 预占测试上下文。 */
    private record Fixture(SpotFundsService service, SpotFundsMapper funds, MatchingResult result,
                           UUID payer, UUID receiver) { }
}
