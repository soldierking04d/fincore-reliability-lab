package dev.fincore.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.UuidOrder;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.AccountRow;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.FundingRow;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.InboxRow;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.OperationRow;
import dev.fincore.infrastructure.persistence.mapper.DerivativesLabMapper.PositionRow;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

/**
 * 不依赖 Docker 的分支、精度和调用契约测试。
 * Mock 不是金融账本，更不能证明数据库锁与事务回滚；真实数据库证明见集成测试。
 */
@ExtendWith(MockitoExtension.class)
class DerivativesLabServiceTest {
    private static final UUID ACCOUNT = UUID.fromString("80000000-0000-0000-0000-000000000001");
    private static final UUID POOL = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-09-03T00:00:00Z");
    /** 超出五秒有效窗口的标记价偏移。 */
    private static final long STALE_MARK_OFFSET_SECONDS = 6L;
    /** 禁止原地更新或删除的合约事实表后缀。 */
    private static final List<String> IMMUTABLE_TABLE_SUFFIXES =
        List.of("operation", "ledger", "funding", "inbox");
    @Mock DerivativesLabMapper mapper;
    @Mock OutboxMapper outbox;
    private DerivativesLabService lab;

    /** 仅模拟持久化返回值；每个测试独立初始化，不共享模拟金融状态。 */
    @BeforeEach
    void setup() {
        lab = new DerivativesLabService(mapper, outbox, new ObjectMapper().findAndRegisterModules());
        lenient().when(mapper.lockAccount(ACCOUNT)).thenReturn(account("10000", "0", 3, 2));
        lenient().when(mapper.lockAccount(POOL)).thenReturn(
            new AccountRow(POOL, n("1000000"), n("1000000"), n("0"), 0, 0, "ACTIVE"));
        lenient().when(mapper.position(ACCOUNT)).thenReturn(position("1"));
        lenient().when(mapper.insertOperation(any())).thenReturn(1);
        lenient().when(mapper.changeAccount(any(), any(), any())).thenReturn(1);
        lenient().when(mapper.updatePosition(any(), any())).thenReturn(1);
        lenient().when(mapper.postPair(any(), any(), any(), any())).thenReturn(2);
        lenient().when(outbox.insert(any(), anyString(), anyString(), anyString())).thenReturn(1);
        lenient().when(mapper.enterLiquidation(any(), anyLong(), anyLong())).thenReturn(1);
        lenient().when(mapper.now()).thenReturn(NOW);
    }

    @Test
    void reservationUsesAvailableBudgetAndPersistsRejection() {
        when(mapper.lockAccount(ACCOUNT)).thenReturn(account("10000", "6000", 3, 2));
        var result = lab.reserve(ACCOUNT, "order", "ETH-USDT", n("6000"));
        assertEquals("INSUFFICIENT_MARGIN", result.status());
        verify(mapper, never()).changeAccount(any(), any(), any());
        verify(outbox).insert(eq(result.operationId()), eq(ACCOUNT.toString()),
            eq("DERIVATIVE_LAB_RESERVE"), anyString());
    }

    @Test
    void sameBusinessKeyReturnsOriginalResultAndRejectsChangedPayload() {
        var first = lab.reserve(ACCOUNT, "order", "BTC-USDT", n("6000"));
        ArgumentCaptor<OperationRow> captured = ArgumentCaptor.forClass(OperationRow.class);
        verify(mapper).insertOperation(captured.capture());
        when(mapper.operation(ACCOUNT, "RESERVE", "order")).thenReturn(captured.getValue());
        var replay = lab.reserve(ACCOUNT, "order", "BTC-USDT", n("6000.00000000"));
        assertTrue(replay.duplicate());
        assertEquals(first.operationId(), replay.operationId());
        assertThrows(IllegalArgumentException.class,
            () -> lab.reserve(ACCOUNT, "order", "BTC-USDT", n("6001")));
        verify(mapper, times(1)).changeAccount(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"1,0.0001,-6", "-1,0.0001,6", "1,-0.0001,6", "-1,-0.0001,-6", "1,0,0"})
    void fundingDirectionAndZeroRateAreExplicit(String quantity, String rate, String expected) {
        stubFunding(quantity, rate);
        var result = lab.applyFunding(ACCOUNT, POOL, "BTC-USDT", NOW, UUID.randomUUID());
        amount(expected, result.effect());
        ArgumentCaptor<BigDecimal> cash = ArgumentCaptor.forClass(BigDecimal.class);
        verify(mapper).changeAccount(eq(ACCOUNT), cash.capture(), any());
        amount(expected, cash.getValue());
        if (n(expected).signum() == 0) {
            verify(mapper, never()).postPair(any(), any(), any(), any());
        } else {
            verify(mapper).postPair(eq(result.operationId()), eq(ACCOUNT), eq(POOL), eq(n(expected)));
        }
    }

