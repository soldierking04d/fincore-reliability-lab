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
 * <p><strong>解决的问题：</strong>Kafka 至少一次投递会产生重复消息，结算重试也可能重放状态迁移；
 * Inbox、业务唯一键和原状态 CAS 共同保证一次业务结果。</p>
 *
 * <p><strong>CPU 与 I/O 优化：</strong>使用 {@code ON CONFLICT DO NOTHING} 和条件 UPDATE 在数据库
 * 内一次判断幂等/状态，避免应用先查再写的额外往返与竞态；审计记录保持追加写，利于顺序 I/O。</p>
 *
 * <p><strong>正确性边界：</strong>唯一约束和原状态条件是最后防线，但仍必须与余额、分录、围栏和
 * Inbox 完成标记处于同一显式事务。SUCCESS 是终态，补偿必须另建反向账务流程。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface SettlementMapper {
    /**
     * 幂等登记结算命令消息。
     *
     * @param messageId messageId 对应的持久化查询或写入参数
     * @param payload 序列化后的事件载荷
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO inbox_message(message_id, message_type, payload)
        VALUES (#{messageId}, 'SETTLEMENT_COMMAND', #{payload})
        ON CONFLICT (message_id) DO NOTHING
        """)
    int insertInbox(@Param("messageId") String messageId, @Param("payload") String payload);

    /**
     * 幂等创建结算单。
     *
     * @param command 已校验的业务命令
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
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

    /**
     * 使用期望原状态执行结算状态 CAS。
     *
     * @param businessKey 业务幂等键
     * @param fromStatus 期望原状态
     * @param toStatus 目标状态
     * @param reason 可审计的处理原因
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE settlement_order
        SET status=#{toStatus}, failure_reason=#{reason}, version=version+1, updated_at=now()
        WHERE business_key=#{businessKey} AND status=#{fromStatus}
        """)
    int transition(@Param("businessKey") String businessKey,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus,
                   @Param("reason") String reason);

    /**
     * 追加一条不可变状态审计记录。
     *
     * @param auditId 审计记录编号
     * @param businessKey 业务幂等键
     * @param fromStatus 期望原状态
     * @param toStatus 目标状态
     * @param reason 可审计的处理原因
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
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

    /**
     * 按业务键查询当前结算结果。
     *
     * @param businessKey 业务幂等键
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT business_key AS "businessKey", status,
               COALESCE(failure_reason, '') AS detail
        FROM settlement_order
        WHERE business_key=#{businessKey}
        """)
    SettlementResultRow findByBusinessKey(@Param("businessKey") String businessKey);

    /**
     * 按消息编号查询当前结算结果。
     *
     * @param messageId messageId 对应的持久化查询或写入参数
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT business_key AS "businessKey", status,
               COALESCE(failure_reason, '') AS detail
        FROM settlement_order
        WHERE message_id=#{messageId}
        """)
    SettlementResultRow findByMessageId(@Param("messageId") String messageId);

    /**
     * 标记 Inbox 消息处理完成。
     *
     * @param messageId messageId 对应的持久化查询或写入参数
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE inbox_message SET processed_at=now() WHERE message_id=#{messageId}
        """)
    int markInboxProcessed(@Param("messageId") String messageId);

    /** 结算结果持久化快照。 */
    record SettlementResultRow(String businessKey, String status, String detail) {
    }
}
