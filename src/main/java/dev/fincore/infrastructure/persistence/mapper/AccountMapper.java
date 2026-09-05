package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 账户和账本摘要 MyBatis Mapper。
 *
 * <p><strong>解决的问题：</strong>持久化账户期初事实，并从不可变分录独立重算期望余额。</p>
 *
 * <p><strong>CPU 与 I/O 说明：</strong>账本 SUM/GROUP BY 在数据库侧一次完成，避免向 JVM 传输全部
 * 分录；该查询应运行在诊断或对账路径，并依赖账户/分录索引，不应放入撮合热循环。</p>
 *
 * <p><strong>正确性边界：</strong>这里只定义单条数据库操作，不承载账户创建事务或余额一致性裁决；
 * 差异只能触发留证和对账流程，不能在查询中自动改余额。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface AccountMapper {
    /**
     * 新建账户并同时保存期初余额。
     *
     * @param accountId 账户编号
     * @param ownerId 账户所有者编号
     * @param asset 资产代码
     * @param accountType 账户类型
     * @param openingBalance 非负期初余额
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
        VALUES (#{accountId}, #{ownerId}, #{asset}, #{accountType}, #{openingBalance}, #{openingBalance})
        """)
    int insert(@Param("accountId") UUID accountId,
               @Param("ownerId") String ownerId,
               @Param("asset") String asset,
               @Param("accountType") String accountType,
               @Param("openingBalance") BigDecimal openingBalance);

    /**
     * 按主键查询账户快照。
     *
     * @param accountId 账户编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id AS "accountId", owner_id AS "ownerId", asset,
               account_type AS "accountType", opening_balance AS "openingBalance",
               balance, version
        FROM account
        WHERE account_id=#{accountId}
        """)
    AccountRow findById(@Param("accountId") UUID accountId);

    /**
     * 根据不可变账本重算账户期望余额。
     *
     * @param accountId 账户编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
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
