package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 不可变账本和账户余额原子操作 Mapper。
 *
 * <p><strong>解决的问题：</strong>把账户行锁、条件扣款、不可变分录和余额更新收敛到可审计 SQL，
 * 由数据库而不是 JVM 快照裁决余额是否足够。</p>
 *
 * <p><strong>CPU 与 I/O 优化：</strong>整组分录使用一次批量 INSERT，减少 JDBC 往返和 SQL 解析；
 * 余额下限及 CAS 条件直接下推数据库，避免读回 Java 后竞争失败再重算。调用方按 UUID 全序加锁，
 * 降低死锁检测和事务重试带来的数据库 CPU 浪费。</p>
 *
 * <p><strong>正确性边界：</strong>Mapper 不提供更新或删除历史分录的方法。所有资金服务必须先写
 * 平衡分录，再在同一 Spring 事务中修改余额；批量写只是性能手段，不改变原子性要求。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface LedgerMapper {
    /**
     * 锁定账户并返回资产与余额快照。
     *
     * @param accountId 账户编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id AS "accountId", asset, account_type AS "accountType", balance,
               balance-reserved_balance-pending_debit AS "availableBalance"
        FROM account
        WHERE account_id=#{accountId}
        FOR UPDATE
        """)
    LockedAccountRow lockAccount(@Param("accountId") UUID accountId);

    /**
     * 不加锁读取账户类型与余额，用于进入资金锁序之前的参数校验。
     *
     * @param accountId 账户编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id AS "accountId", asset, account_type AS "accountType", balance,
               balance-reserved_balance-pending_debit AS "availableBalance"
        FROM account
        WHERE account_id=#{accountId}
        """)
    LockedAccountRow findAccount(@Param("accountId") UUID accountId);

    /**
     * 查询指定资产的手续费分片账户编号。
     *
     * @param asset 资产代码
     * @return 满足查询条件的只读结果列表；没有记录时返回空列表
     */
    @Select("""
        SELECT account_id
        FROM account
        WHERE asset=#{asset} AND account_type='SYSTEM_FEE_SHARD'
        """)
    List<AccountIdRow> findFeeShardIds(@Param("asset") String asset);

    /**
     * 追加账本事务头。
     *
     * @param transactionId transactionId 对应的持久化查询或写入参数
     * @param businessKey 业务幂等键
     * @param transactionType transactionType 对应的持久化查询或写入参数
     * @param asset 资产代码
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO ledger_transaction(transaction_id, business_key, transaction_type, asset)
        VALUES (#{transactionId}, #{businessKey}, #{transactionType}, #{asset})
        """)
    int insertTransaction(@Param("transactionId") UUID transactionId,
                          @Param("businessKey") String businessKey,
                          @Param("transactionType") String transactionType,
                          @Param("asset") String asset);

    /**
     * 在一个 SQL 往返中追加整组不可变借贷分录。
     *
     * @param transactionId transactionId 对应的持久化查询或写入参数
     * @param entries entries 对应的持久化查询或写入参数
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        <script>
        INSERT INTO ledger_entry(entry_id, transaction_id, account_id, direction, amount)
        VALUES
        <foreach collection="entries" item="entry" separator=",">
          (#{entry.entryId,javaType=java.util.UUID,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
           #{transactionId,javaType=java.util.UUID,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
           #{entry.accountId,javaType=java.util.UUID,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
           #{entry.direction}, #{entry.amount})
        </foreach>
        </script>
        """)
    int insertEntries(@Param("transactionId") UUID transactionId,
                      @Param("entries") List<LedgerEntryRow> entries);

    /**
     * 在余额下限保护下原子扣款。
     *
     * @param accountId 账户编号
     * @param amount 固定精度金额
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE account
        SET balance=balance-#{amount}, version=version+1, updated_at=now()
        WHERE account_id=#{accountId} AND balance-reserved_balance-pending_debit>=#{amount}
          AND financial_hold=false
        """)
    int debit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

    /**
     * 按锁定快照执行 CAS 全额扣款。
     *
     * @param accountId 账户编号
     * @param amount 固定精度金额
     * @param expectedBalance expectedBalance 对应的持久化查询或写入参数
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE account
        SET balance=balance-#{amount}, version=version+1, updated_at=now()
        WHERE account_id=#{accountId} AND balance=#{expectedBalance}
          AND balance-reserved_balance-pending_debit>=#{amount} AND financial_hold=false
        """)
    int debitExact(@Param("accountId") UUID accountId,
                   @Param("amount") BigDecimal amount,
                   @Param("expectedBalance") BigDecimal expectedBalance);

    /**
     * 原子增加账户余额。
     *
     * @param accountId 账户编号
     * @param amount 固定精度金额
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE account
        SET balance=balance+#{amount}, version=version+1, updated_at=now()
        WHERE account_id=#{accountId}
        """)
    int credit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

    /** 资金事务使用的已锁定账户快照。 */
    record LockedAccountRow(UUID accountId, String asset, String accountType, BigDecimal balance,
                            BigDecimal availableBalance) {
    }

    /** 单列账户编号查询的显式结果对象，避免把 UUID 当成构造器映射类型。 */
    record AccountIdRow(UUID accountId) {
    }

    /** 待批量写入的不可变账本分录。 */
    record LedgerEntryRow(UUID entryId, UUID accountId, String direction, BigDecimal amount) {
    }
}
