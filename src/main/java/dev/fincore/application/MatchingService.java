package dev.fincore.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.MatchingPolicy;
import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderBookView;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderStatus;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.OrderView;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.domain.TradingIdentifiers;
import dev.fincore.infrastructure.persistence.mapper.MatchingMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单撮合应用服务。
 *
 * <p><strong>解决的问题：</strong>在多实例、并发下单和请求重试下保持价格优先、时间优先，生成唯一
 * 订单与成交，并让订单、审计和事件原子提交。</p>
 *
 * <p><strong>执行链路：</strong>先取得交易对事务锁，再核验 {@code clientOrderId}，分配持久序号，
 * 通过索引和 {@code FOR UPDATE} 每次选出一个最优 Maker，计算成交量并以 CAS 更新双方状态，最后
 * 同事务写成交、审计和 Outbox。</p>
 *
 * <p><strong>CPU 与数据访问：</strong>本类不在 Java 堆维护第二份权威订单簿，也不对全部订单排序；
 * 最优价筛选交给数据库复合索引，应用每轮只保留当前 Maker 和本次成交。symbol 已在上游按 Lane
 * 串行，减少同实例锁竞争和 CPU 上下文切换。金额使用 BigDecimal 是正确性成本，不能换成浮点数。
 * 当前数据库订单簿面向可靠性实验，不宣称达到内存撮合的微秒级延迟。</p>
 *
 * <p><strong>正确性边界：</strong>本地 Lane 之外仍使用 PostgreSQL advisory lock 保证跨实例顺序。
 * {@code clientOrderId} 命中后继续核对原始参数；任一写入失败整体回滚，不返回部分成功。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Service
public class MatchingService {
    /** 订单簿、成交和撮合审计持久化接口。 */
    private final MatchingMapper matchingMapper;
    /** 事务 Outbox 持久化接口。 */
    private final OutboxMapper outboxMapper;
    /** 事件载荷序列化器。 */
    private final ObjectMapper json;
    /** 已受理订单计数器。 */
    private final Counter accepted;
    /** 幂等重放计数器。 */
    private final Counter duplicates;
    /** 已生成成交计数器。 */
    private final Counter trades;
    /** 被拒绝或撤销的订单计数器。 */
    private final Counter rejected;
    /** 受控市场的委托资金预占与撤单释放。 */
    private final SpotFundsService spotFunds;

    /**
     * 创建撮合服务并注册业务指标。
     *
     * @param matchingMapper 撮合持久化接口
     * @param outboxMapper 事务 Outbox 持久化接口
     * @param json JSON 序列化器
     * @param registry Micrometer 指标注册表
     */
    public MatchingService(MatchingMapper matchingMapper, OutboxMapper outboxMapper,
                           ObjectMapper json, MeterRegistry registry, SpotFundsService spotFunds) {
        this.matchingMapper = matchingMapper;
        this.outboxMapper = outboxMapper;
        this.json = json;
        this.spotFunds = spotFunds;
        this.accepted = registry.counter("fincore.matching.orders.accepted");
        this.duplicates = registry.counter("fincore.matching.orders.duplicate");
        this.trades = registry.counter("fincore.matching.trades.executed");
        this.rejected = registry.counter("fincore.matching.orders.rejected");
    }

    /**
     * 受理并撮合订单。
     *
     * <p>该方法的事务边界覆盖订单写入、Maker/Taker 数量更新、成交、审计与 Outbox。任意一步失败都会
     * 整体回滚，不能返回“部分成功”。</p>
     *
     * @param command 已完成格式校验的下单命令
     * @return 最终订单快照以及本次请求产生的成交列表
     */
    @Transactional(rollbackFor = Exception.class)
    public MatchingResult place(PlaceOrderCommand command) {
        lockSymbol(command.symbol());
        spotFunds.requireUnfundedMarket(command.symbol());
        return placeInternal(command);
    }

    /** 只供已完成盘前决定的内部入口调用；资金与撮合必须同事务。 */
    @Transactional(rollbackFor = Exception.class)
    public MatchingResult placeFunded(PlaceOrderCommand command) {
        lockSymbol(command.symbol());
        spotFunds.requireFundedMarket(command.symbol());
        MatchingResult result = placeInternal(command);
        spotFunds.capture(result);
        return result;
    }

