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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单撮合应用服务。
 *
 * <p>同一交易对在事务内通过 PostgreSQL advisory lock 串行撮合，再按照“价格优先、时间优先”选择
 * Maker。订单、成交、审计记录和 Outbox 事件在同一数据库事务中提交，避免业务状态已经生效但事件丢失。
 * {@code clientOrderId} 仅用于幂等重放，重复请求仍会校验原始参数，防止同一个幂等键承载两笔不同订单。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Service
public class MatchingService {
    /** 查询订单时统一使用的列清单，避免不同接口映射出不一致的订单快照。 */
    private static final String ORDER_COLUMNS = """
        order_id, client_order_id, user_id, symbol, side, order_type, price,
        original_quantity, executed_quantity, remaining_quantity, status,
        order_sequence, version, COALESCE(detail, '') AS detail
        """;
    /** 订单行映射器。 */
    private static final RowMapper<OrderView> ORDER_MAPPER =
        (rs, row) -> mapOrder(rs, false);
    /** 成交行映射器。 */
    private static final RowMapper<TradeView> TRADE_MAPPER =
        (rs, row) -> new TradeView(
            rs.getObject("trade_id", UUID.class),
            rs.getString("symbol"),
            rs.getObject("maker_order_id", UUID.class),
            rs.getObject("taker_order_id", UUID.class),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("quantity"),
            rs.getBigDecimal("quote_amount"),
            rs.getLong("trade_sequence"));

    /** 数据库访问入口。 */
    private final JdbcTemplate jdbc;
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

