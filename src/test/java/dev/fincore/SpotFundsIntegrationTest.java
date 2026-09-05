package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import dev.fincore.application.AccountService;
import dev.fincore.application.MatchingService;
import dev.fincore.application.SettlementService;
import dev.fincore.application.ShardLeaseService;
import dev.fincore.application.SpotDeliveryService;
import dev.fincore.application.SpotFundsService;
import dev.fincore.application.TradingLifecycleService;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementStatus;
import dev.fincore.domain.SpotDeliveryCommand;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.support.TestExecutors;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 委托预占、在途和双资产交割的数据库验收；先建立防止余额重复使用的失败测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.task.scheduling.enabled=false"
})
class SpotFundsIntegrationTest {
    /** 现货场景统一使用的计价资产。 */
    private static final String QUOTE_ASSET = "USDT";
    /** 幂等下单竞态的调用数量。 */
    private static final int ORDER_REPLAY_CALLS = 6;
    /** 同一成交的并发交割投递数量。 */
    private static final int DELIVERY_REPLAY_CALLS = 8;
    /** 每个套件使用独立数据库，不接触线上资金。 */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 注入测试容器连接。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** 受控交易入口。 */
    @Autowired TradingLifecycleService trading;
    /** 原有撤单入口也必须释放委托预占。 */
    @Autowired MatchingService matching;
    /** 独立核对数据库事实。 */
    @Autowired JdbcTemplate jdbc;
    /** 真实交割事务。 */
    @Autowired SpotDeliveryService deliveries;
    /** 分桶查询及对账。 */
    @Autowired SpotFundsService funds;
    /** 真正的数据库 Worker Lease。 */
    @Autowired ShardLeaseService leases;
    /** 通用转账不能消费已预占余额。 */
    @Autowired SettlementService settlements;
    /** 创建测试对手账户。 */
    @Autowired AccountService accounts;
    /** 只在测试中模拟最后一步数据库调用失败，生产没有故障旁路。 */
    @MockitoSpyBean OutboxMapper outbox;

    /** 未成交委托也必须占用余额，第二张订单不能重复花同一笔钱。 */
    @Test
    void openOrdersCannotReuseTheSameAvailableBalance() {
        String id = user("USDT", "100");
        String symbol = quote();
        assertEquals("APPROVED", trading.place(order(id, symbol, OrderSide.BUY, "100", "0.6"))
            .preTradeDecision().decision());
        assertEquals("INSUFFICIENT_BALANCE", trading.place(order(id, symbol, OrderSide.BUY, "100", "0.6"))
            .preTradeDecision().reasonCode());
    }

    /** 撤单只恢复一次可用资金，重复撤单与重放下单都不重新冻结。 */
    @Test
    void cancellationReleasesOnlyOnce() {
        String id = user("USDT", "100");
        PlaceOrderCommand command = order(id, quote(), OrderSide.BUY, "100", "0.6");
        var placed = trading.place(command);
        amount("60", "SELECT reserved_balance FROM account WHERE owner_id=? AND asset='USDT'", id);
        matching.cancel(placed.matching().order().orderId(), id);
        matching.cancel(placed.matching().order().orderId(), id);
        trading.place(command);
        amount("0", "SELECT reserved_balance FROM account WHERE owner_id=? AND asset='USDT'", id);
        amount("100", "SELECT balance FROM account WHERE owner_id=? AND asset='USDT'", id);
    }

    /** 受控资金市场不接受超过八位精度的价格，不能由数据库悄悄舍入。 */
    @Test
    void unsupportedPrecisionIsRejectedBeforeFinancialWrites() {
        String id = user("USDT", "100");
        String symbol = quote();
        assertThrows(IllegalArgumentException.class, () -> trading.place(
            order(id, symbol, OrderSide.BUY, "100.000000001", "0.6")));
    }

