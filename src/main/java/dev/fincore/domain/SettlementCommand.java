package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

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
    public SettlementCommand {
        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId is required");
        if (businessKey == null || businessKey.isBlank()) throw new IllegalArgumentException("businessKey is required");
        if (payerAccountId == null || payeeAccountId == null || feeAccountId == null) throw new IllegalArgumentException("accounts are required");
        if (payerAccountId.equals(payeeAccountId) || payerAccountId.equals(feeAccountId) || payeeAccountId.equals(feeAccountId)) {
            throw new IllegalArgumentException("payer, payee and fee accounts must be distinct");
        }
        if (asset == null || asset.isBlank()) throw new IllegalArgumentException("asset is required");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (fee == null || fee.signum() < 0) throw new IllegalArgumentException("fee must be non-negative");
    }
}