    /**
     * 创建撮合服务并注册业务指标。
     *
     * @param jdbc 数据库访问入口
     * @param json JSON 序列化器
     * @param registry Micrometer 指标注册表
     */
    public MatchingService(JdbcTemplate jdbc, ObjectMapper json, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.json = json;
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
    @Transactional
    public MatchingResult place(PlaceOrderCommand command) {
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

        UUID orderId = UUID.randomUUID();
        long orderSequence = nextSequence(command.symbol());
        jdbc.update("""
            INSERT INTO matching_order(
                order_id, client_order_id, user_id, symbol, side, order_type, price,
                original_quantity, executed_quantity, remaining_quantity, status, order_sequence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'OPEN', ?)
            """, orderId, command.clientOrderId(), command.userId(), command.symbol(),
            command.side().name(), command.type().name(), command.price(),
            command.quantity(), command.quantity(), orderSequence);
        audit(orderId, null, OrderStatus.OPEN, "order accepted");
        accepted.increment();

        BigDecimal remaining = command.quantity();
        OrderStatus takerStatus = OrderStatus.OPEN;
        List<TradeView> executions = new java.util.ArrayList<>();

        while (remaining.signum() > 0) {
            // FOR UPDATE 锁住当前最优 Maker；价格相同时按持久化序号保证先到先成交。
            Optional<OrderView> candidate = bestMaker(command.symbol(), command.side().opposite());
            if (candidate.isEmpty()) {
                break;
            }
            OrderView maker = candidate.get();
            if (!MatchingPolicy.crosses(command.side(), command.type(), command.price(), maker.price())) {
                break;
            }

            if (maker.userId().equals(command.userId())) {
                // 实验采用 CANCEL_TAKER 自成交保护，避免同一账户人为制造成交量。
                takerStatus = terminalize(orderId, takerStatus, OrderStatus.CANCELED,
                    "self-trade prevention: CANCEL_TAKER");
                rejected.increment();
                outbox(orderId.toString(), "MATCHING_ORDER_CANCELED", Map.of(
                    "eventType", "MATCHING_ORDER_CANCELED",
                    "orderId", orderId,
                    "symbol", command.symbol(),
                    "reason", "SELF_TRADE_PREVENTION"));
                break;
            }

            BigDecimal fillQuantity = remaining.min(maker.remainingQuantity());
            BigDecimal quoteAmount = MatchingPolicy.quoteAmount(maker.price(), fillQuantity);
            long tradeSequence = nextSequence(command.symbol());
            UUID tradeId = UUID.randomUUID();

            jdbc.update("""
                INSERT INTO trade_execution(
                    trade_id, symbol, maker_order_id, taker_order_id, price,
                    quantity, quote_amount, trade_sequence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tradeId, command.symbol(), maker.orderId(), orderId, maker.price(),
                fillQuantity, quoteAmount, tradeSequence);

            // Maker 更新带 version 条件，防止并发写入时发生静默覆盖。
            BigDecimal makerRemaining = maker.remainingQuantity().subtract(fillQuantity);
            OrderStatus makerStatus = makerRemaining.signum() == 0
                ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            updateFilledOrder(maker.orderId(), maker.version(), fillQuantity, makerStatus);
            audit(maker.orderId(), maker.status(), makerStatus, "matched as maker");

            remaining = remaining.subtract(fillQuantity);
            OrderStatus nextTakerStatus = remaining.signum() == 0
                ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            updateTaker(orderId, fillQuantity, nextTakerStatus);
            audit(orderId, takerStatus, nextTakerStatus, "matched as taker");
            takerStatus = nextTakerStatus;

            TradeView execution = new TradeView(
                tradeId, command.symbol(), maker.orderId(), orderId,
                maker.price(), fillQuantity, quoteAmount, tradeSequence);
            executions.add(execution);
            trades.increment();
            // 成交与事件同事务落库，发布失败由 Outbox 后台任务重试。
            outbox(tradeId.toString(), "MATCHING_TRADE_EXECUTED", Map.of(
                "eventType", "MATCHING_TRADE_EXECUTED",
                "tradeId", tradeId,
                "symbol", command.symbol(),
                "makerOrderId", maker.orderId(),
                "takerOrderId", orderId,
                "price", maker.price(),
                "quantity", fillQuantity,
                "quoteAmount", quoteAmount,
                "sequence", tradeSequence));
        }

        if (command.type() == OrderType.MARKET && remaining.signum() > 0 && takerStatus.isOpen()) {
            // 市价单不能挂入订单簿；无成交则拒绝，部分成交后的剩余量则撤销。
            OrderStatus terminal = executions.isEmpty() ? OrderStatus.REJECTED : OrderStatus.CANCELED;
            String detail = executions.isEmpty() ? "no liquidity" : "unfilled market remainder canceled";
            takerStatus = terminalize(orderId, takerStatus, terminal, detail);
            rejected.increment();
        }

        return new MatchingResult(get(orderId), executions);
    }

    /**
     * 撤销仍处于开放状态且属于指定用户的订单。
     *
     * @param orderId 订单编号
     * @param userId 发起撤单的用户编号
     * @return 撤单后的订单快照；重复撤销已撤订单时直接返回当前快照
     */
    @Transactional
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
            throw new IllegalStateException("terminal order cannot be canceled: " + current.status());
        }
        terminalize(orderId, current.status(), OrderStatus.CANCELED, "canceled by user");
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
        return jdbc.queryForObject(
            "SELECT " + ORDER_COLUMNS + " FROM matching_order WHERE order_id=?",
            ORDER_MAPPER, orderId);
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
        int depth = Math.max(1, Math.min(requestedDepth, 100));
        List<OrderBookView.BookLevel> bids = levels(symbol, OrderSide.BUY, depth);
        List<OrderBookView.BookLevel> asks = levels(symbol, OrderSide.SELL, depth);
        Long sequence = jdbc.queryForObject("""
            SELECT COALESCE(MAX(trade_sequence), 0)
            FROM trade_execution WHERE symbol=?
            """, Long.class, symbol);
        return new OrderBookView(symbol, bids, asks, sequence == null ? 0 : sequence);
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
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        return jdbc.query("""
            SELECT trade_id, symbol, maker_order_id, taker_order_id,
                   price, quantity, quote_amount, trade_sequence
            FROM trade_execution WHERE symbol=?
            ORDER BY trade_sequence DESC LIMIT ?
            """, TRADE_MAPPER, symbol, limit);
    }

    /**
     * 按用户与客户端订单号查找幂等记录。
     *
     * @param userId 用户编号
     * @param clientOrderId 客户端订单号
     * @return 已存在的订单；不存在时为空
     */
    private Optional<OrderView> findByClientOrder(String userId, String clientOrderId) {
        return jdbc.query(
            "SELECT " + ORDER_COLUMNS + " FROM matching_order WHERE user_id=? AND client_order_id=?",
            ORDER_MAPPER, userId, clientOrderId).stream().findFirst();
    }

    /**
     * 按价格时间优先规则选取并锁定最优 Maker。
     *
     * @param symbol 交易对
     * @param side Maker 方向
     * @return 可成交的最优 Maker
     */
    private Optional<OrderView> bestMaker(String symbol, OrderSide side) {
        String direction = side == OrderSide.BUY ? "DESC" : "ASC";
        String sql = ("SELECT " + ORDER_COLUMNS + """
            FROM matching_order
            WHERE symbol=? AND side=? AND status IN ('OPEN', 'PARTIALLY_FILLED')
              AND remaining_quantity>0
            ORDER BY price %s, order_sequence ASC LIMIT 1 FOR UPDATE
            """).formatted(direction);
        return jdbc.query(sql, ORDER_MAPPER, symbol, side.name()).stream().findFirst();
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
        String direction = side == OrderSide.BUY ? "DESC" : "ASC";
        String sql = """
            SELECT price, SUM(remaining_quantity) AS quantity, COUNT(*) AS order_count
            FROM matching_order
            WHERE symbol=? AND side=? AND status IN ('OPEN', 'PARTIALLY_FILLED')
              AND remaining_quantity>0
            GROUP BY price ORDER BY price %s LIMIT ?
            """.formatted(direction);
        return jdbc.query(sql, (rs, row) -> new OrderBookView.BookLevel(
            rs.getBigDecimal("price"), rs.getBigDecimal("quantity"), rs.getLong("order_count")),
            symbol, side.name(), depth);
    }

    /**
     * 查询某个 Taker 订单已经产生的全部成交，用于幂等重放。
     *
     * @param orderId Taker 订单编号
     * @return 按成交序号正序排列的成交列表
     */
    private List<TradeView> tradesForTaker(UUID orderId) {
        return jdbc.query("""
            SELECT trade_id, symbol, maker_order_id, taker_order_id,
                   price, quantity, quote_amount, trade_sequence
            FROM trade_execution WHERE taker_order_id=?
            ORDER BY trade_sequence
            """, TRADE_MAPPER, orderId);
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
        int changed = jdbc.update("""
            UPDATE matching_order
            SET executed_quantity=executed_quantity+?, remaining_quantity=remaining_quantity-?,
                status=?, version=version+1, updated_at=now()
            WHERE order_id=? AND version=? AND status IN ('OPEN', 'PARTIALLY_FILLED')
              AND remaining_quantity>=?
            """, quantity, quantity, status.name(), orderId, version, quantity);
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
        int changed = jdbc.update("""
            UPDATE matching_order
            SET executed_quantity=executed_quantity+?, remaining_quantity=remaining_quantity-?,
                status=?, version=version+1, updated_at=now()
            WHERE order_id=? AND status IN ('OPEN', 'PARTIALLY_FILLED')
              AND remaining_quantity>=?
            """, quantity, quantity, status.name(), orderId, quantity);
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
        int changed = jdbc.update("""
            UPDATE matching_order SET status=?, detail=?, version=version+1, updated_at=now()
            WHERE order_id=? AND status=?
            """, to.name(), detail, orderId, from.name());
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
        return jdbc.queryForObject(
            "SELECT " + ORDER_COLUMNS + " FROM matching_order WHERE order_id=? FOR UPDATE",
            ORDER_MAPPER, orderId);
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
        jdbc.update("""
            INSERT INTO matching_audit(audit_id, order_id, from_status, to_status, reason)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID(), orderId, from == null ? null : from.name(), to.name(), reason);
    }

    /**
     * 在当前事务内保存待发布事件。
     *
     * @param aggregateId 聚合根编号
     * @param eventType 事件类型
     * @param payload 事件载荷
     */
    private void outbox(String aggregateId, String eventType, Map<String, Object> payload) {
        jdbc.update("""
            INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload)
            VALUES (?, ?, ?, ?)
            """, UUID.randomUUID(), aggregateId, eventType, toJson(payload));
    }

    /**
     * 获取交易对级事务锁。
     *
     * @param symbol 交易对
     */
    private void lockSymbol(String symbol) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (rs, row) -> Boolean.TRUE, symbol);
    }