    /** 部分成交之后撤单只释放余单，随后 DvP 同时交付两种资产。 */
    @Test
    void partialFillPriceImprovementAndCancellationPreservePending() {
        String symbol = quote();
        String base = symbol.split("-")[0];
        String seller = user(base, "10");
        String buyer = user("USDT", "1000");
        trading.place(order(seller, symbol, OrderSide.SELL, "95", "1"));
        var bought = trading.place(order(buyer, symbol, OrderSide.BUY, "100", "2"));
        UUID orderId = bought.matching().order().orderId();
        UUID trade = bought.matching().trades().getFirst().tradeId();
        UUID payer = account(buyer, "USDT");
        assertEquals(OrderStatus.PARTIALLY_FILLED, bought.matching().order().status());
        amount("100", "SELECT reserved_balance FROM account WHERE account_id=?", payer);
        amount("95", "SELECT pending_debit FROM account WHERE account_id=?", payer);
        amount("805", "SELECT balance-reserved_balance-pending_debit FROM account WHERE account_id=?", payer);
        matching.cancel(orderId, buyer);
        matching.cancel(orderId, buyer);
        amount("905", "SELECT balance-reserved_balance-pending_debit FROM account WHERE account_id=?", payer);
        amount("95", "SELECT pending_debit FROM account WHERE account_id=?", payer);
        settle(trade, "partial");
        balance(buyer, "USDT", "905");
        balance(buyer, base, "1");
        balance(seller, base, "9");
        balance(seller, "USDT", "95");
        amount("0", "SELECT pending_debit FROM account WHERE account_id=?", payer);
        assertClean(buyer, seller, base);
    }

    /** 跨交易对同时花费同一计价资产，最多一张订单可以得到预算。 */
    @Test
    void concurrentSymbolsShareOneBudget() throws Exception {
        String buyer = user("USDT", "100");
        String first = quote();
        String second = quote();
        List<String> results = race(List.of(
            () -> trading.place(order(buyer, first, OrderSide.BUY, "100", "0.6")).preTradeDecision().decision(),
            () -> trading.place(order(buyer, second, OrderSide.BUY, "100", "0.6")).preTradeDecision().decision()));
        assertEquals(1, results.stream().filter("APPROVED"::equals).count());
        amount("60", "SELECT reserved_balance FROM account WHERE owner_id=? AND asset='USDT'", buyer);
    }

    /** 同一客户端请求并发重放只冻结一次，并保留一张委托和一个决定。 */
    @Test
    void concurrentOrderReplayHasOneReservation() throws Exception {
        String buyer = user("USDT", "100");
        PlaceOrderCommand command = order(buyer, quote(), OrderSide.BUY, "100", "0.6");
        List<Callable<UUID>> tasks = new ArrayList<>();
        for (int index = 0; index < ORDER_REPLAY_CALLS; index++) {
            tasks.add(() -> trading.place(command).matching().order().orderId());
        }
        assertEquals(1, race(tasks).stream().distinct().count());
        amount("60", "SELECT reserved_balance FROM account WHERE owner_id=? AND asset='USDT'", buyer);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pre_trade_decision WHERE user_id=?", Integer.class, buyer));
    }

