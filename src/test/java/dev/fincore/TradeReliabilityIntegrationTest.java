package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.AdvancedLabScenarioService;
import dev.fincore.application.MatchingService;
import dev.fincore.application.TradeReliabilityService;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeSyncCommand;
import dev.fincore.domain.TradeView;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 成交同步、差异检测、自动修复和修复竞态的数据库集成测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("lab")
@SpringBootTest(properties = {
    "spring.kafka.bootstrap-servers=127.0.0.1:1",
    "spring.task.scheduling.enabled=false"
})
class TradeReliabilityIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MatchingService matching;
    @Autowired TradeReliabilityService reliability;
    @Autowired AdvancedLabScenarioService advanced;
    @Autowired JdbcTemplate jdbc;

    /** 普通下单积压不能挤占撤单容量，且撤单必须在权威终态后返回成功。 */
    @Test
    void cancellationStormPreservesExitCapacityAndOrderInvariant() {
        var report = advanced.runCancellationStorm(32);
        assertTrue(report.overflowRejected());
        assertEquals(dev.fincore.domain.OrderStatus.CANCELED, report.cancellationStatus());
        assertEquals(0, report.ordinaryCompletedAtCancellation());
        assertEquals(32, report.ordinaryCompleted());
        assertTrue(report.quantityInvariant());
        assertTrue(report.checks().values().stream().allMatch(value -> value.startsWith("PASS")));
    }

    @Test
    void outOfOrderDuplicateAndMissingEventRepairConverges() {
        String symbol = symbol("MISS");
        List<TradeView> trades = createTrades(symbol, 3);
        UUID lastEvent = UUID.randomUUID();

        reliability.apply(TradeSyncCommand.from(lastEvent, trades.get(2)));
        reliability.apply(TradeSyncCommand.from(UUID.randomUUID(), trades.get(0)));
        var duplicate = reliability.apply(
            TradeSyncCommand.from(lastEvent, trades.get(2)));

        assertTrue(duplicate.duplicateEvent());
        var detected = reliability.reconcile(symbol);
        assertEquals("DIFFERENCE_FOUND", detected.status());
        assertEquals(1, detected.missingCount());
        assertEquals(0, detected.mismatchCount());
        assertEquals(0, detected.extraCount());

        String repairKey = "missing-" + UUID.randomUUID();
        var repaired = reliability.repair(detected.runId(), repairKey);
        var replay = reliability.repair(detected.runId(), repairKey);
        assertEquals(1, repaired.repairedCount());
        assertTrue(replay.duplicate());

        var clean = reliability.reconcile(symbol);
        assertEquals("CLEAN", clean.status());
        assertEquals(3, reliability.activeProjectionCount(symbol));
    }

    @Test
    void mismatchAndGhostTradeAreRebuiltOrQuarantined() {
        String symbol = symbol("CORR");
        List<TradeView> trades = createTrades(symbol, 2);
        trades.forEach(trade -> reliability.apply(
            TradeSyncCommand.from(UUID.randomUUID(), trade)));

        jdbc.update("""
            UPDATE trade_projection SET quantity=quantity+1, updated_at=now()
            WHERE trade_id=?
            """, trades.get(0).tradeId());
        long sequence = trades.stream().mapToLong(TradeView::sequence)
            .max().orElseThrow() + 10_000;
        TradeSyncCommand ghost = new TradeSyncCommand(
            UUID.randomUUID(), UUID.randomUUID(), symbol,
            trades.get(0).makerOrderId(), trades.get(0).takerOrderId(),
            new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("100"),
            sequence);
        reliability.apply(ghost);

        var detected = reliability.reconcile(symbol);
        assertEquals(1, detected.mismatchCount());
        assertEquals(1, detected.extraCount());

        var repaired = reliability.repair(
            detected.runId(), "corrupted-" + UUID.randomUUID());
        assertEquals(1, repaired.repairedCount());
        assertEquals(1, repaired.quarantinedCount());
        assertEquals("CLEAN", reliability.reconcile(symbol).status());
        assertEquals("QUARANTINED", jdbc.queryForObject("""
            SELECT status FROM trade_projection WHERE trade_id=?
            """, String.class, ghost.tradeId()));
    }

    @Test
    void sameEventIdWithChangedPayloadIsRejected() {
        String symbol = symbol("HASH");
        TradeView trade = createTrades(symbol, 1).get(0);
        UUID eventId = UUID.randomUUID();
        reliability.apply(TradeSyncCommand.from(eventId, trade));

        TradeSyncCommand changed = new TradeSyncCommand(
            eventId, trade.tradeId(), trade.symbol(),
            trade.makerOrderId(), trade.takerOrderId(), trade.price(),
            trade.quantity().add(BigDecimal.ONE),
            trade.quoteAmount().add(trade.price()), trade.sequence());
        assertThrows(IllegalArgumentException.class,
            () -> reliability.apply(changed));
        assertEquals(1, reliability.activeProjectionCount(symbol));
    }

    @Test
    void concurrentDuplicateDeliveryCreatesOneProjection() throws Exception {
        String symbol = symbol("DUP");
        TradeView trade = createTrades(symbol, 1).get(0);
        TradeSyncCommand command = TradeSyncCommand.from(UUID.randomUUID(), trade);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<TradeReliabilityService.SyncOutcome>> tasks =
                new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> reliability.apply(command));
            }
            var outcomes = pool.invokeAll(tasks);
            int inserts = 0;
            int duplicates = 0;
            for (var future : outcomes) {
                var outcome = future.get();
                if (outcome.projectionInserted()) inserts++;
                if (outcome.duplicateEvent()) duplicates++;
            }
            assertEquals(1, inserts);
            assertEquals(15, duplicates);
            assertEquals(1, reliability.activeProjectionCount(symbol));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void repairRechecksSourceBeforeQuarantiningLateAuthoritativeTrade() {
        String symbol = symbol("LATE");
        TradeView base = createTrades(symbol, 1).get(0);
        reliability.apply(TradeSyncCommand.from(UUID.randomUUID(), base));
        long lateSequence = base.sequence() + 20_000;
        TradeSyncCommand late = new TradeSyncCommand(
            UUID.randomUUID(), UUID.randomUUID(), symbol,
            base.makerOrderId(), base.takerOrderId(),
            new BigDecimal("100"), BigDecimal.ONE, new BigDecimal("100"),
            lateSequence);
        reliability.apply(late);
        var detected = reliability.reconcile(symbol);
        assertEquals(1, detected.extraCount());

        jdbc.update("""
            INSERT INTO trade_execution(
                trade_id, symbol, maker_order_id, taker_order_id, price,
                quantity, quote_amount, trade_sequence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, late.tradeId(), symbol, late.makerOrderId(), late.takerOrderId(),
            late.price(), late.quantity(), late.quoteAmount(), late.tradeSequence());

        var repaired = reliability.repair(
            detected.runId(), "late-source-" + UUID.randomUUID());
        assertEquals(1, repaired.repairedCount());
        assertEquals(0, repaired.quarantinedCount());
        assertEquals("CLEAN", reliability.reconcile(symbol).status());
    }

    @Test
    void advancedRecoveryScenarioEndsClean() {
        var report = advanced.runTradeSyncRecovery();
        assertEquals(7, report.checks().size());
        assertTrue(report.checks().values().stream()
            .allMatch(value -> value.startsWith("PASS")));
    }

    @Test
    void hotSymbolBurstPreservesAllInvariants() {
        var report = advanced.runMatchingBurst(40, 8);
        assertEquals(40, report.tradeCount());
        assertEquals(5, report.checks().size());
        assertTrue(report.checks().values().stream()
            .allMatch(value -> value.startsWith("PASS")));
    }

    private List<TradeView> createTrades(String symbol, int count) {
        String suffix = UUID.randomUUID().toString();
        for (int i = 0; i < count; i++) {
            matching.place(new PlaceOrderCommand(
                "maker-" + suffix + "-" + i,
                "seller-" + suffix + "-" + i,
                symbol, OrderSide.SELL, OrderType.LIMIT,
                new BigDecimal(100 + i), BigDecimal.ONE));
        }
        MatchingResult result = matching.place(new PlaceOrderCommand(
            "taker-" + suffix, "buyer-" + suffix,
            symbol, OrderSide.BUY, OrderType.LIMIT,
            new BigDecimal(100 + count), new BigDecimal(count)));
        assertEquals(count, result.trades().size());
        return result.trades();
    }

    private static String symbol(String prefix) {
        return prefix + UUID.randomUUID().toString()
            .replace("-", "").substring(0, 8).toUpperCase() + "-USDT";
    }
}
