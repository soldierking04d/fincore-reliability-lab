package dev.fincore.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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

    public record AccountDifference(UUID accountId, BigDecimal expected, BigDecimal actual) {}
    public record ReconciliationReport(int differenceCount, List<AccountDifference> differences) {}
}
