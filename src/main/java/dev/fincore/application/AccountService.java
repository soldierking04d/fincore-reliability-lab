package dev.fincore.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public AccountView create(String ownerId, String asset, String type, BigDecimal openingBalance) {
        if (openingBalance == null || openingBalance.signum() < 0) {
            throw new IllegalArgumentException("opening balance must be non-negative");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, ownerId, asset, type, openingBalance, openingBalance);
        return get(id);
    }

    public AccountView get(UUID id) {
        return jdbc.queryForObject("""
            SELECT account_id, owner_id, asset, account_type, opening_balance, balance, version
            FROM account WHERE account_id = ?
            """, (rs, row) -> new AccountView(
                rs.getObject("account_id", UUID.class), rs.getString("owner_id"), rs.getString("asset"),
                rs.getString("account_type"), rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("balance"), rs.getLong("version")), id);
    }

    public Map<String, Object> ledgerSummary(UUID id) {
        return jdbc.queryForMap("""
            SELECT a.account_id, a.opening_balance, a.balance,
                   COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0) AS ledger_delta,
                   a.opening_balance + COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0) AS expected_balance
            FROM account a LEFT JOIN ledger_entry e ON e.account_id = a.account_id
            WHERE a.account_id = ?
            GROUP BY a.account_id, a.opening_balance, a.balance
            """, id);
    }

    public record AccountView(UUID accountId, String ownerId, String asset, String accountType,
                              BigDecimal openingBalance, BigDecimal balance, long version) {}
}