    /** 盘前与撮合统一先取交易对锁，之后才能持有风控和账户锁。 */
    public void lockForTrading(String symbol) {
        lockSymbol(symbol);
    }

    /** 共用价格时间优先撮合算法，资金市场与历史纯撮合市场不混单。 */
    private MatchingResult placeInternal(PlaceOrderCommand command) {
        // 先锁定交易对，使同一交易对的价格时间优先顺序在多实例部署时仍然确定。
        lockSymbol(command.symbol());
        Optional<OrderView> replay = findByClientOrder(command.userId(), command.clientOrderId());
        if (replay.isPresent()) {
            // 命中幂等键后必须核对完整业务参数，不能把冲突请求伪装成成功重放。
            requireSameRequest(command, replay.get());
            duplicates.increment();
            OrderView duplicate = asDuplicate(replay.get(), "duplicate client order");
            return new MatchingResult(duplicate, tradesForTaker(duplicate.orderId()));
        }

        // 序号由数据库按 symbol 单调分配；不能用 JVM 时间或本地 AtomicLong 代替跨实例顺序。
        UUID orderId = UUID.randomUUID();
        long orderSequence = nextSequence(command.symbol());
        matchingMapper.insertOrder(orderId, command, orderSequence);
        audit(orderId, null, OrderStatus.OPEN, "order accepted");
        accepted.increment();

        MatchProgress progress = executeMatches(command, orderId);
        closeMarketRemainder(command, orderId, progress);
        return new MatchingResult(get(orderId), progress.executions());
    }

    /** 每轮只锁一个最优 Maker，避免把整本订单加载到堆或扩大行锁范围。 */
    private MatchProgress executeMatches(PlaceOrderCommand command, UUID orderId) {
        MatchProgress progress = new MatchProgress(
            command.quantity(), OrderStatus.OPEN, new java.util.ArrayList<>(), false);
        while (progress.remaining().signum() > 0 && !progress.stopped()) {
            progress = executeNextMatch(command, orderId, progress);
        }
        return progress;
    }

    /**
     * 锁定并处理一笔最优 Maker；所有写入仍处于调用方开启的同一个数据库事务。
     */
    private MatchProgress executeNextMatch(PlaceOrderCommand command, UUID orderId,
                                           MatchProgress progress) {
        // 复合索引完成价格/时间排序，FOR UPDATE 使选择与后续 CAS 基于同一事务快照。
        Optional<OrderView> candidate = bestMaker(command.symbol(), command.side().opposite());
        if (candidate.isEmpty()) {
            return progress.stop();
        }
        OrderView maker = candidate.get();
        if (!MatchingPolicy.crosses(command.side(), command.type(), command.price(), maker.price())) {
            return progress.stop();
        }
        if (maker.userId().equals(command.userId())) {
            // 实验采用 CANCEL_TAKER 自成交保护，避免同一账户人为制造成交量。
            OrderStatus canceled = terminalize(orderId, progress.takerStatus(),
                OrderStatus.CANCELED, "self-trade prevention: CANCEL_TAKER");
            rejected.increment();
            outbox(orderId.toString(), "MATCHING_ORDER_CANCELED", Map.of(
                "eventType", "MATCHING_ORDER_CANCELED", "orderId", orderId,
                "symbol", command.symbol(), "reason", "SELF_TRADE_PREVENTION"));
            return new MatchProgress(progress.remaining(), canceled, progress.executions(), true);
        }

        BigDecimal fillQuantity = progress.remaining().min(maker.remainingQuantity());
        BigDecimal quoteAmount = MatchingPolicy.quoteAmount(maker.price(), fillQuantity);
        long tradeSequence = nextSequence(command.symbol());
        UUID tradeId = UUID.randomUUID();
        matchingMapper.insertTrade(tradeId, command.symbol(), maker.orderId(), orderId,
            maker.price(), fillQuantity, quoteAmount, tradeSequence);

        BigDecimal makerRemaining = maker.remainingQuantity().subtract(fillQuantity);
        OrderStatus makerStatus = makerRemaining.signum() == 0
            ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        updateFilledOrder(maker.orderId(), maker.version(), fillQuantity, makerStatus);
        audit(maker.orderId(), maker.status(), makerStatus, "matched as maker");

        BigDecimal takerRemaining = progress.remaining().subtract(fillQuantity);
        OrderStatus takerStatus = takerRemaining.signum() == 0
            ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        updateTaker(orderId, fillQuantity, takerStatus);
        audit(orderId, progress.takerStatus(), takerStatus, "matched as taker");
        recordExecution(command, orderId, maker, fillQuantity, quoteAmount, tradeSequence,
            tradeId, progress.executions());
        return new MatchProgress(takerRemaining, takerStatus, progress.executions(), false);
    }

