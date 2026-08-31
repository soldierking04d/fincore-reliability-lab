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
 * <p>账户资金移动复用 {@link LedgerMapper}；本 Mapper 仅维护分片账户元数据和归集任务状态。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface FeeAggregationMapper {
    /** 幂等创建手续费分片账户。 */
    @Insert("""
        INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
        VALUES (#{accountId}, #{ownerId}, #{asset}, 'SYSTEM_FEE_SHARD', 0, 0)
        ON CONFLICT DO NOTHING
        """)
    int insertShardAccount(@Param("accountId") UUID accountId,
                           @Param("ownerId") String ownerId,
                           @Param("asset") String asset);

    /** 查询指定资产的全部手续费分片账户。 */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset, balance
        FROM account
        WHERE asset=#{asset} AND account_type='SYSTEM_FEE_SHARD'
        ORDER BY owner_id
        """)
    List<FeeAccountRow> findShards(@Param("asset") String asset);

    /** 按所有者和资产查找目标手续费分片。 */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset, balance
        FROM account
        WHERE owner_id=#{ownerId} AND asset=#{asset}
          AND account_type='SYSTEM_FEE_SHARD'
        """)
    FeeAccountRow findShard(@Param("ownerId") String ownerId, @Param("asset") String asset);

    /** 幂等创建财资归集账户。 */
    @Insert("""
        INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
        VALUES (#{accountId}, 'SYSTEM_FEE_TREASURY', #{asset}, 'SYSTEM_FEE_TREASURY', 0, 0)
        ON CONFLICT DO NOTHING
        """)
    int insertTreasury(@Param("accountId") UUID accountId, @Param("asset") String asset);

    /** 查询指定资产的财资归集账户。 */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset, balance
        FROM account
        WHERE owner_id='SYSTEM_FEE_TREASURY' AND asset=#{asset}
          AND account_type='SYSTEM_FEE_TREASURY'
        """)
    FeeAccountRow findTreasury(@Param("asset") String asset);

    /** 依据归集业务键幂等创建归集任务。 */
    @Insert("""
        INSERT INTO fee_aggregation(aggregation_key, asset, treasury_account_id, status)
        VALUES (#{aggregationKey}, #{asset}, #{treasuryAccountId}, 'PROCESSING')
        ON CONFLICT (aggregation_key) DO NOTHING
        """)
    int insertAggregation(@Param("aggregationKey") String aggregationKey,
                          @Param("asset") String asset,
                          @Param("treasuryAccountId") UUID treasuryAccountId);

    /** 把零金额归集任务结束为成功。 */
    @Update("""
        UPDATE fee_aggregation
        SET status='SUCCESS', total_amount=0, updated_at=now()
        WHERE aggregation_key=#{aggregationKey} AND status='PROCESSING'
        """)
    int markEmptySuccess(@Param("aggregationKey") String aggregationKey);

    /** 把资金已移动的归集任务结束为成功。 */
    @Update("""
        UPDATE fee_aggregation
        SET status='SUCCESS', total_amount=#{totalAmount}, version=version+1, updated_at=now()
        WHERE aggregation_key=#{aggregationKey} AND status='PROCESSING'
        """)
    int markSuccess(@Param("aggregationKey") String aggregationKey,
                    @Param("totalAmount") BigDecimal totalAmount);

    /** 查询已有归集任务以支持幂等重放。 */
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
