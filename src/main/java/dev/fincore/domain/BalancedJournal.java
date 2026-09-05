package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * 复式记账平衡校验器。
 *
 * <p><strong>解决的问题：</strong>在持久化之前验证一组账本分录满足“借方合计等于贷方合计”，
 * 阻止不平衡交易进入不可变账本。</p>
 *
 * <p><strong>CPU 取舍：</strong>当前每笔交易只有固定少量分录，两次线性遍历为 O(n)，可读性和审计
 * 清晰度高于复杂聚合器。金额坚持使用 {@link BigDecimal}，不会为了减少对象运算改用浮点数；若未来
 * 处理大批量分录，应按批次限制输入，而不是牺牲金额精度。</p>
 *
 * <p><strong>正确性边界：</strong>本类型只校验金额平衡，不负责持久化、账户资产一致性或余额覆盖。
 * 结算与补偿服务必须在写入账本和更新余额之前调用，并由同一数据库事务保证原子性。</p>
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