    /** 保存对外成交视图和同事务 Outbox 事件。 */
    private void recordExecution(PlaceOrderCommand command, UUID orderId, OrderView maker,
                                 BigDecimal fillQuantity, BigDecimal quoteAmount,
                                 long tradeSequence, UUID tradeId, List<TradeView> executions) {
        TradeView execution = new TradeView(tradeId, command.symbol(), maker.orderId(), orderId,
            maker.price(), fillQuantity, quoteAmount, tradeSequence);
        executions.add(execution);
        trades.increment();
        outbox(tradeId.toString(), "MATCHING_TRADE_EXECUTED", Map.of(
            "eventType", "MATCHING_TRADE_EXECUTED", "tradeId", tradeId,
            "symbol", command.symbol(), "makerOrderId", maker.orderId(),
            "takerOrderId", orderId, "price", maker.price(), "quantity", fillQuantity,
            "quoteAmount", quoteAmount, "sequence", tradeSequence));
    }

    /** 市价单不能挂单；把无成交和部分成交后的余量转换成明确终态。 */
    private void closeMarketRemainder(PlaceOrderCommand command, UUID orderId,
                                      MatchProgress progress) {
        if (command.type() != OrderType.MARKET || progress.remaining().signum() <= 0
            || !progress.takerStatus().isOpen()) {
            return;
        }
        OrderStatus terminal = progress.executions().isEmpty()
            ? OrderStatus.REJECTED : OrderStatus.CANCELED;
        String detail = progress.executions().isEmpty()
            ? "no liquidity" : "unfilled market remainder canceled";
        terminalize(orderId, progress.takerStatus(), terminal, detail);
        rejected.increment();
    }

    /** 一轮撮合后的剩余量、Taker 状态、已产生成交和停止标记。 */
    private record MatchProgress(BigDecimal remaining, OrderStatus takerStatus,
                                 List<TradeView> executions, boolean stopped) {
        /** 保留当前撮合事实并停止继续读取订单簿。 */
        private MatchProgress stop() {
            return new MatchProgress(remaining, takerStatus, executions, true);
        }
    }

    /**
     * 撤销仍处于开放状态且属于指定用户的订单。
     *
     * @param orderId 订单编号
     * @param userId 发起撤单的用户编号
     * @return 撤单后的订单快照；重复撤销已撤订单时直接返回当前快照
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderView cancel(UUID orderId, String userId) {
        OrderView snapshot = get(orderId);
        lockSymbol(snapshot.symbol());
        OrderView current = getForUpdate(orderId);
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("order does not belong to user");
        }
        if (current.status() == OrderStatus.CANCELED) {
            return current;
        }
        if (!current.status().isOpen()) {
            throw new BusinessConflictException(
                "terminal order cannot be canceled: " + current.status());
        }
        terminalize(orderId, current.status(), OrderStatus.CANCELED, "canceled by user");
        spotFunds.releaseCanceled(orderId);
        outbox(orderId.toString(), "MATCHING_ORDER_CANCELED", Map.of(
            "eventType", "MATCHING_ORDER_CANCELED",
            "orderId", orderId,
            "symbol", current.symbol(),
            "reason", "USER_REQUEST"));
        return get(orderId);
    }

    /**
     * 查询单笔订单的当前快照。
     *
     * @param orderId 订单编号
     * @return 订单快照
     */
    public OrderView get(UUID orderId) {
        return matchingMapper.findOrder(orderId);
    }

