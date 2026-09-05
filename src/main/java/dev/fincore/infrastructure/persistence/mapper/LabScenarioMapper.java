package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * {@code lab} Profile 故障注入与事实断言 Mapper。
 *
 * <p><strong>解决的问题：</strong>主动制造 Lease 过期、余额错账和投影错误，再用数据库断言证明系统
 * 能发现、隔离或修复，而不是只展示理想路径。</p>
 *
 * <p><strong>CPU 与容量边界：</strong>统计和扫描 SQL 只供受控实验使用，不属于线上热路径，也不能
 * 用其耗时推导生产 QPS；大数据量演练需要独立影子库、分页和资源隔离。</p>
 *
 * <p><strong>正确性边界：</strong>所有破坏不变量的方法都以 {@code inject} 命名，生产 Profile 不会
 * 暴露调用它们的服务或 HTTP 接口。不得把本接口注入生产组件。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface LabScenarioMapper {
    /**
     * 人工使 Lease 过期，用于验证 Epoch 接管。
     *
     * @param shardId 分片编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE shard_lease
        SET lease_until=now() - interval '1 second'
        WHERE shard_id=#{shardId}
        """)
    int injectExpiredLease(@Param("shardId") int shardId);

    /**
     * 绕过账本修改余额，用于验证余额—账本对账。
     *
     * @param accountId 账户编号
     * @param delta 需要记账的固定精度变动额
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE account
        SET balance=balance+#{delta}, updated_at=now()
        WHERE account_id=#{accountId}
        """)
    int injectBalanceCorruption(@Param("accountId") UUID accountId,
                                @Param("delta") BigDecimal delta);

    /**
     * 只污染成交派生投影，不修改权威成交。
     *
     * @param tradeId 成交编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE trade_projection
        SET quantity=quantity+1, updated_at=now()
        WHERE trade_id=#{tradeId} AND status='ACTIVE'
        """)
    int injectProjectionMismatch(@Param("tradeId") UUID tradeId);

    /**
     * 统计交易对权威成交数量。
     *
     * @param symbol 交易对或合约代码
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("SELECT COUNT(*) FROM trade_execution WHERE symbol=#{symbol}")
    long countTrades(@Param("symbol") String symbol);

    /**
     * 统计交易对唯一成交序号数量。
     *
     * @param symbol 交易对或合约代码
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("""
        SELECT COUNT(DISTINCT trade_sequence)
        FROM trade_execution WHERE symbol=#{symbol}
        """)
    long countDistinctTradeSequences(@Param("symbol") String symbol);

    /**
     * 统计不满足数量守恒的订单。
     *
     * @param symbol 交易对或合约代码
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("""
        SELECT COUNT(*) FROM matching_order
        WHERE symbol=#{symbol}
          AND original_quantity<>executed_quantity+remaining_quantity
        """)
    long countBrokenOrders(@Param("symbol") String symbol);

    /**
     * 统计交易对仍处于开放状态的订单。
     *
     * @param symbol 交易对或合约代码
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("""
        SELECT COUNT(*) FROM matching_order
        WHERE symbol=#{symbol} AND status IN ('OPEN', 'PARTIALLY_FILLED')
        """)
    long countOpenOrders(@Param("symbol") String symbol);

    /**
     * 统计载荷包含指定交易对的成交 Outbox 事件。
     *
     * @param payloadPattern 用于限定实验事件的载荷匹配模式
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("""
        SELECT COUNT(*) FROM outbox_event
        WHERE event_type='MATCHING_TRADE_EXECUTED' AND payload LIKE #{payloadPattern}
        """)
    long countTradeOutboxEvents(@Param("payloadPattern") String payloadPattern);

    /**
     * 统计指定前缀的账本事务。
     *
     * @param businessKeyPattern 用于限定实验流水的业务键匹配模式
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("""
        SELECT COUNT(*) FROM ledger_transaction
        WHERE business_key LIKE #{businessKeyPattern}
        """)
    long countLedgerTransactions(@Param("businessKeyPattern") String businessKeyPattern);

    /**
     * 统计指定前缀且成功终结的结算单。
     *
     * @param businessKeyPattern 用于限定实验流水的业务键匹配模式
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
    @Select("""
        SELECT COUNT(*) FROM settlement_order
        WHERE business_key LIKE #{businessKeyPattern} AND status='SUCCESS'
        """)
    long countSuccessfulSettlements(@Param("businessKeyPattern") String businessKeyPattern);

    /**
     * 读取权威成交的紧凑聚合快照。
     *
     * @param symbol 交易对或合约代码
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT COUNT(*) AS "tradeCount",
               COALESCE(SUM(trade_sequence), 0) AS "sequenceSum",
               COALESCE(SUM(quantity), 0) AS "quantitySum",
               COALESCE(SUM(quote_amount), 0) AS "quoteSum"
        FROM trade_execution WHERE symbol=#{symbol}
        """)
    TruthRow summarizeTruth(@Param("symbol") String symbol);

    /**
     * 统计三个场景账户的余额—账本不一致项。
     *
     * @param payer payer 对应的持久化查询或写入参数
     * @param payee payee 对应的持久化查询或写入参数
     * @param fee fee 对应的持久化查询或写入参数
     * @return 查询或原子分配得到的数值；包装类型结果不存在时可能为 null
     */
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
