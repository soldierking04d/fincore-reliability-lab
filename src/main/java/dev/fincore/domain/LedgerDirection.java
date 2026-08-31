package dev.fincore.domain;

/**
 * 账本分录方向。
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public enum LedgerDirection {
    /** 借方分录，表示从账户扣减资产。 */
    DEBIT,

    /** 贷方分录，表示向账户增加资产。 */
    CREDIT
}
