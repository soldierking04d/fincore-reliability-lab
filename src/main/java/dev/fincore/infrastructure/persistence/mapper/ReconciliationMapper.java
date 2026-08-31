package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 账户余额与不可变账本对账 Mapper。
 *
 * <p>查询只计算并冻结差异，不提供自动修改账户余额的 SQL。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface ReconciliationMapper {
    /** 查找全部“期初余额 + 分录净额”与账户余额不一致的账户。 */
    @Select("""
        SELECT a.account_id AS "accountId",
               a.opening_balance +
               COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0)
                   AS expected,
               a.balance AS actual
        FROM account a
        LEFT JOIN ledger_entry e ON e.account_id=a.account_id
        GROUP BY a.account_id, a.opening_balance, a.balance
        HAVING a.opening_balance +
               COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0)
               <> a.balance
        """)
    List<AccountDifferenceRow> findBalanceDifferences();

    /** 幂等登记一个开放的高风险差异。 */
    @Insert("""
        INSERT INTO reconciliation_issue(
            issue_id, account_id, issue_type, expected_amount,
            actual_amount, risk_level, details)
        VALUES (#{issueId}, #{accountId}, 'BALANCE_LEDGER_MISMATCH',
                #{expected}, #{actual}, 'HIGH', #{details})
        ON CONFLICT DO NOTHING
        """)
    int insertIssue(@Param("issueId") UUID issueId,
                    @Param("accountId") UUID accountId,
                    @Param("expected") BigDecimal expected,
                    @Param("actual") BigDecimal actual,
                    @Param("details") String details);

    /** 单账户账本差异快照。 */
    record AccountDifferenceRow(UUID accountId, BigDecimal expected, BigDecimal actual) {
    }
}
