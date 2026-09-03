package dev.fincore.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.OrderView;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.SpotDeliveryCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.domain.UuidOrder;
import dev.fincore.infrastructure.persistence.mapper.MatchingMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.infrastructure.persistence.mapper.SpotFundsMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 撮合事务内的委托预占、成交转在途和撤单释放。
 *
 * <p>先由撮合持有交易对锁、确定本次所有成交，再按 UUID 全序一次锁住所有付款账户。
 * 预占不足或任何后续 SQL 失败时，盘前决定、订单、成交、分桶审计及 Outbox 整体回滚。
 * 这使多交易对共享余额仍然安全，并避免在已持有一个随机账户锁后继续寻找 Maker 账户。</p>
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Service
public class SpotFundsService {
    /** 金额零值。 */
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    /** 资金持久化。 */
    private final SpotFundsMapper funds;
    /** 权威订单与成交。 */
    private final MatchingMapper matching;
    /** 事务事件。 */
    private final OutboxMapper outbox;
    /** JSON 编码器。 */
    private final ObjectMapper json;

    /** 创建资金编排服务。 */
    public SpotFundsService(SpotFundsMapper funds, MatchingMapper matching,
                             OutboxMapper outbox, ObjectMapper json) {
        this.funds = funds;
        this.matching = matching;
        this.outbox = outbox;
        this.json = json;
    }

    /** 验证新资金市场精度；八位价格乘八位数量不会被 NUMERIC(38,18) 静默舍入。 */
    public void validate(PlaceOrderCommand command) {
        String[] assets = command.symbol().split("-");
        if (assets[0].equals(assets[1])) {
            throw new IllegalArgumentException("base and quote assets must differ");
        }
        if (command.type() == OrderType.LIMIT) {
            exact(command.price());
            exact(command.quantity());
            BigDecimal amount = command.price().multiply(command.quantity());
            if (amount.precision() - amount.scale() > 20) {
                throw new IllegalArgumentException("spot order notional exceeds storage precision");
            }
        }
    }

    /** 已持有交易对锁时建立资金市场；不迁移或重解释历史未冻结订单。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFundedMarket(String symbol) {
        if (funds.fundedMarket(symbol) == 0) {
            if (funds.existingOrders(symbol) != 0) {
                throw new IllegalStateException("legacy market requires explicit funding migration");
            }
            funds.insertMarket(symbol);
        }
    }

    /** 原始撮合演示不得绕过预占进入资金市场。 */
    public void requireUnfundedMarket(String symbol) {
        if (funds.fundedMarket(symbol) != 0) {
            throw new IllegalArgumentException("funded market requires controlled trading entry");
        }
    }

    /**
     * 捕获本次新订单和所有成交，必须加入已有撮合事务；重放不重复冻结或写事件。
     * 收款账户只自动建立零余额账户，付款账户必须在盘前已经存在。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void capture(MatchingResult result) {
        OrderView taker = result.order();
        if (taker.duplicate()) {
            if (funds.reservation(taker.orderId()) == null) {
                throw new IllegalStateException("historical order has no funded reservation");
            }
            return;
        }
        String[] assets = taker.symbol().split("-");
        String payAsset = taker.side() == OrderSide.BUY ? assets[1] : assets[0];
        String receiveAsset = taker.side() == OrderSide.BUY ? assets[0] : assets[1];
        funds.ensureReceiver(UUID.randomUUID(), taker.userId(), receiveAsset);
        UUID payer = Objects.requireNonNull(funds.accountId(taker.userId(), payAsset), "payer").accountId();
        UUID receiver = funds.accountId(taker.userId(), receiveAsset).accountId();

        List<UUID> payers = new ArrayList<>();
        payers.add(payer);
        for (TradeView trade : result.trades()) {
            payers.add(Objects.requireNonNull(funds.reservation(trade.makerOrderId()),
                "maker must have funds reserved").payerAccountId());
        }
        UuidOrder.sortAndRemoveDuplicates(payers);
        for (UUID id : payers) {
            if (funds.lockFunds(id).financialHold()) {
                throw new IllegalStateException("financial account frozen for review");
            }
        }
        BigDecimal required = taker.side() == OrderSide.BUY
            ? taker.price().multiply(taker.originalQuantity()) : taker.originalQuantity();
        if (funds.funds(payer).available().compareTo(required) < 0) {
            // 盘前快照之后可能被其他资金事务占用；失败回滚整单，不提交假批准。
            throw new IllegalStateException("insufficient available balance after concurrent update");
        }
        requireChanged(funds.reserve(taker.orderId(), payer, receiver, required));
        change("reserve:" + taker.orderId(), payer, ZERO, required, ZERO);

        for (TradeView trade : result.trades()) {
            OrderView maker = matching.findOrder(trade.makerOrderId());
            OrderView buy = taker.side() == OrderSide.BUY ? taker : maker;
            OrderView sell = taker.side() == OrderSide.SELL ? taker : maker;
            stage(trade, buy, trade.quoteAmount(), buy.price().multiply(trade.quantity()));
            stage(trade, sell, trade.quantity(), trade.quantity());
            var buyFunds = funds.reservation(buy.orderId());
            var sellFunds = funds.reservation(sell.orderId());
            requireChanged(funds.insertDelivery(new SpotFundsMapper.DeliveryRow(trade.tradeId(),
                buy.orderId(), sell.orderId(), buyFunds.payerAccountId(), buyFunds.receiverAccountId(),
                sellFunds.payerAccountId(), sellFunds.receiverAccountId(), assets[0], assets[1],
                trade.quantity(), trade.quoteAmount(), "PENDING", null, null)));
            outbox.insert(UUID.randomUUID(), trade.tradeId().toString(), "SPOT_DVP_REQUESTED",
                encode(new SpotDeliveryCommand("spot-dvp:" + trade.tradeId(), trade.tradeId())));
        }
        if (taker.status() == OrderStatus.CANCELED || taker.status() == OrderStatus.REJECTED) {
            releaseCanceled(taker.orderId());
        }
    }

    /** 成交按实际价格转在途；买方限价与成交价的差额立即返还可用。 */
    private void stage(TradeView trade, OrderView order, BigDecimal actual, BigDecimal budget) {
        if (actual.signum() <= 0 || budget.compareTo(actual) < 0) {
            throw new IllegalStateException("trade exceeds reserved limit price");
        }
        var reservation = funds.reservation(order.orderId());
        requireChanged(funds.changeReservation(order.orderId(), budget.negate(), actual, ZERO,
            budget.subtract(actual)));
        change("stage:" + trade.tradeId(), reservation.payerAccountId(), ZERO, budget.negate(), actual);
    }