    @Test
    void everyMultiAccountMutationStartsWithDeterministicLocks() {
        List<UUID> acquired = new ArrayList<>();
        doAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            acquired.add(id);
            return ACCOUNT.equals(id) ? account("10000", "0", 3, 2)
                : new AccountRow(POOL, n("1000000"), n("1000000"), n("0"), 0, 0, "ACTIVE");
        }).when(mapper).lockAccount(any());
        lab.topUp(ACCOUNT, POOL, "topup", n("100"));
        assertEquals(UuidOrder.uniqueSorted(ACCOUNT, POOL), acquired.subList(0, 2));
    }

    @Test
    void outboxFailureIsNotTurnedIntoSuccess() {
        stubFunding("1", "0.0001");
        doThrow(new IllegalStateException("写入失败"))
            .when(outbox).insert(any(), anyString(), anyString(), anyString());
        assertThrows(IllegalStateException.class,
            () -> lab.applyFunding(ACCOUNT, POOL, "BTC-USDT", NOW, UUID.randomUUID()));
        // 这里只证明异常传播；事务回滚必须由 PostgreSQL 集成用例验证。
        verify(mapper).postPair(any(), eq(ACCOUNT), eq(POOL), eq(n("-6")));
    }

    @ParameterizedTest
    @CsvSource({"SELL,61000,0.2,200", "BUY,59000,-0.2,200", "SELL,59000,0.2,-200"})
    void reduceOnlyCapsAtCurrentPositionAndBooksPnl(OrderSide side,
                                                    String price, String remaining, String pnl) {
        when(mapper.position(ACCOUNT)).thenReturn(position(remaining));
        var result = lab.reduceOnly(ACCOUNT, POOL, "fill", "BTC-USDT", side, n("0.8"), n(price));
        amount("0.2", result.effect());
        verify(mapper).updatePosition(ACCOUNT, n("0"));
        verify(mapper).postPair(eq(result.operationId()), eq(ACCOUNT), eq(POOL), eq(n(pnl)));
    }

    @Test
    void wrongSideCannotIncreaseThePosition() {
        var result = lab.reduceOnly(ACCOUNT, POOL, "fill", "BTC-USDT", OrderSide.BUY, n("1"), n("60000"));
        assertEquals("WRONG_SIDE", result.status());
        verify(mapper, never()).updatePosition(any(), any());
        verify(mapper, never()).postPair(any(), any(), any(), any());
    }

    @Test
    void oldEpochAndOldAccountVersionCannotEnterLiquidation() {
        var snapshot = snapshot(3, "54000", NOW);
        assertEquals("STALE_EPOCH", lab.liquidate(UUID.randomUUID(), snapshot, 1).status());
        assertEquals("STALE_ACCOUNT", lab.liquidate(UUID.randomUUID(), snapshot(2, "54000", NOW), 2).status());
        verify(mapper, never()).enterLiquidation(any(), anyLong(), anyLong());
    }

    @Test
    void staleAndFutureMarksFailClosed() {
        for (Instant time : List.of(
            NOW.minusSeconds(STALE_MARK_OFFSET_SECONDS), NOW.plusSeconds(1))) {
            assertEquals("STALE_MARK", lab.liquidate(UUID.randomUUID(), snapshot(3, "54000", time), 2).status());
        }
        verify(mapper, never()).enterLiquidation(any(), anyLong(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"299,LIQUIDATING", "300,LIQUIDATING", "300.00000001,NOT_REQUIRED"})
    void maintenanceBoundaryUsesEquityInsteadOfWalletOrLastTrade(String wallet, String expected) {
        when(mapper.lockAccount(ACCOUNT)).thenReturn(account(wallet, "0", 3, 2));
        assertEquals(expected, lab.liquidate(UUID.randomUUID(), snapshot(3, "60000", NOW), 2).status());
    }

    @Test
    void databaseCasFailureCannotReturnLiquidating() {
        when(mapper.lockAccount(ACCOUNT)).thenReturn(account("5000", "0", 3, 2));
        when(mapper.enterLiquidation(ACCOUNT, 3, 2)).thenReturn(0);
        assertThrows(IllegalStateException.class,
            () -> lab.liquidate(UUID.randomUUID(), snapshot(3, "54000", NOW), 2));
        verify(outbox, never()).insert(any(), anyString(), anyString(), anyString());
    }

    @Test
    void invalidPrecisionAndSelfCounterpartyAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> lab.reserve(ACCOUNT, "order", "BTC-USDT", new BigDecimal("0.000000001")));
        assertThrows(IllegalArgumentException.class,
            () -> lab.topUp(ACCOUNT, ACCOUNT, "topup", n("100")));
        assertThrows(IllegalArgumentException.class,
            () -> lab.reserve(ACCOUNT, "order", "BTC-USDT", n("-1")));
        verify(mapper, never()).changeAccount(any(), any(), any());
    }

    @Test
    void financialEntryPointsStayTransactionalAndLabOnly() {
        assertEquals(List.of("lab"), List.of(DerivativesLabService.class.getAnnotation(Profile.class).value()));
        for (var method : DerivativesLabService.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
                assertTrue(method.isAnnotationPresent(Transactional.class), method.getName());
            }
        }
    }

    @Test
    void mybatisParsesAllDerivativesStatementsWithoutDatabase() {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DerivativesLabMapper.class);
        assertTrue(configuration.hasStatement(DerivativesLabMapper.class.getName() + ".insertOperation"));
    }

    @Test
    void schemaProtectsFinancialHistoryAndBusinessUniqueness() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V7__derivatives_lab.sql"));
        assertTrue(sql.contains("UNIQUE(account_id, kind, business_key)"));
        assertTrue(sql.contains("PRIMARY KEY(account_id, symbol, cycle_at)"));
        for (String table : IMMUTABLE_TABLE_SUFFIXES) {
            assertTrue(sql.contains("BEFORE UPDATE OR DELETE ON lab_derivative_" + table));
        }
    }

    private void stubFunding(String quantity, String rate) {
        when(mapper.funding(ACCOUNT, "BTC-USDT", NOW)).thenReturn(
            new FundingRow(ACCOUNT, "BTC-USDT", NOW, n(quantity), n("60000"), n(rate), 3));
        AtomicReference<UUID> operation = new AtomicReference<>();
        when(mapper.insertInbox(any(), any())).thenAnswer(invocation -> {
            operation.set(invocation.getArgument(1));
            return 1;
        });
        when(mapper.inboxOperation(any())).thenAnswer(invocation -> new InboxRow(operation.get()));
    }

    private static AccountRow account(String wallet, String reserved, long version, long epoch) {
        return new AccountRow(ACCOUNT, n(wallet), n(wallet), n(reserved), version, epoch, "ACTIVE");
    }

    private static PositionRow position(String quantity) {
        return new PositionRow(ACCOUNT, "BTC-USDT", n(quantity), n("60000"));
    }

    private static DerivativesLabService.RiskSnapshot snapshot(long version, String mark, Instant time) {
        return new DerivativesLabService.RiskSnapshot(ACCOUNT, "BTC-USDT", version, n(mark), time);
    }

    private static BigDecimal n(String value) { return new BigDecimal(value).setScale(8); }
    private static void amount(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual));
    }
}
