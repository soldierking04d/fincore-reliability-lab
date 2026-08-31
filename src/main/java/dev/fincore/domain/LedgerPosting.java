package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 一条不可变的账本记账分录。
 *
 * @param accountId 入账账户
 * @param direction 借贷方向
 * @param amount 正数金额；方向由 {@code direction} 单独表达
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record LedgerPosting(UUID accountId, LedgerDirection direction, BigDecimal amount) {
    /** 校验账本分录必须具备账户、方向和正数金额。 */
    public LedgerPosting {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        if (direction == null) {
            throw new IllegalArgumentException("direction is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }
}