    /**
     * 查询聚合后的买卖盘口。
     *
     * @param rawSymbol 原始交易对，方法内部会统一转成大写
     * @param requestedDepth 请求的档位深度，实际限制在 1 至 100 档
     * @return 当前盘口以及最新成交序号
     */
    public OrderBookView book(String rawSymbol, int requestedDepth) {
        String symbol = normalizeSymbol(rawSymbol);
        // 限制最大档位，避免一次只读请求聚合超大结果集并挤占数据库 CPU、网络和堆。
        int depth = Math.max(1, Math.min(requestedDepth, 100));
        List<OrderBookView.BookLevel> bids = levels(symbol, OrderSide.BUY, depth);
        List<OrderBookView.BookLevel> asks = levels(symbol, OrderSide.SELL, depth);
        return new OrderBookView(symbol, bids, asks, matchingMapper.findLastTradeSequence(symbol));
    }

    /**
     * 查询指定交易对的最近成交。
     *
     * @param rawSymbol 原始交易对
     * @param requestedLimit 请求数量，实际限制在 1 至 200 条
     * @return 按成交序号倒序排列的成交列表
     */
    public List<TradeView> recentTrades(String rawSymbol, int requestedLimit) {
        String symbol = normalizeSymbol(rawSymbol);
        // 最近成交接口同样限长，防止客户端把交易查询接口当成无限导出接口。
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        return matchingMapper.findRecentTrades(symbol, limit);
    }

    /**
     * 按用户与客户端订单号查找幂等记录。
     *
     * @param userId 用户编号
     * @param clientOrderId 客户端订单号
     * @return 已存在的订单；不存在时为空
     */
    private Optional<OrderView> findByClientOrder(String userId, String clientOrderId) {
        return Optional.ofNullable(matchingMapper.findByClientOrder(userId, clientOrderId));
    }

    /**
     * 按价格时间优先规则选取并锁定最优 Maker。
     *
     * @param symbol 交易对
     * @param side Maker 方向
     * @return 可成交的最优 Maker
     */
    private Optional<OrderView> bestMaker(String symbol, OrderSide side) {
        OrderView order = side == OrderSide.BUY
            ? matchingMapper.lockBestBuy(symbol)
            : matchingMapper.lockBestSell(symbol);
        return Optional.ofNullable(order);
    }

    /**
     * 将开放订单按价格聚合成盘口档位。
     *
     * @param symbol 交易对
     * @param side 买卖方向
     * @param depth 最大档位数
     * @return 聚合后的盘口档位
     */
    private List<OrderBookView.BookLevel> levels(String symbol, OrderSide side, int depth) {
        return side == OrderSide.BUY
            ? matchingMapper.findBidLevels(symbol, depth)
            : matchingMapper.findAskLevels(symbol, depth);
    }

    /**
     * 查询某个 Taker 订单已经产生的全部成交，用于幂等重放。
     *
     * @param orderId Taker 订单编号
     * @return 按成交序号正序排列的成交列表
     */
    private List<TradeView> tradesForTaker(UUID orderId) {
        return matchingMapper.findTradesForTaker(orderId);
    }

    /**
     * 使用乐观锁累计 Maker 成交量。
     *
     * @param orderId Maker 订单编号
     * @param version 读取 Maker 时的版本号
     * @param quantity 本次成交数量
     * @param status 更新后的状态
     */
    private void updateFilledOrder(UUID orderId, long version, BigDecimal quantity, OrderStatus status) {
        int changed = matchingMapper.updateMaker(orderId, version, quantity, status.name());
        if (changed != 1) {
            throw new IllegalStateException("maker CAS update rejected");
        }
    }

    /**
     * 累计当前 Taker 的成交量。
     *
     * @param orderId Taker 订单编号
     * @param quantity 本次成交数量
     * @param status 更新后的状态
     */
    private void updateTaker(UUID orderId, BigDecimal quantity, OrderStatus status) {
        int changed = matchingMapper.updateTaker(orderId, quantity, status.name());
        if (changed != 1) {
            throw new IllegalStateException("taker update rejected");
        }
    }

