package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 手续费分片账户和归集任务 Mapper。
 *
 * <p><strong>解决的问题：</strong>维护手续费分片元数据和幂等归集任务，把高频写入分散、低频归集
 * 的生命周期持久化。</p>
 *
 * <p><strong>CPU 与 I/O 说明：</strong>分片和财资账户使用 ON CONFLICT 幂等创建；一次查询返回目标
 * 资产的有限分片集合，资金移动交给 LedgerMapper 批量分录，避免逐条远程往返。</p>
 *
 * <p><strong>正确性边界：</strong>本 Mapper 不直接移动资金；账户资金移动复用 {@link LedgerMapper}
 * 并与归集状态处于同一事务。归集业务键不能删除或复用。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface FeeAggregationMapper {
    /**
     * 幂等创建手续费分片账户。
     *
     * @param accountId 账户编号
     * @param ownerId 账户所有者编号
     * @param asset 资产代码
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
        VALUES (#{accountId}, #{ownerId}, #{asset}, 'SYSTEM_FEE_SHARD', 0, 0)
        ON CONFLICT DO NOTHING
        """)
    int insertShardAccount(@Param("accountId") UUID accountId,
                           @Param("ownerId") String ownerId,
                           @Param("asset") String asset);

    /**
     * 查询指定资产的全部手续费分片账户。
     *
     * @param asset 资产代码
     * @return 满足查询条件的只读结果列表；没有记录时返回空列表
     */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset, balance
        FROM account
        WHERE asset=#{asset} AND account_type='SYSTEM_FEE_SHARD'
        ORDER BY owner_id
        """)
    List<FeeAccountRow> findShards(@Param("asset") String asset);

    /**
     * 按所有者和资产查找目标手续费分片。
     *
     * @param ownerId 账户所有者编号
     * @param asset 资产代码
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset, balance
        FROM account
        WHERE owner_id=#{ownerId} AND asset=#{asset}
          AND account_type='SYSTEM_FEE_SHARD'
        """)
    FeeAccountRow findShard(@Param("ownerId") String ownerId, @Param("asset") String asset);

    /**
     * 幂等创建财资归集账户。
     *
     * @param accountId 账户编号
     * @param asset 资产代码
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
        VALUES (#{accountId}, 'SYSTEM_FEE_TREASURY', #{asset}, 'SYSTEM_FEE_TREASURY', 0, 0)
        ON CONFLICT DO NOTHING
        """)
    int insertTreasury(@Param("accountId") UUID accountId, @Param("asset") String asset);

    /**
     * 查询指定资产的财资归集账户。
     *
     * @param asset 资产代码
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset, balance
        FROM account
        WHERE owner_id='SYSTEM_FEE_TREASURY' AND asset=#{asset}
          AND account_type='SYSTEM_FEE_TREASURY'
        """)
    FeeAccountRow findTreasury(@Param("asset") String asset);

    /**
     * 依据归集业务键幂等创建归集任务。
     *
     * @param aggregationKey 归集业务幂等键
     * @param asset 资产代码
     * @param treasuryAccountId 归集总账户编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO fee_aggregation(aggregation_key, asset, treasury_account_id, status)
        VALUES (#{aggregationKey}, #{asset}, #{treasuryAccountId}, 'PROCESSING')
        ON CONFLICT (aggregation_key) DO NOTHING
        """)
    int insertAggregation(@Param("aggregationKey") String aggregationKey,
                          @Param("asset") String asset,
                          @Param("treasuryAccountId") UUID treasuryAccountId);

    /**
     * 把零金额归集任务结束为成功。
     *
     * @param aggregationKey 归集业务幂等键
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE fee_aggregation
        SET status='SUCCESS', total_amount=0, updated_at=now()
        WHERE aggregation_key=#{aggregationKey} AND status='PROCESSING'
        """)
    int markEmptySuccess(@Param("aggregationKey") String aggregationKey);

    /**
     * 把资金已移动的归集任务结束为成功。
     *
     * @param aggregationKey 归集业务幂等键
     * @param totalAmount 本次归集总金额
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE fee_aggregation
        SET status='SUCCESS', total_amount=#{totalAmount}, version=version+1, updated_at=now()
        WHERE aggregation_key=#{aggregationKey} AND status='PROCESSING'
        """)
    int markSuccess(@Param("aggregationKey") String aggregationKey,
                    @Param("totalAmount") BigDecimal totalAmount);

    /**
     * 查询已有归集任务以支持幂等重放。
     *
     * @param aggregationKey 归集业务幂等键
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT aggregation_key AS "aggregationKey", status,
               COALESCE(total_amount, 0) AS "totalAmount"
        FROM fee_aggregation
        WHERE aggregation_key=#{aggregationKey}
        """)
    AggregationRow findAggregation(@Param("aggregationKey") String aggregationKey);

    /** 手续费账户持久化快照。 */
    record FeeAccountRow(UUID accountId, String ownerId, String asset, BigDecimal balance) {
    }

    /** 手续费归集任务快照。 */
    record AggregationRow(String aggregationKey, String status, BigDecimal totalAmount) {
    }
}
