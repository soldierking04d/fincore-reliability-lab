package dev.fincore.infrastructure.persistence.mapper;

import dev.fincore.domain.SettlementCommand;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 结算单、Inbox 和状态审计 Mapper。
 *
 * <p>数据库唯一约束和带原状态条件的更新语句属于金融幂等与状态机的最后防线。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface SettlementMapper {
    /** 幂等登记结算命令消息。 */
    @Insert("""
        INSERT INTO inbox_message(message_id, message_type, payload)
        VALUES (#{messageId}, 'SETTLEMENT_COMMAND', #{payload})
        ON CONFLICT (message_id) DO NOTHING
        """)
    int insertInbox(@Param("messageId") String messageId, @Param("payload") String payload);

    /** 幂等创建结算单。 */
    @Insert("""
        INSERT INTO settlement_order(
            business_key, message_id, payer_account_id, payee_account_id,
            fee_account_id, asset, amount, fee, status)
        VALUES (
            #{command.businessKey}, #{command.messageId}, #{command.payerAccountId},
            #{command.payeeAccountId}, #{command.feeAccountId}, #{command.asset},
            #{command.amount}, #{command.fee}, 'INIT')
        ON CONFLICT (business_key) DO NOTHING
        """)
    int insertOrder(@Param("command") SettlementCommand command);

    /** 使用期望原状态执行结算状态 CAS。 */
    @Update("""
        UPDATE settlement_order
        SET status=#{toStatus}, failure_reason=#{reason}, version=version+1, updated_at=now()
        WHERE business_key=#{businessKey} AND status=#{fromStatus}
        """)
    int transition(@Param("businessKey") String businessKey,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("reason") String reason);

    /** 追加一条不可变状态审计记录。 */
    @Insert("""
        INSERT INTO state_audit(
            audit_id, business_key, from_status, to_status, reason, changed_by)
        VALUES (#{auditId}, #{businessKey}, #{fromStatus}, #{toStatus}, #{reason},
                'settlement-service')
        """)
    int insertAudit(@Param("auditId") UUID auditId,
                    @Param("businessKey") String businessKey,
                    @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus,
                    @Param("reason") String reason);

    /** 按业务键查询当前结算结果。 */
    @Select("""
        SELECT business_key AS "businessKey", status,
               COALESCE(failure_reason, '') AS detail
        FROM settlement_order
        WHERE business_key=#{businessKey}
        """)
    SettlementResultRow findByBusinessKey(@Param("businessKey") String businessKey);

    /** 按消息编号查询当前结算结果。 */
    @Select("""
        SELECT business_key AS "businessKey", status,
               COALESCE(failure_reason, '') AS detail
        FROM settlement_order
        WHERE message_id=#{messageId}
        """)
    SettlementResultRow findByMessageId(@Param("messageId") String messageId);

    /** 标记 Inbox 消息处理完成。 */
    @Update("""
        UPDATE inbox_message SET processed_at=now() WHERE message_id=#{messageId}
        """)
    int markInboxProcessed(@Param("messageId") String messageId);

    /** 结算结果持久化快照。 */
    record SettlementResultRow(String businessKey, String status, String detail) {
    }
}
