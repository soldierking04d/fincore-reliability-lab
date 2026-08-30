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

@Service
public class MatchingService {
    private static final String ORDER_COLUMNS = """
        order_id, client_order_id, user_id, symbol, side, order_type, price,
        original_quantity, executed_quantity, remaining_quantity, status,
        order_sequence, version, COALESCE(detail, '') AS detail
        """;
    private static final RowMapper<OrderView> ORDER_MAPPER =
        (rs, row) -> mapOrder(rs, false);
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

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Counter accepted;
    private final Counter duplicates;
    private final Counter trades;
    private final Counter rejected;

    public MatchingService(JdbcTemplate jdbc, ObjectMapper json, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.json = json;
        this.accepted = registry.counter("fincore.matching.orders.accepted");
        this.duplicates = registry.counter("fincore.matching.orders.duplicate");
        this.trades = registry.counter("fincore.matching.trades.executed");
        this.rejected = registry.counter("fincore.matching.orders.rejected");
    }

    @Transactional
    public MatchingResult place(PlaceOrderCommand command) {
        lockSymbol(command.symbol());
        Optional<OrderView> replay = findByClientOrder(command.userId(), command.clientOrderId());
        if (replay.isPresent()) {
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
            Optional<OrderView> candidate = bestMaker(command.symbol(), command.side().opposite());
            if (candidate.isEmpty()) break;
            OrderView maker = candidate.get();
            if (!MatchingPolicy.crosses(command.side(), command.type(), command.price(), maker.price())) break;

            if (maker.userId().equals(command.userId())) {
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
            OrderStatus terminal = executions.isEmpty() ? OrderStatus.REJECTED : OrderStatus.CANCELED;
            String detail = executions.isEmpty() ? "no liquidity" : "unfilled market remainder canceled";
            takerStatus = terminalize(orderId, takerStatus, terminal, detail);
            rejected.increment();
        }

        return new MatchingResult(get(orderId), executions);
    }

    @Transactional
    public OrderView cancel(UUID orderId, String userId) {
        OrderView snapshot = get(orderId);
        lockSymbol(snapshot.symbol());
        OrderView current = getForUpdate(orderId);
        if (!current.userId().equals(userId)) {
            throw new IllegalArgumentException("order does not belong to user");
        }
        if (current.status() == OrderStatus.CANCELED) return current;
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

    public OrderView get(UUID orderId) {
        return jdbc.queryForObject(
            "SELECT " + ORDER_COLUMNS + " FROM matching_order WHERE order_id=?",
            ORDER_MAPPER, orderId);
    }

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

    private Optional<OrderView> findByClientOrder(String userId, String clientOrderId) {
        return jdbc.query(
            "SELECT " + ORDER_COLUMNS + " FROM matching_order WHERE user_id=? AND client_order_id=?",
            ORDER_MAPPER, userId, clientOrderId).stream().findFirst();
    }

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

    private List<TradeView> tradesForTaker(UUID orderId) {
        return jdbc.query("""
            SELECT trade_id, symbol, maker_order_id, taker_order_id,
                   price, quantity, quote_amount, trade_sequence
            FROM trade_execution WHERE taker_order_id=?
            ORDER BY trade_sequence
            """, TRADE_MAPPER, orderId);
    }

    private void updateFilledOrder(UUID orderId, long version, BigDecimal quantity, OrderStatus status) {
        int changed = jdbc.update("""
            UPDATE matching_order
            SET executed_quantity=executed_quantity+?, remaining_quantity=remaining_quantity-?,
                status=?, version=version+1, updated_at=now()
            WHERE order_id=? AND version=? AND status IN ('OPEN', 'PARTIALLY_FILLED')
              AND remaining_quantity>=?
            """, quantity, quantity, status.name(), orderId, version, quantity);
        if (changed != 1) throw new IllegalStateException("maker CAS update rejected");
    }

    private void updateTaker(UUID orderId, BigDecimal quantity, OrderStatus status) {
        int changed = jdbc.update("""
            UPDATE matching_order
            SET executed_quantity=executed_quantity+?, remaining_quantity=remaining_quantity-?,
                status=?, version=version+1, updated_at=now()
            WHERE order_id=? AND status IN ('OPEN', 'PARTIALLY_FILLED')
              AND remaining_quantity>=?
            """, quantity, quantity, status.name(), orderId, quantity);
        if (changed != 1) throw new IllegalStateException("taker update rejected");
    }

    private OrderStatus terminalize(UUID orderId, OrderStatus from, OrderStatus to, String detail) {
        int changed = jdbc.update("""
            UPDATE matching_order SET status=?, detail=?, version=version+1, updated_at=now()
            WHERE order_id=? AND status=?
            """, to.name(), detail, orderId, from.name());
        if (changed != 1) throw new IllegalStateException("order transition rejected: " + from + " -> " + to);
        audit(orderId, from, to, detail);
        return to;
    }

    private OrderView getForUpdate(UUID orderId) {
        return jdbc.queryForObject(
            "SELECT " + ORDER_COLUMNS + " FROM matching_order WHERE order_id=? FOR UPDATE",
            ORDER_MAPPER, orderId);
    }

    private void audit(UUID orderId, OrderStatus from, OrderStatus to, String reason) {
        jdbc.update("""
            INSERT INTO matching_audit(audit_id, order_id, from_status, to_status, reason)
            VALUES (?, ?, ?, ?, ?)
            """, UUID.randomUUID(), orderId, from == null ? null : from.name(), to.name(), reason);
    }

    private void outbox(String aggregateId, String eventType, Map<String, Object> payload) {
        jdbc.update("""
            INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload)
            VALUES (?, ?, ?, ?)
            """, UUID.randomUUID(), aggregateId, eventType, toJson(payload));
    }

    private void lockSymbol(String symbol) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (rs, row) -> Boolean.TRUE, symbol);
    }

    private long nextSequence(String symbol) {
        Long value = jdbc.queryForObject("""
            INSERT INTO matching_sequence(symbol, next_value) VALUES (?, 2)
            ON CONFLICT(symbol) DO UPDATE
                SET next_value=matching_sequence.next_value+1
            RETURNING next_value-1
            """, Long.class, symbol);
        if (value == null) throw new IllegalStateException("sequence allocation failed");
        return value;
    }

    private static void requireSameRequest(PlaceOrderCommand command, OrderView existing) {
        boolean same = command.symbol().equals(existing.symbol())
            && command.side() == existing.side()
            && command.type() == existing.type()
            && command.quantity().compareTo(existing.originalQuantity()) == 0
            && sameDecimal(command.price(), existing.price());
        if (!same) throw new IllegalArgumentException("clientOrderId replay has conflicting payload");
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) return left == right;
        return left.compareTo(right) == 0;
    }

    private static OrderView asDuplicate(OrderView order, String detail) {
        return new OrderView(order.orderId(), order.clientOrderId(), order.userId(), order.symbol(),
            order.side(), order.type(), order.price(), order.originalQuantity(),
            order.executedQuantity(), order.remainingQuantity(), order.status(),
            order.sequence(), order.version(), true, detail);
    }

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

    private static String normalizeSymbol(String rawSymbol) {
        Objects.requireNonNull(rawSymbol, "symbol");
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9]{2,20}-[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("symbol must use BASE-QUOTE format");
        }
        return symbol;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize matching event", e);
        }
    }
}