    /**
     * 在数据库中原子分配交易对序号。
     *
     * @param symbol 交易对
     * @return 本次分配的单调递增序号
     */
    private long nextSequence(String symbol) {
        Long value = jdbc.queryForObject("""
            INSERT INTO matching_sequence(symbol, next_value) VALUES (?, 2)
            ON CONFLICT(symbol) DO UPDATE
                SET next_value=matching_sequence.next_value+1
            RETURNING next_value-1
            """, Long.class, symbol);
        if (value == null) {
            throw new IllegalStateException("sequence allocation failed");
        }
        return value;
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
     * 将 JDBC 结果集映射为订单领域视图。
     *
     * @param rs 当前结果集
     * @param duplicate 是否为幂等重放
     * @return 订单视图
     * @throws SQLException 读取结果集失败时抛出
     */
    private static OrderView mapOrder(ResultSet rs, boolean duplicate) throws SQLException {
        return new OrderView(
            rs.getObject("order_id", UUID.class),
            rs.getString("client_order_id"),
            rs.getString("user_id"),
            rs.getString("symbol"),
            OrderSide.valueOf(rs.getString("side")),
            OrderType.valueOf(rs.getString("order_type")),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("original_quantity"),
            rs.getBigDecimal("executed_quantity"),
            rs.getBigDecimal("remaining_quantity"),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getLong("order_sequence"),
            rs.getLong("version"),
            duplicate,
            rs.getString("detail"));
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
        if (!symbol.matches("[A-Z0-9]{2,20}-[A-Z0-9]{2,20}")) {
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
