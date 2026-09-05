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
 * <p><strong>解决的问题：</strong>独立比较“期初余额+不可变分录净额”和账户当前余额，持久化高风险
 * 差异证据。</p>
 *
 * <p><strong>CPU 与 I/O 说明：</strong>聚合在数据库执行，避免将全量分录传入 JVM；这是后台对账扫描，
 * 必须通过调度频率、分页/分区和数据库资源预算与实时交易隔离。</p>
 *
 * <p><strong>正确性边界：</strong>查询只计算并登记差异，不提供自动修改账户余额的 SQL；重复问题由
 * 唯一约束收敛，发现异常后先冻结和留证。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface ReconciliationMapper {
    /**
     * 查找全部“期初余额 + 分录净额”与账户余额不一致的账户。
     * @return 满足查询条件的只读结果列表；没有记录时返回空列表
     */
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

    /**
     * 幂等登记一个开放的高风险差异。
     *
     * @param issueId issueId 对应的持久化查询或写入参数
     * @param accountId 账户编号
     * @param expected expected 对应的持久化查询或写入参数
     * @param actual actual 对应的持久化查询或写入参数
     * @param details details 对应的持久化查询或写入参数
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
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
