package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code lab} Profile 故障注入与事实断言 Mapper。
 *
 * <p>该接口由实验编排器调用，所有会破坏正常不变量的方法都以 {@code inject} 命名，避免与生产
 * 持久化接口混淆。生产 Profile 不会暴露调用这些方法的应用服务或 HTTP 接口。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface LabScenarioMapper {
    /** 人工使 Lease 过期，用于验证 Epoch 接管。 */
    @Update("""
        UPDATE shard_lease
        SET lease_until=now() - interval '1 second'
        WHERE shard_id=#{shardId}
        """)
    int injectExpiredLease(@Param("shardId") int shardId);

    /** 绕过账本修改余额，用于验证余额—账本对账。 */
    @Update("""
        UPDATE account
        SET balance=balance+#{delta}, updated_at=now()
        WHERE account_id=#{accountId}
        """)
    int injectBalanceCorruption(@Param("accountId") UUID accountId,
                                @Param("delta") BigDecimal delta);

    /** 只污染成交派生投影，不修改权威成交。 */
    @Update("""
        UPDATE trade_projection
        SET quantity=quantity+1, updated_at=now()
        WHERE trade_id=#{tradeId} AND status='ACTIVE'
        """)
    int injectProjectionMismatch(@Param("tradeId") UUID tradeId);

    /** 统计交易对权威成交数量。 */
    @Select("SELECT COUNT(*) FROM trade_execution WHERE symbol=#{symbol}")
    long countTrades(@Param("symbol") String symbol);

    /** 统计交易对唯一成交序号数量。 */
    @Select("""
        SELECT COUNT(DISTINCT trade_sequence)
        FROM trade_execution WHERE symbol=#{symbol}
        """)
    long countDistinctTradeSequences(@Param("symbol") String symbol);

    /** 统计不满足数量守恒的订单。 */
    @Select("""
        SELECT COUNT(*) FROM matching_order
        WHERE symbol=#{symbol}
          AND original_quantity<>executed_quantity+remaining_quantity
        """)
    long countBrokenOrders(@Param("symbol") String symbol);

    /** 统计交易对仍处于开放状态的订单。 */
    @Select("""
        SELECT COUNT(*) FROM matching_order
        WHERE symbol=#{symbol} AND status IN ('OPEN', 'PARTIALLY_FILLED')
        """)
    long countOpenOrders(@Param("symbol") String symbol);

    /** 统计载荷包含指定交易对的成交 Outbox 事件。 */
    @Select("""
        SELECT COUNT(*) FROM outbox_event
        WHERE event_type='MATCHING_TRADE_EXECUTED' AND payload LIKE #{payloadPattern}
        """)
    long countTradeOutboxEvents(@Param("payloadPattern") String payloadPattern);

    /** 统计指定前缀的账本事务。 */
    @Select("""
        SELECT COUNT(*) FROM ledger_transaction
        WHERE business_key LIKE #{businessKeyPattern}
        """)
    long countLedgerTransactions(@Param("businessKeyPattern") String businessKeyPattern);

    /** 统计指定前缀且成功终结的结算单。 */
    @Select("""
        SELECT COUNT(*) FROM settlement_order
        WHERE business_key LIKE #{businessKeyPattern} AND status='SUCCESS'
        """)
    long countSuccessfulSettlements(@Param("businessKeyPattern") String businessKeyPattern);

    /** 读取权威成交的紧凑聚合快照。 */
    @Select("""
        SELECT COUNT(*) AS "tradeCount",
               COALESCE(SUM(trade_sequence), 0) AS "sequenceSum",
               COALESCE(SUM(quantity), 0) AS "quantitySum",
               COALESCE(SUM(quote_amount), 0) AS "quoteSum"
        FROM trade_execution WHERE symbol=#{symbol}
        """)
    TruthRow summarizeTruth(@Param("symbol") String symbol);

    /** 统计三个场景账户的余额—账本不一致项。 */
    @Select("""
        SELECT COUNT(*) FROM (
            SELECT a.account_id
            FROM account a
            LEFT JOIN ledger_entry e ON e.account_id=a.account_id
            WHERE a.account_id IN (#{payer}, #{payee}, #{fee})
            GROUP BY a.account_id, a.opening_balance, a.balance
            HAVING a.balance<>a.opening_balance + COALESCE(SUM(
                CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0)
        ) mismatches
        """)
    long countLedgerMismatches(@Param("payer") UUID payer,
                               @Param("payee") UUID payee,
                               @Param("fee") UUID fee);

    /** 权威成交聚合快照。 */
    record TruthRow(long tradeCount, long sequenceSum,
                    BigDecimal quantitySum, BigDecimal quoteSum) {
    }
}
