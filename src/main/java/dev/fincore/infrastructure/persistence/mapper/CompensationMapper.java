package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 反向补偿单 MyBatis Mapper。
 *
 * <p><strong>解决的问题：</strong>以原业务键唯一约束创建补偿，并锁定原成功结算生成可审计的反向
 * 流程，阻止重复补偿。</p>
 *
 * <p><strong>CPU 与锁说明：</strong>幂等判断由 ON CONFLICT 在数据库内完成；只锁目标原结算行，账户
 * 锁序由应用服务统一处理，减少无关事务互相等待。</p>
 *
 * <p><strong>正确性边界：</strong>不提供修改原始结算或历史账本的语句；PROCESSING 到终态使用条件
 * 更新，必须与反向分录、余额和 Outbox 同事务提交。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface CompensationMapper {
    /**
     * 依据原业务键幂等创建补偿单。
     *
     * @param compensationId 补偿记录编号
     * @param originalBusinessKey 原业务幂等键
     * @param compensationBusinessKey 补偿业务幂等键
     * @param reason 可审计的处理原因
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO compensation_order(
            compensation_id, original_business_key, compensation_business_key, status, reason)
        VALUES (#{compensationId}, #{originalBusinessKey}, #{compensationBusinessKey},
                'PROCESSING', #{reason})
        ON CONFLICT (original_business_key) DO NOTHING
        """)
    int insert(@Param("compensationId") UUID compensationId,
               @Param("originalBusinessKey") String originalBusinessKey,
               @Param("compensationBusinessKey") String compensationBusinessKey,
               @Param("reason") String reason);

    /**
     * 锁定原始结算单，保证反向分录基于稳定的成功快照。
     *
     * @param businessKey 业务幂等键
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT business_key AS "businessKey", payer_account_id AS payer,
               payee_account_id AS payee, fee_account_id AS "feeAccount",
               asset, amount, fee, status
        FROM settlement_order
        WHERE business_key=#{businessKey}
        FOR UPDATE
        """)
    OriginalSettlementRow lockOriginal(@Param("businessKey") String businessKey);

    /**
     * 把余额不足的补偿单标记为失败。
     *
     * @param compensationId 补偿记录编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE compensation_order
        SET status='FAILED', updated_at=now()
        WHERE compensation_id=#{compensationId} AND status='PROCESSING'
        """)
    int markFailed(@Param("compensationId") UUID compensationId);

    /**
     * 在反向分录和余额更新完成后把补偿单迁移到成功终态。
     *
     * @param compensationId 补偿记录编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE compensation_order
        SET status='SUCCESS', updated_at=now()
        WHERE compensation_id=#{compensationId} AND status='PROCESSING'
        """)
    int markSuccess(@Param("compensationId") UUID compensationId);

    /**
     * 按原业务键查询已存在的补偿结果。
     *
     * @param originalBusinessKey 原业务幂等键
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT compensation_id AS "compensationId",
               original_business_key AS "originalBusinessKey", status
        FROM compensation_order
        WHERE original_business_key=#{originalBusinessKey}
        """)
    CompensationRow findByOriginalBusinessKey(
        @Param("originalBusinessKey") String originalBusinessKey);

    /** 原始结算的补偿所需快照。 */
    record OriginalSettlementRow(String businessKey, UUID payer, UUID payee, UUID feeAccount,
                                 String asset, BigDecimal amount, BigDecimal fee, String status) {
    }

    /** 已存在补偿单快照。 */
    record CompensationRow(UUID compensationId, String originalBusinessKey, String status) {
    }
}
