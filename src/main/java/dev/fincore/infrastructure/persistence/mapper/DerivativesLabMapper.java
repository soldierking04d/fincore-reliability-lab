package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 合约实验持久化接口：资金事实只追加，账户状态变更由同一账户锁串行化。
 * 服务必须先按 UuidOrder 锁全部账户，再访问仓位和业务键，不能反向获取账户锁。
 *
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
public interface DerivativesLabMapper {
    /** 创建模拟期初余额，重复账号不能覆盖既有资金。 */
    @Insert("INSERT INTO lab_derivative_account(account_id,opening_wallet,wallet) VALUES (#{id},#{wallet},#{wallet})")
    int openAccount(@Param("id") UUID id, @Param("wallet") BigDecimal wallet);

    /** 锁住账户权威状态，锁持续到事务提交或回滚。 */
    @Select("SELECT * FROM lab_derivative_account WHERE account_id=#{id} FOR UPDATE")
    AccountRow lockAccount(@Param("id") UUID id);

    /** 读取数据库实时时间，而不是事务开始时间，避免等锁后仍使用旧时间。 */
    @Select("SELECT clock_timestamp()")
    Instant now();

    /** 更新模拟资金和预算；每次资金相关变更都使风险快照失效。 */
    @Update("""
        UPDATE lab_derivative_account SET wallet=wallet+#{cash}, reserved=reserved+#{reserve},
            version=version+1 WHERE account_id=#{id}
        """)
    int changeAccount(@Param("id") UUID id, @Param("cash") BigDecimal cash,
                      @Param("reserve") BigDecimal reserve);

    /** 显式模拟 Worker 接管；Epoch 独立于账户版本单调递增。 */
    @Update("UPDATE lab_derivative_account SET epoch=epoch+1 WHERE account_id=#{id}")
    int incrementEpoch(@Param("id") UUID id);

    /** 数据面最终 CAS：应用层检查不是唯一防线。 */
    @Update("""
        UPDATE lab_derivative_account SET state='LIQUIDATING', version=version+1
        WHERE account_id=#{id} AND version=#{version} AND epoch=#{epoch} AND state='ACTIVE'
        """)
    int enterLiquidation(@Param("id") UUID id, @Param("version") long version, @Param("epoch") long epoch);

    /** 只供实验准备阶段插入一个净仓位，禁止重复覆盖。 */
    @Insert("""
        INSERT INTO lab_derivative_position(account_id,symbol,quantity,entry_price)
        VALUES (#{id},#{symbol},#{quantity},#{price})
        """)
    int seedPosition(@Param("id") UUID id, @Param("symbol") String symbol,
                     @Param("quantity") BigDecimal quantity, @Param("price") BigDecimal price);

    /** 必须在持有账户锁时读取。 */
    @Select("SELECT * FROM lab_derivative_position WHERE account_id=#{id}")
    PositionRow position(@Param("id") UUID id);

    /** 数量只由执行时的只减仓计算结果更新。 */
    @Update("UPDATE lab_derivative_position SET quantity=#{quantity} WHERE account_id=#{id}")
    int updatePosition(@Param("id") UUID id, @Param("quantity") BigDecimal quantity);

    /** 业务键查询与插入处于同一个账户锁事务内。 */
    @Select("""
        SELECT * FROM lab_derivative_operation
        WHERE account_id=#{id} AND kind=#{kind} AND business_key=#{key}
        """)
    OperationRow operation(@Param("id") UUID id, @Param("kind") String kind, @Param("key") String key);

    /** 唯一约束是幂等的最终防线；决定包含拒绝结果，历史结果不变。 */
    @Insert("""
        INSERT INTO lab_derivative_operation(operation_id,account_id,kind,business_key,request,status,effect)
        VALUES (#{operationId,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
                #{accountId,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
                #{kind},#{businessKey},#{request},#{status},#{effect})
        """)
    int insertOperation(OperationRow operation);

    /** 每次非零现金变化同时记录相反的两腿，模拟结算池不是保险基金。 */
    @Insert("""
        INSERT INTO lab_derivative_ledger(operation_id,account_id,delta)
        VALUES (#{operation},#{account},#{delta}), (#{operation},#{pool},-#{delta})
        """)
    int postPair(@Param("operation") UUID operation, @Param("account") UUID account,
                 @Param("pool") UUID pool, @Param("delta") BigDecimal delta);

    /** 读取不可变的资金费周期快照。 */
    @Select("""
        SELECT * FROM lab_derivative_funding
        WHERE account_id=#{account} AND symbol=#{symbol} AND cycle_at=#{cycle}
        """)
    FundingRow funding(@Param("account") UUID account, @Param("symbol") String symbol,
                       @Param("cycle") Instant cycle);

    /** 固化周期仓位、费率、标记价与账户版本，不依赖消费时的实时仓位。 */
    @Insert("""
        INSERT INTO lab_derivative_funding(account_id,symbol,cycle_at,quantity,mark_price,rate,account_version)
        VALUES (#{account},#{symbol},#{cycle},#{quantity},#{mark},#{rate},#{version})
        """)
    int captureFunding(@Param("account") UUID account, @Param("symbol") String symbol,
                        @Param("cycle") Instant cycle, @Param("quantity") BigDecimal quantity,
                        @Param("mark") BigDecimal mark, @Param("rate") BigDecimal rate,
                        @Param("version") long version);

    /** 同一消息编号只能关联到同一业务操作。 */
    @Insert("""
        INSERT INTO lab_derivative_inbox(message_id,operation_id) VALUES (#{message},#{operation})
        ON CONFLICT (message_id) DO NOTHING
        """)
    int insertInbox(@Param("message") UUID message, @Param("operation") UUID operation);

    /** 消息重放时核对业务操作，不能仅因消息存在就返回成功。 */
    @Select("SELECT operation_id FROM lab_derivative_inbox WHERE message_id=#{message}")
    InboxRow inboxOperation(@Param("message") UUID message);

    /** 模拟账户权威快照；wallet 是已实现现金，不是盯市权益。 */
    record AccountRow(UUID accountId, BigDecimal openingWallet, BigDecimal wallet,
                      BigDecimal reserved, long version, long epoch, String state) { }

    /** 数量正数表示多仓，负数表示空仓，零表示已平仓。 */
    record PositionRow(UUID accountId, String symbol, BigDecimal quantity, BigDecimal entryPrice) { }

    /** 不可变决定，同时充当领域审计与业务幂等记录。 */
    record OperationRow(UUID operationId, UUID accountId, String kind, String businessKey,
                        String request, String status, BigDecimal effect) { }

    /** 结算周期快照；本实验不实现历史事件流的全局时间水位。 */
    record FundingRow(UUID accountId, String symbol, Instant cycleAt, BigDecimal quantity,
                      BigDecimal markPrice, BigDecimal rate, long accountVersion) { }

    /** 使用记录包装 UUID，避免 MyBatis 将 UUID 当作需要反射构造的普通对象。 */
    record InboxRow(UUID operationId) { }
}
