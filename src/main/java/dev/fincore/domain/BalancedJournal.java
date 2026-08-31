package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 复式记账平衡校验器。
 *
 * <p>该类型只负责验证一组账本分录是否满足“借方合计等于贷方合计”，不负责持久化。
 * 结算与补偿服务必须在写入账本和更新余额之前调用本校验，避免不平衡分录进入数据库。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public final class BalancedJournal {
    /** 工具类不允许实例化。 */
    private BalancedJournal() {
    }

    /**
     * 校验账本分录是否借贷平衡。
     *
     * @param postings 同一账本事务中的全部有效分录
     * @throws IllegalArgumentException 借方金额合计与贷方金额合计不一致时抛出
     */
    public static void requireBalanced(List<LedgerPosting> postings) {
        BigDecimal debits = postings.stream()
            .filter(p -> p.direction() == LedgerDirection.DEBIT)
            .map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = postings.stream()
            .filter(p -> p.direction() == LedgerDirection.CREDIT)
            .map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("unbalanced journal: debit=" + debits + ", credit=" + credits);
        }
    }
}