    /** 数据库调用在最后一个 Outbox 写入失败时，两种资产、账本和 Inbox 一起回滚。 */
    @Test
    void lastWriteFailureRollsBackBothAssetsAndCanReplay() {
        Fixture fixture = tradeFixture("2");
        UUID trade = fixture.trades().getFirst();
        doThrow(new IllegalStateException("injected after both assets"))
            .when(outbox).insert(any(), eq(trade.toString()), eq("SPOT_DVP_SETTLED"), anyString());
        try {
            assertThrows(IllegalStateException.class, () -> settle(trade, "rollback"));
        } finally {
            reset(outbox);
        }
        balance(fixture.buyer(), "USDT", "1000");
        balance(fixture.seller(), fixture.base(), "10");
        assertEquals("PENDING", deliveries.get(trade).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spot_delivery_inbox WHERE trade_id=?", Integer.class, trade));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE business_key LIKE ?",
            Integer.class, "spot:" + trade + ":%"));
        settle(trade, "rollback");
        settle(trade, "rollback");
        assertClean(fixture.buyer(), fixture.seller(), fixture.base());
        balance(fixture.buyer(), "USDT", "800");
        balance(fixture.buyer(), fixture.base(), "2");
    }

    /** 撮合事务最后阶段失败也不能留下订单成交，却没有预占或交割指令。 */
    @Test
    void matchingFailureRollsBackOrderTradeReservationAndEvents() {
        String symbol = quote();
        String base = symbol.split("-")[0];
        String seller = user(base, "10");
        String buyer = user("USDT", "1000");
        var maker = trading.place(order(seller, symbol, OrderSide.SELL, "100", "2"));
        PlaceOrderCommand command = order(buyer, symbol, OrderSide.BUY, "100", "2");
        doThrow(new IllegalStateException("injected after staging funds"))
            .when(outbox).insert(any(), anyString(), eq("SPOT_DVP_REQUESTED"), anyString());
        try {
            assertThrows(IllegalStateException.class, () -> trading.place(command));
        } finally {
            reset(outbox);
        }
        assertEquals(OrderStatus.OPEN, matching.get(maker.matching().order().orderId()).status());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM trade_execution WHERE symbol=?", Integer.class, symbol));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pre_trade_decision WHERE user_id=?", Integer.class, buyer));
        amount("0", "SELECT reserved_balance+pending_debit FROM account WHERE owner_id=? AND asset='USDT'", buyer);
        assertEquals(1, trading.place(command).matching().trades().size());
    }

    /** 同一成交被多个线程重复投递，只能有两笔按资产平衡的账本事务。 */
    @Test
    void concurrentDeliveryHasOneFinancialEffect() throws Exception {
        Fixture fixture = tradeFixture("2");
        UUID trade = fixture.trades().getFirst();
        FenceToken token = fence(trade);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int index = 0; index < DELIVERY_REPLAY_CALLS; index++) {
            String message = "duplicate-" + index + "-" + trade;
            tasks.add(() -> deliveries.settle(new SpotDeliveryCommand(message, trade), token).status());
        }
        assertTrue(race(tasks).stream().allMatch("SETTLED"::equals));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE business_key LIKE ?",
            Integer.class, "spot:" + trade + ":%"));
        balance(fixture.buyer(), "USDT", "800");
        assertClean(fixture.buyer(), fixture.seller(), fixture.base());
    }

    /** 未交割资金在进程外可恢复；不同成交倒序处理仍保持各资产守恒。 */
    @Test
    void outOfOrderDeliveriesConsumeOnlyTheirOwnPendingBudget() {
        Fixture fixture = tradeFixture("1", "1");
        UUID first = fixture.trades().get(0);
        UUID second = fixture.trades().get(1);
        settle(second, "second");
        amount("100", "SELECT pending_debit FROM account WHERE owner_id=? AND asset='USDT'", fixture.buyer());
        settle(first, "first");
        settle(second, "second-again");
        balance(fixture.buyer(), "USDT", "800");
        assertClean(fixture.buyer(), fixture.seller(), fixture.base());
    }

    /** 旧 Epoch、错误分片和无 Fence 都不能消耗在途资金。 */
    @Test
    void staleWorkerCannotSettleAfterTakeover() {
        Fixture fixture = tradeFixture("1");
        UUID trade = fixture.trades().getFirst();
        FenceToken old = fence(trade);
        jdbc.update("UPDATE shard_lease SET lease_until=now()-interval '1 second' WHERE shard_id=?", old.shardId());
        var next = leases.acquireOrRenew(old.shardId(), "next-worker", Duration.ofMinutes(1));
        SpotDeliveryCommand command = new SpotDeliveryCommand("fenced-" + trade, trade);
        assertThrows(IllegalStateException.class, () -> deliveries.settle(command, old));
        assertThrows(IllegalStateException.class, () -> deliveries.settle(command,
            new FenceToken(99, next.ownerId(), next.epoch())));
        assertThrows(NullPointerException.class, () -> deliveries.settle(command, null));
        assertEquals("PENDING", deliveries.get(trade).status());
        deliveries.settle(command, new FenceToken(next.shardId(), next.ownerId(), next.epoch()));
        assertClean(fixture.buyer(), fixture.seller(), fixture.base());
        jdbc.update("UPDATE shard_lease SET lease_until=now()-interval '1 second' WHERE shard_id=?", old.shardId());
    }

    /** 重用消息编号但替换成交，不能得到一笔无声的假重复成功。 */
    @Test
    void duplicateMessageWithDifferentTradeIsRejected() {
        Fixture fixture = tradeFixture("1", "1");
        UUID first = fixture.trades().getFirst();
        UUID second = fixture.trades().getLast();
        String message = "conflict-" + first;
        deliveries.settle(new SpotDeliveryCommand(message, first), fence(first));
        assertThrows(IllegalArgumentException.class,
            () -> deliveries.settle(new SpotDeliveryCommand(message, second), fence(second)));
        assertEquals("PENDING", deliveries.get(second).status());
        settle(second, "clean");
    }

    /** 独立转账模块也必须遵守资金预占，不能从另一入口把钱转走。 */
    @Test
    void ordinaryTransferCannotSpendReservedMoney() {
        String buyer = user("USDT", "100");
        trading.place(order(buyer, quote(), OrderSide.BUY, "100", "0.8"));
        UUID payee = accounts.create("payee-" + UUID.randomUUID(), "USDT", "TRADING", BigDecimal.ZERO).accountId();
        UUID fee = accounts.create("fee-" + UUID.randomUUID(), "USDT", "SYSTEM_FEE_SHARD", BigDecimal.ZERO).accountId();
        String key = UUID.randomUUID().toString();
        var result = settlements.settle(new SettlementCommand(key, key, account(buyer, "USDT"), payee, fee,
            "USDT", new BigDecimal("30"), BigDecimal.ZERO));
        assertEquals(SettlementStatus.FAILED, result.status());
        balance(buyer, "USDT", "100");
    }

    /** 对账不平只能冻结并留工单；不自动覆盖账本或把不平修成零。 */
    @Test
    void reconciliationFreezesDifferenceAndHistoryIsImmutable() {
        String buyer = user("USDT", "100");
        trading.place(order(buyer, quote(), OrderSide.BUY, "100", "0.6"));
        UUID id = account(buyer, "USDT");
        assertThrows(DataAccessException.class,
            () -> jdbc.update("UPDATE spot_fund_journal SET available_delta=available_delta WHERE account_id=?", id));
        jdbc.update("UPDATE account SET balance=balance+1 WHERE account_id=?", id);
        assertFalse(funds.reconcile(id));
        assertFalse(funds.reconcile(id));
        assertTrue(funds.view(id).financialHold());
        balance(buyer, "USDT", "101");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reconciliation_issue WHERE account_id=?", Integer.class, id));
        assertEquals("ACCOUNT_FROZEN", trading.place(order(buyer, quote(), OrderSide.BUY, "100", "0.1"))
            .preTradeDecision().reasonCode());
    }

    /** 历史纯撮合与新资金市场不混合，避免无资金卖单成交后无法交割。 */
    @Test
    void unfundedMatchingCannotEnterFundedMarket() {
        String buyer = user("USDT", "100");
        String symbol = quote();
        trading.place(order(buyer, symbol, OrderSide.BUY, "100", "0.6"));
        assertThrows(IllegalArgumentException.class,
            () -> matching.place(order("raw-seller", symbol, OrderSide.SELL, "100", "0.6")));
        String legacy = quote();
        matching.place(order("legacy-seller", legacy, OrderSide.SELL, "100", "0.1"));
        assertThrows(IllegalStateException.class,
            () -> trading.place(order(buyer, legacy, OrderSide.BUY, "100", "0.1")));
    }

    /** 部分成交后遇到自成交保护，自动撤销的剩余预占必须释放。 */
    @Test
    void selfTradePreventionReleasesUnfilledTakerBudget() {
        String symbol = quote();
        String base = symbol.split("-")[0];
        String other = user(base, "10");
        String user = user("USDT", "1000");
        trading.openTradingAccount(user, base, new BigDecimal("3"));
        trading.place(order(other, symbol, OrderSide.SELL, "100", "1"));
        trading.place(order(user, symbol, OrderSide.SELL, "105", "1"));
        var result = trading.place(order(user, symbol, OrderSide.BUY, "110", "3"));
        assertEquals(OrderStatus.CANCELED, result.matching().order().status());
        amount("0", "SELECT reserved_balance FROM account WHERE owner_id=? AND asset='USDT'", user);
        amount("100", "SELECT pending_debit FROM account WHERE owner_id=? AND asset='USDT'", user);
        amount("230", "SELECT released FROM spot_order_reservation WHERE order_id=?", result.matching().order().orderId());
        settle(result.matching().trades().getFirst().tradeId(), "stp");
        assertClean(user, other, base);
    }

    /** 创建一个或多个独立成交，保留在途，交给测试决定投递次序。 */
    private Fixture tradeFixture(String... quantities) {
        String symbol = quote();
        String base = symbol.split("-")[0];
        String seller = user(base, "10");
        String buyer = user("USDT", "1000");
        List<UUID> trades = new ArrayList<>();
        for (String quantity : quantities) {
            trading.place(order(seller, symbol, OrderSide.SELL, "100", quantity));
            trades.add(trading.place(order(buyer, symbol, OrderSide.BUY, "100", quantity))
                .matching().trades().getFirst().tradeId());
        }
        return new Fixture(base, seller, buyer, trades);
    }

    /** 取得有效分片 Lease；测试只通过有效 Fence 调用资金事务。 */
    private FenceToken fence(UUID trade) {
        var lease = leases.acquireOrRenew(deliveries.shardFor(trade), "spot-test-worker", Duration.ofMinutes(1));
        return new FenceToken(lease.shardId(), lease.ownerId(), lease.epoch());
    }

    /** 执行一次可重放的受围栏交割。 */
    private void settle(UUID trade, String label) {
        assertEquals("SETTLED", deliveries.settle(new SpotDeliveryCommand(label + "-" + trade, trade), fence(trade)).status());
    }

    /** 数据库查询账号不依赖内存映射。 */
    private UUID account(String user, String asset) {
        return jdbc.queryForObject("SELECT account_id FROM account WHERE owner_id=? AND asset=? AND account_type='TRADING'",
            UUID.class, user, asset);
    }

    /** 核对指定用户某种资产的总余额。 */
    private void balance(String user, String asset, String expected) {
        amount(expected, "SELECT balance FROM account WHERE owner_id=? AND asset=?", user, asset);
    }

    /** 四个交割账户分别重算不可变账本与资金分桶。 */
    private void assertClean(String buyer, String seller, String base) {
        for (String user : List.of(buyer, seller)) {
            for (String asset : List.of(base, QUOTE_ASSET)) {
                assertTrue(funds.reconcile(account(user, asset)), user + ":" + asset);
            }
        }
    }

    /** 同步起跑且限时完成，避免伪并发串行测试；所有线程均回收。 */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(tasks.size());
        try (var pool = TestExecutors.fixedThreadPool(tasks.size(), "spot-funds-test-")) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return task.call(); }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    /** 一组隔离成交夹具。 */
    private record Fixture(String base, String seller, String buyer, List<UUID> trades) { }

    /** 为每次测试建立独立已验证用户及初始资金。 */
    private String user(String asset, String balance) {
        String id = "spot-" + UUID.randomUUID();
        trading.registerCustomer(id, id, "CN");
        trading.reviewKyc(id, "VERIFIED");
        trading.openTradingAccount(id, asset, new BigDecimal(balance));
        trading.configureRisk(id, "LOW", true, new BigDecimal("100000"),
            new BigDecimal("1000000"), new BigDecimal("0.20"));
        return id;
    }

    /** 每个测试使用不与历史实验混用的资金市场。 */
    private String quote() {
        String symbol = "SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase()
            + "-USDT";
        trading.publishQuote(symbol, new BigDecimal("100"), "LAB", Instant.now());
        return symbol;
    }

    /** 构造独立限价委托。 */
    private static PlaceOrderCommand order(String user, String symbol, OrderSide side,
                                           String price, String quantity) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(), user, symbol, side,
            OrderType.LIMIT, new BigDecimal(price), new BigDecimal(quantity));
    }

    /** 金额比较使用数值语义，不依赖 BigDecimal 的 scale。 */
    private void amount(String expected, String sql, Object... arguments) {
        assertEquals(0, new BigDecimal(expected).compareTo(
            jdbc.queryForObject(sql, BigDecimal.class, arguments)));
    }
}