    /**
     * 撤单只释放 held，不释放 pending；必须与订单撤销同事务。
     * 单账户撤单不会持有其他账户锁，因此与多账户有序交割无反向锁序。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseCanceled(UUID orderId) {
        var row = funds.reservation(orderId);
        if (row == null) {
            return; // 原有纯撮合实验没有资金预占，不能追溯伪造一笔释放。
        }
        funds.lockFunds(row.payerAccountId());
        row = funds.reservation(orderId);
        if (row.held().signum() == 0) {
            return;
        }
        BigDecimal held = row.held();
        requireChanged(funds.changeReservation(orderId, held.negate(), ZERO, ZERO, held));
        change("cancel:" + orderId, row.payerAccountId(), ZERO, held.negate(), ZERO);
        outbox.insert(UUID.randomUUID(), orderId.toString(), "SPOT_RESERVATION_RELEASED",
            encode(java.util.Map.of("orderId", orderId, "released", held)));
    }

    /** 查看真实资金分桶，不改变状态。 */
    public SpotFundsMapper.FundsRow view(UUID accountId) {
        return Objects.requireNonNull(funds.funds(accountId), "account not found");
    }

    /** 仅供内部场景按自己创建的用户和资产读取模拟资金；不新增公开账户查询接口。 */
    public SpotFundsMapper.FundsRow view(String userId, String asset) {
        return view(Objects.requireNonNull(funds.accountId(userId, asset), "account not found").accountId());
    }

    /** 对账发现差异时冻结待审核，不直接修改余额或历史。 */
    @Transactional
    public boolean reconcile(UUID accountId) {
        var row = funds.lockFunds(accountId);
        var expected = funds.recompute(accountId);
        boolean clean = equal(row.balance(), expected.expectedBalance())
            && equal(row.reservedBalance(), expected.journalHeld())
            && equal(row.reservedBalance(), expected.orderHeld())
            && equal(row.pendingDebit(), expected.journalPending())
            && equal(row.pendingDebit(), expected.orderPending());
        if (!clean) {
            funds.freeze(accountId);
            funds.issue(UUID.randomUUID(), accountId);
        }
        return clean && !row.financialHold();
    }

    /** 账户与分桶审计必须在同一调用事务内同时写入。 */
    private void change(String key, UUID id, BigDecimal balance, BigDecimal held, BigDecimal pending) {
        requireChanged(funds.changeFunds(id, balance, held, pending));
        requireChanged(funds.journal(key, id, balance.subtract(held).subtract(pending), held, pending, balance));
    }

    /** 所有资金更新必须精确影响一条记录。 */
    private static void requireChanged(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("spot funds update rejected");
        }
    }

    /** 精度错误属于输入拒绝，不做默默舍入。 */
    private static void exact(BigDecimal value) {
        try {
            value.setScale(8, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("funded spot price and quantity support at most 8 decimals", exception);
        }
    }

    /** 按数值比较资金。 */
    private static boolean equal(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    /** 序列化失败必须回滚整个业务事务。 */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot encode spot event", exception);
        }
    }
}
