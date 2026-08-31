package dev.fincore.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户生命周期与账本摘要应用服务。
 *
 * <p>账户余额使用 {@link BigDecimal} 存储。账本摘要通过期初余额和不可变分录重新计算
 * 期望余额，用于对照账户表中的当前余额。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class AccountService {
    /** 账户和账本数据库访问模板。 */
    private final JdbcTemplate jdbc;

    /** @param jdbc 数据库访问模板 */
    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 创建账户并保存期初余额。
     *
     * @param ownerId 账户所有者
     * @param asset 资产代码
     * @param type 账户类型
     * @param openingBalance 非负期初余额
     * @return 新账户快照
     */
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

    /**
     * 查询账户快照。
     *
     * @param id 账户编号
     * @return 账户当前状态
     */
    public AccountView get(UUID id) {
        return jdbc.queryForObject("""
            SELECT account_id, owner_id, asset, account_type, opening_balance, balance, version
            FROM account WHERE account_id = ?
            """, (rs, row) -> new AccountView(
                rs.getObject("account_id", UUID.class), rs.getString("owner_id"), rs.getString("asset"),
                rs.getString("account_type"), rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("balance"), rs.getLong("version")), id);
    }

    /**
     * 从期初余额和账本分录重新计算账户期望余额。
     *
     * @param id 账户编号
     * @return 当前余额、账本净额和期望余额
     */
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

    /**
     * 账户只读快照。
     *
     * @param accountId 账户编号
     * @param ownerId 所有者
     * @param asset 资产代码
     * @param accountType 账户类型
     * @param openingBalance 期初余额
     * @param balance 当前余额
     * @param version 乐观锁版本
     */
    public record AccountView(UUID accountId, String ownerId, String asset, String accountType,
                              BigDecimal openingBalance, BigDecimal balance, long version) {
    }
}
