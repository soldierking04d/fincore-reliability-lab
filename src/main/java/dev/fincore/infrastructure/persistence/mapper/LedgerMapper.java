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
 * <p>Mapper 不提供更新或删除历史分录的方法。所有资金服务必须先写平衡分录，再在同一 Spring
 * 事务中修改余额。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface LedgerMapper {
    /** 锁定账户并返回资产与余额快照。 */
    @Select("""
        SELECT account_id AS "accountId", asset, account_type AS "accountType", balance
        FROM account
        WHERE account_id=#{accountId}
        FOR UPDATE
        """)
    LockedAccountRow lockAccount(@Param("accountId") UUID accountId);

    /** 不加锁读取账户类型与余额，用于进入资金锁序之前的参数校验。 */
    @Select("""
        SELECT account_id AS "accountId", asset, account_type AS "accountType", balance
        FROM account
        WHERE account_id=#{accountId}
        """)
    LockedAccountRow findAccount(@Param("accountId") UUID accountId);

    /** 查询指定资产的手续费分片账户编号。 */
    @Select("""
        SELECT account_id
        FROM account
        WHERE asset=#{asset} AND account_type='SYSTEM_FEE_SHARD'
        """)
    List<AccountIdRow> findFeeShardIds(@Param("asset") String asset);

    /** 追加账本事务头。 */
    @Insert("""
        INSERT INTO ledger_transaction(transaction_id, business_key, transaction_type, asset)
        VALUES (#{transactionId}, #{businessKey}, #{transactionType}, #{asset})
        """)
    int insertTransaction(@Param("transactionId") UUID transactionId,
                          @Param("businessKey") String businessKey,
                          @Param("transactionType") String transactionType,
                          @Param("asset") String asset);

    /** 在一个 SQL 往返中追加整组不可变借贷分录。 */
    @Insert("""
        <script>
        INSERT INTO ledger_entry(entry_id, transaction_id, account_id, direction, amount)
        VALUES
        <foreach collection="entries" item="entry" separator=",">
          (#{entry.entryId}, #{transactionId}, #{entry.accountId}, #{entry.direction}, #{entry.amount})
        </foreach>
        </script>
        """)
    int insertEntries(@Param("transactionId") UUID transactionId,
                      @Param("entries") List<LedgerEntryRow> entries);

    /** 在余额下限保护下原子扣款。 */
    @Update("""
        UPDATE account
        SET balance=balance-#{amount}, version=version+1, updated_at=now()
        WHERE account_id=#{accountId} AND balance>=#{amount}
        """)
    int debit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

    /** 按锁定快照执行 CAS 全额扣款。 */
    @Update("""
        UPDATE account
        SET balance=balance-#{amount}, version=version+1, updated_at=now()
        WHERE account_id=#{accountId} AND balance=#{expectedBalance}
        """)
    int debitExact(@Param("accountId") UUID accountId,
                   @Param("amount") BigDecimal amount,
                   @Param("expectedBalance") BigDecimal expectedBalance);

    /** 原子增加账户余额。 */
    @Update("""
        UPDATE account
        SET balance=balance+#{amount}, version=version+1, updated_at=now()
        WHERE account_id=#{accountId}
        """)
    int credit(@Param("accountId") UUID accountId, @Param("amount") BigDecimal amount);

    /** 资金事务使用的已锁定账户快照。 */
    record LockedAccountRow(UUID accountId, String asset, String accountType, BigDecimal balance) {
    }

    /** 单列账户编号查询的显式结果对象，避免把 UUID 当成构造器映射类型。 */
    record AccountIdRow(UUID accountId) {
    }

    /** 待批量写入的不可变账本分录。 */
    record LedgerEntryRow(UUID entryId, UUID accountId, String direction, BigDecimal amount) {
    }
}