    /**
     * 将订单迁移到终态并写入审计记录。
     *
     * @param orderId 订单编号
     * @param from 期望的当前状态
     * @param to 目标终态
     * @param detail 状态变化原因
     * @return 目标状态
     */
    private OrderStatus terminalize(UUID orderId, OrderStatus from, OrderStatus to, String detail) {
        int changed = matchingMapper.transition(orderId, from.name(), to.name(), detail);
        if (changed != 1) {
            throw new IllegalStateException("order transition rejected: " + from + " -> " + to);
        }
        audit(orderId, from, to, detail);
        return to;
    }

    /**
     * 使用行锁读取订单，确保撤单校验与状态迁移基于同一版本。
     *
     * @param orderId 订单编号
     * @return 已加行锁的订单快照
     */
    private OrderView getForUpdate(UUID orderId) {
        return matchingMapper.lockOrder(orderId);
    }

    /**
     * 追加订单状态审计记录。
     *
     * @param orderId 订单编号
     * @param from 原状态；新建订单时为空
     * @param to 新状态
     * @param reason 变化原因
     */
    private void audit(UUID orderId, OrderStatus from, OrderStatus to, String reason) {
        matchingMapper.insertAudit(UUID.randomUUID(), orderId,
            from == null ? null : from.name(), to.name(), reason);
    }

    /**
     * 在当前事务内保存待发布事件。
     *
     * @param aggregateId 聚合根编号
     * @param eventType 事件类型
     * @param payload 事件载荷
     */
    private void outbox(String aggregateId, String eventType, Map<String, Object> payload) {
        outboxMapper.insert(UUID.randomUUID(), aggregateId, eventType, toJson(payload));
    }

    /**
     * 获取交易对级事务锁。
     *
     * @param symbol 交易对
     */
    private void lockSymbol(String symbol) {
        matchingMapper.lockSymbol(symbol);
    }

    /**
     * 在数据库中原子分配交易对序号。
     *
     * @param symbol 交易对
     * @return 本次分配的单调递增序号
     */
    private long nextSequence(String symbol) {
        Long value = matchingMapper.nextSequence(symbol);
        if (value == null) {
            throw new IllegalStateException("sequence allocation failed");
        }
        return value.longValue();
    }

    /**
     * 校验幂等重放的业务参数与原订单完全一致。
     *
     * @param command 本次请求
     * @param existing 已存在订单
     */
    private static void requireSameRequest(PlaceOrderCommand command, OrderView existing) {
        boolean same = command.symbol().equals(existing.symbol())
            && command.side() == existing.side()
            && command.type() == existing.type()
            && command.quantity().compareTo(existing.originalQuantity()) == 0
            && sameDecimal(command.price(), existing.price());
        if (!same) {
            throw new IllegalArgumentException("clientOrderId replay has conflicting payload");
        }
    }

    /**
     * 以数值语义比较可空金额，忽略 BigDecimal 的 scale 差异。
     *
     * @param left 左值
     * @param right 右值
     * @return 数值相等或同时为空时为 {@code true}
     */
    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    /**
     * 将订单快照标记为幂等重放结果。
     *
     * @param order 原订单快照
     * @param detail 重放说明
     * @return 标记后的不可变快照
     */
    private static OrderView asDuplicate(OrderView order, String detail) {
        return new OrderView(order.orderId(), order.clientOrderId(), order.userId(), order.symbol(),
            order.side(), order.type(), order.price(), order.originalQuantity(),
            order.executedQuantity(), order.remainingQuantity(), order.status(),
            order.sequence(), order.version(), true, detail);
    }

    /**
     * 规范化并校验交易对。
     *
     * @param rawSymbol 原始交易对
     * @return 大写的 {@code BASE-QUOTE} 格式交易对
     */
    private static String normalizeSymbol(String rawSymbol) {
        Objects.requireNonNull(rawSymbol, "symbol");
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (!TradingIdentifiers.isSymbol(symbol)) {
            throw new IllegalArgumentException("symbol must use BASE-QUOTE format");
        }
        return symbol;
    }

    /**
     * 将事件载荷序列化为 JSON。
     *
     * @param value 待序列化对象
     * @return JSON 文本
     */
    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot serialize matching event", exception);
        }
    }
}
