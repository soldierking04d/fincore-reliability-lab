package dev.fincore.infrastructure.persistence.mapper;

import dev.fincore.domain.OrderBookView;
import dev.fincore.domain.OrderView;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 订单簿、成交和撮合审计 MyBatis Mapper。
 *
 * <p>买卖方向使用两个固定 SQL 方法表达排序，禁止通过字符串替换拼接排序方向，从而避免动态 SQL
 * 注入，也让价格时间优先规则可以被代码审查直接确认。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface MatchingMapper {
    /** 获取交易对级 PostgreSQL 事务锁。 */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{symbol}, 0))")
    Object lockSymbol(@Param("symbol") String symbol);

    /** 依据用户和客户端订单号查询幂等记录。 */
    @Select("""
        SELECT order_id AS "orderId", client_order_id AS "clientOrderId",
               user_id AS "userId", symbol, side, order_type AS type, price,
               original_quantity AS "originalQuantity",
               executed_quantity AS "executedQuantity",
               remaining_quantity AS "remainingQuantity", status,
               order_sequence AS sequence, version, FALSE AS duplicate,
               COALESCE(detail, '') AS detail
        FROM matching_order
        WHERE user_id=#{userId} AND client_order_id=#{clientOrderId}
        """)
    OrderView findByClientOrder(@Param("userId") String userId,
                                @Param("clientOrderId") String clientOrderId);

    /** 原子分配交易对内的单调递增序号。 */
    @Select("""
        INSERT INTO matching_sequence(symbol, next_value)
        VALUES (#{symbol}, 2)
        ON CONFLICT(symbol) DO UPDATE
            SET next_value=matching_sequence.next_value+1
        RETURNING next_value-1
        """)
    Long nextSequence(@Param("symbol") String symbol);

    /** 保存新受理订单。 */
    @Insert("""
        INSERT INTO matching_order(
            order_id, client_order_id, user_id, symbol, side, order_type, price,
            original_quantity, executed_quantity, remaining_quantity, status, order_sequence)
        VALUES (
            #{orderId}, #{command.clientOrderId}, #{command.userId}, #{command.symbol},
            #{command.side}, #{command.type}, #{command.price}, #{command.quantity},
            0, #{command.quantity}, 'OPEN', #{orderSequence})
        """)
    int insertOrder(@Param("orderId") UUID orderId,
                    @Param("command") PlaceOrderCommand command,
                    @Param("orderSequence") long orderSequence);

    /** 锁定价格最高且最早到达的有效买单。 */
    @Select("""
        SELECT order_id AS "orderId", client_order_id AS "clientOrderId",
               user_id AS "userId", symbol, side, order_type AS type, price,
               original_quantity AS "originalQuantity",
               executed_quantity AS "executedQuantity",
               remaining_quantity AS "remainingQuantity", status,
               order_sequence AS sequence, version, FALSE AS duplicate,
               COALESCE(detail, '') AS detail
        FROM matching_order
        WHERE symbol=#{symbol} AND side='BUY'
          AND status IN ('OPEN', 'PARTIALLY_FILLED') AND remaining_quantity>0
        ORDER BY price DESC, order_sequence ASC
        LIMIT 1 FOR UPDATE
        """)
    OrderView lockBestBuy(@Param("symbol") String symbol);

    /** 锁定价格最低且最早到达的有效卖单。 */
    @Select("""
        SELECT order_id AS "orderId", client_order_id AS "clientOrderId",
               user_id AS "userId", symbol, side, order_type AS type, price,
               original_quantity AS "originalQuantity",
               executed_quantity AS "executedQuantity",
               remaining_quantity AS "remainingQuantity", status,
               order_sequence AS sequence, version, FALSE AS duplicate,
               COALESCE(detail, '') AS detail
        FROM matching_order
        WHERE symbol=#{symbol} AND side='SELL'
          AND status IN ('OPEN', 'PARTIALLY_FILLED') AND remaining_quantity>0
        ORDER BY price ASC, order_sequence ASC
        LIMIT 1 FOR UPDATE
        """)
    OrderView lockBestSell(@Param("symbol") String symbol);

    /** 追加权威成交事实。 */
    @Insert("""
        INSERT INTO trade_execution(
            trade_id, symbol, maker_order_id, taker_order_id, price,
            quantity, quote_amount, trade_sequence)
        VALUES (#{tradeId}, #{symbol}, #{makerOrderId}, #{takerOrderId}, #{price},
                #{quantity}, #{quoteAmount}, #{tradeSequence})
        """)
    int insertTrade(@Param("tradeId") UUID tradeId,
                    @Param("symbol") String symbol,
                    @Param("makerOrderId") UUID makerOrderId,
                    @Param("takerOrderId") UUID takerOrderId,
                    @Param("price") BigDecimal price,
                    @Param("quantity") BigDecimal quantity,
                    @Param("quoteAmount") BigDecimal quoteAmount,
                    @Param("tradeSequence") long tradeSequence);

    /** 使用版本号和剩余数量条件更新 Maker。 */
    @Update("""
        UPDATE matching_order
        SET executed_quantity=executed_quantity+#{quantity},
            remaining_quantity=remaining_quantity-#{quantity},
            status=#{status}, version=version+1, updated_at=now()
        WHERE order_id=#{orderId} AND version=#{version}
          AND status IN ('OPEN', 'PARTIALLY_FILLED')
          AND remaining_quantity>=#{quantity}
        """)
    int updateMaker(@Param("orderId") UUID orderId,
                    @Param("version") long version,
                    @Param("quantity") BigDecimal quantity,
                    @Param("status") String status);

    /** 累计当前 Taker 的成交数量。 */
    @Update("""
        UPDATE matching_order
        SET executed_quantity=executed_quantity+#{quantity},
            remaining_quantity=remaining_quantity-#{quantity},
            status=#{status}, version=version+1, updated_at=now()
        WHERE order_id=#{orderId} AND status IN ('OPEN', 'PARTIALLY_FILLED')
          AND remaining_quantity>=#{quantity}
        """)
    int updateTaker(@Param("orderId") UUID orderId,
                    @Param("quantity") BigDecimal quantity,
                    @Param("status") String status);

    /** 使用期望原状态迁移订单。 */
    @Update("""
        UPDATE matching_order
        SET status=#{toStatus}, detail=#{detail}, version=version+1, updated_at=now()
        WHERE order_id=#{orderId} AND status=#{fromStatus}
        """)
    int transition(@Param("orderId") UUID orderId,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("detail") String detail);

    /** 查询订单当前快照。 */
    @Select("""
        SELECT order_id AS "orderId", client_order_id AS "clientOrderId",
               user_id AS "userId", symbol, side, order_type AS type, price,
               original_quantity AS "originalQuantity",
               executed_quantity AS "executedQuantity",
               remaining_quantity AS "remainingQuantity", status,
               order_sequence AS sequence, version, FALSE AS duplicate,
               COALESCE(detail, '') AS detail
        FROM matching_order
        WHERE order_id=#{orderId}
        """)
    OrderView findOrder(@Param("orderId") UUID orderId);

    /** 使用行锁查询订单当前快照。 */
    @Select("""
        SELECT order_id AS "orderId", client_order_id AS "clientOrderId",
               user_id AS "userId", symbol, side, order_type AS type, price,
               original_quantity AS "originalQuantity",
               executed_quantity AS "executedQuantity",
               remaining_quantity AS "remainingQuantity", status,
               order_sequence AS sequence, version, FALSE AS duplicate,
               COALESCE(detail, '') AS detail
        FROM matching_order
        WHERE order_id=#{orderId}
        FOR UPDATE
        """)
    OrderView lockOrder(@Param("orderId") UUID orderId);

    /** 查询买方聚合盘口，按价格从高到低排序。 */
    @Select("""
        SELECT price, SUM(remaining_quantity) AS quantity, COUNT(*) AS "orderCount"
        FROM matching_order
        WHERE symbol=#{symbol} AND side='BUY'
          AND status IN ('OPEN', 'PARTIALLY_FILLED') AND remaining_quantity>0
        GROUP BY price ORDER BY price DESC LIMIT #{depth}
        """)
    List<OrderBookView.BookLevel> findBidLevels(@Param("symbol") String symbol,
                                                @Param("depth") int depth);

    /** 查询卖方聚合盘口，按价格从低到高排序。 */
    @Select("""
        SELECT price, SUM(remaining_quantity) AS quantity, COUNT(*) AS "orderCount"
        FROM matching_order
        WHERE symbol=#{symbol} AND side='SELL'
          AND status IN ('OPEN', 'PARTIALLY_FILLED') AND remaining_quantity>0
        GROUP BY price ORDER BY price ASC LIMIT #{depth}
        """)
    List<OrderBookView.BookLevel> findAskLevels(@Param("symbol") String symbol,
                                                @Param("depth") int depth);

    /** 查询交易对最新成交序号。 */
    @Select("""
        SELECT COALESCE(MAX(trade_sequence), 0)
        FROM trade_execution WHERE symbol=#{symbol}
        """)
    long findLastTradeSequence(@Param("symbol") String symbol);

    /** 查询交易对最近成交。 */
    @Select("""
        SELECT trade_id AS "tradeId", symbol, maker_order_id AS "makerOrderId",
               taker_order_id AS "takerOrderId", price, quantity,
               quote_amount AS "quoteAmount", trade_sequence AS sequence
        FROM trade_execution
        WHERE symbol=#{symbol}
        ORDER BY trade_sequence DESC LIMIT #{limit}
        """)
    List<TradeView> findRecentTrades(@Param("symbol") String symbol, @Param("limit") int limit);

    /** 查询指定 Taker 订单产生的全部成交。 */
    @Select("""
        SELECT trade_id AS "tradeId", symbol, maker_order_id AS "makerOrderId",
               taker_order_id AS "takerOrderId", price, quantity,
               quote_amount AS "quoteAmount", trade_sequence AS sequence
        FROM trade_execution
        WHERE taker_order_id=#{orderId}
        ORDER BY trade_sequence
        """)
    List<TradeView> findTradesForTaker(@Param("orderId") UUID orderId);

    /** 追加订单状态审计记录。 */
    @Insert("""
        INSERT INTO matching_audit(audit_id, order_id, from_status, to_status, reason)
        VALUES (#{auditId}, #{orderId}, #{fromStatus}, #{toStatus}, #{reason})
        """)
    int insertAudit(@Param("auditId") UUID auditId,
                    @Param("orderId") UUID orderId,
                    @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus,
                    @Param("reason") String reason);
}
