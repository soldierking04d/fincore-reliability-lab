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
 * <p>补偿只引用并锁定原始结算，不提供修改原始结算或历史账本的语句。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface CompensationMapper {
    /** 依据原业务键幂等创建补偿单。 */
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

    /** 锁定原始结算单，保证反向分录基于稳定的成功快照。 */
    @Select("""
        SELECT business_key AS "businessKey", payer_account_id AS payer,
               payee_account_id AS payee, fee_account_id AS "feeAccount",
               asset, amount, fee, status
        FROM settlement_order
        WHERE business_key=#{businessKey}
        FOR UPDATE
        """)
    OriginalSettlementRow lockOriginal(@Param("businessKey") String businessKey);

    /** 把余额不足的补偿单标记为失败。 */
    @Update("""
        UPDATE compensation_order
        SET status='FAILED', updated_at=now()
        WHERE compensation_id=#{compensationId} AND status='PROCESSING'
        """)
    int markFailed(@Param("compensationId") UUID compensationId);

    /** 在反向分录和余额更新完成后把补偿单迁移到成功终态。 */
    @Update("""
        UPDATE compensation_order
        SET status='SUCCESS', updated_at=now()
        WHERE compensation_id=#{compensationId} AND status='PROCESSING'
        """)
    int markSuccess(@Param("compensationId") UUID compensationId);

    /** 按原业务键查询已存在的补偿结果。 */
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
