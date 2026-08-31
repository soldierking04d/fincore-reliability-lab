package dev.fincore.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户余额与不可变账本的全量对账服务。
 *
 * <p>服务根据“期初余额 + 贷方 - 借方”计算期望余额，并把不一致项登记为高风险问题。
 * 默认只发现和冻结差异，不自动修改资金，避免错误修复掩盖真实账务问题。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class ReconciliationService {
    /** 账户、账本和对账问题数据库访问模板。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 数据库访问模板 */
    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 对全部账户执行余额—账本对账。
     *
     * @return 差异数量和明细；无差异时列表为空
     */
    @Transactional
    public ReconciliationReport reconcileAll() {
        List<AccountDifference> differences = jdbc.query("""
            SELECT a.account_id, a.opening_balance +
                   COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0) expected,
                   a.balance actual
            FROM account a LEFT JOIN ledger_entry e ON e.account_id=a.account_id
            GROUP BY a.account_id, a.opening_balance, a.balance
            HAVING a.opening_balance +
                   COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0) <> a.balance
            """, (rs, row) -> new AccountDifference(rs.getObject("account_id", UUID.class),
                rs.getBigDecimal("expected"), rs.getBigDecimal("actual")));
        for (AccountDifference diff : differences) {
            jdbc.update("""
                INSERT INTO reconciliation_issue(issue_id, account_id, issue_type, expected_amount,
                                                 actual_amount, risk_level, details)
                VALUES (?, ?, 'BALANCE_LEDGER_MISMATCH', ?, ?, 'HIGH', ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), diff.accountId(), diff.expected(), diff.actual(),
                "Automatic repair is forbidden; manual review required");
        }
        return new ReconciliationReport(differences.size(), differences);
    }

    /**
     * 单账户对账差异。
     *
     * @param accountId 账户编号
     * @param expected 账本重算余额
     * @param actual 账户表实际余额
     */
    public record AccountDifference(UUID accountId, BigDecimal expected, BigDecimal actual) {
    }

    /**
     * 余额—账本对账报告。
     *
     * @param differenceCount 差异账户数
     * @param differences 差异明细
     */
    public record ReconciliationReport(int differenceCount, List<AccountDifference> differences) {
    }
}
