package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 账户和账本摘要 MyBatis Mapper。
 *
 * <p>这里只定义单条数据库操作，不承载账户创建事务或余额一致性判断。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface AccountMapper {
    /** 新建账户并同时保存期初余额。 */
    @Insert("""
        INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
        VALUES (#{accountId}, #{ownerId}, #{asset}, #{accountType}, #{openingBalance}, #{openingBalance})
        """)
    int insert(@Param("accountId") UUID accountId,
               @Param("ownerId") String ownerId,
               @Param("asset") String asset,
               @Param("accountType") String accountType,
               @Param("openingBalance") BigDecimal openingBalance);

    /** 按主键查询账户快照。 */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset,
               account_type AS "accountType", opening_balance AS "openingBalance",
               balance, version
        FROM account
        WHERE account_id=#{accountId}
        """)
    AccountRow findById(@Param("accountId") UUID accountId);

    /** 根据不可变账本重算账户期望余额。 */
    @Select("""
        SELECT a.account_id AS "accountId", a.opening_balance AS "openingBalance",
               a.balance,
               COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0)
                   AS "ledgerDelta",
               a.opening_balance +
               COALESCE(SUM(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END), 0)
                   AS "expectedBalance"
        FROM account a
        LEFT JOIN ledger_entry e ON e.account_id=a.account_id
        WHERE a.account_id=#{accountId}
        GROUP BY a.account_id, a.opening_balance, a.balance
        """)
    LedgerSummaryRow summarizeLedger(@Param("accountId") UUID accountId);

    /** 账户持久化快照。 */
    record AccountRow(UUID accountId, String ownerId, String asset, String accountType,
                      BigDecimal openingBalance, BigDecimal balance, long version) {
    }

    /** 账本重算结果。 */
    record LedgerSummaryRow(UUID accountId, BigDecimal openingBalance, BigDecimal balance,
                            BigDecimal ledgerDelta, BigDecimal expectedBalance) {
    }
}
