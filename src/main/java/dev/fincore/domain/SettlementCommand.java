package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 资金结算命令。
 *
 * <p>{@code messageId} 用于消息级幂等，{@code businessKey} 用于业务级幂等。付款、
 * 收款和手续费账户必须彼此不同，避免同一账户在一笔事务中承担冲突角色。</p>
 *
 * @param messageId 消息唯一编号
 * @param businessKey 结算业务唯一键
 * @param payerAccountId 付款账户
 * @param payeeAccountId 收款账户
 * @param feeAccountId 手续费账户
 * @param asset 结算资产
 * @param amount 支付给收款方的正数金额
 * @param fee 非负手续费
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record SettlementCommand(
    String messageId,
    String businessKey,
    UUID payerAccountId,
    UUID payeeAccountId,
    UUID feeAccountId,
    String asset,
    BigDecimal amount,
    BigDecimal fee
) {
    /** 校验结算参与方、资产和金额的基本领域约束。 */
    public SettlementCommand {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId is required");
        }
        if (businessKey == null || businessKey.isBlank()) {
            throw new IllegalArgumentException("businessKey is required");
        }
        if (payerAccountId == null || payeeAccountId == null || feeAccountId == null) {
            throw new IllegalArgumentException("accounts are required");
        }
        if (payerAccountId.equals(payeeAccountId) || payerAccountId.equals(feeAccountId) || payeeAccountId.equals(feeAccountId)) {
            throw new IllegalArgumentException("payer, payee and fee accounts must be distinct");
        }
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (fee == null || fee.signum() < 0) {
            throw new IllegalArgumentException("fee must be non-negative");
        }
    }
}
