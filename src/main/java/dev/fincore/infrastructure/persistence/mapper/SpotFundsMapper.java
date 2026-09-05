package dev.fincore.infrastructure.persistence.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 现货预占、在途、交割指令与不可变分桶审计的持久化接口。
 *
 * <p><strong>解决的问题：</strong>把可用、预占、在途、已交割和已释放资金明确分桶，防止订单进入
 * 撮合后被重复花费，并让撤单与成交竞态可以审计。</p>
 *
 * <p><strong>CPU、锁与 I/O 优化：</strong>分桶变更用数据库增量 UPDATE 完成，不在 Java 中做整行
 * 读改写；调用方先按 UUID 全序锁账户，再处理订单预占，使锁等待可预测并减少死锁重试。</p>
 *
 * <p><strong>正确性边界：</strong>所有改动由应用事务编排，总余额与分桶由数据库非负/覆盖约束兜底，
 * 不能靠 Java 快照放行。旧订单不能无预占混入受控资金订单簿。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
public interface SpotFundsMapper {
    /**
     * 查询市场是否已采用资金预占规则。
     *
     * @param symbol 交易对或合约代码
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Select("SELECT count(*) FROM spot_funded_market WHERE symbol=#{symbol}")
    int fundedMarket(@Param("symbol") String symbol);

    /**
     * 不允许把历史未预占订单混入受控资金订单簿。
     *
     * @param symbol 交易对或合约代码
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Select("SELECT count(*) FROM matching_order WHERE symbol=#{symbol}")
    int existingOrders(@Param("symbol") String symbol);

    /**
     * 在交易对锁内首次建立资金市场。
     *
     * @param symbol 交易对或合约代码
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("INSERT INTO spot_funded_market(symbol) VALUES(#{symbol})")
    int insertMarket(@Param("symbol") String symbol);

    /**
     * 自动建立零余额收款账户，不制造任何期初资金。
     *
     * @param id 目标记录编号
     * @param owner owner 对应的持久化查询或写入参数
     * @param asset 资产代码
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO account(account_id, owner_id, asset, account_type)
        VALUES(#{id}, #{owner}, #{asset}, 'TRADING')
        ON CONFLICT(owner_id, asset, account_type) DO NOTHING
        """)
    int ensureReceiver(@Param("id") UUID id, @Param("owner") String owner, @Param("asset") String asset);

    /**
     * 查询用户资产账户。
     *
     * @param owner owner 对应的持久化查询或写入参数
     * @param asset 资产代码
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id FROM account
        WHERE owner_id=#{owner} AND asset=#{asset} AND account_type='TRADING'
        """)
    AccountId accountId(@Param("owner") String owner, @Param("asset") String asset);

    /**
     * 查询包含资金分桶的账户快照。
     *
     * @param id 目标记录编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id, asset, balance, reserved_balance, pending_debit, financial_hold,
               balance-reserved_balance-pending_debit AS available
        FROM account WHERE account_id=#{id}
        """)
    FundsRow funds(@Param("id") UUID id);

    /**
     * 锁定账户；调用方必须按 UUID 全序锁定本事务涉及的所有账户。
     *
     * @param id 目标记录编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT account_id, asset, balance, reserved_balance, pending_debit, financial_hold,
               balance-reserved_balance-pending_debit AS available
        FROM account WHERE account_id=#{id} FOR UPDATE
        """)
    FundsRow lockFunds(@Param("id") UUID id);

    /**
     * 用增量原子修改分桶；数据库覆盖约束拦截透支，冻结账户不能新增资金操作。
     *
     * @param id 目标记录编号
     * @param balance balance 对应的持久化查询或写入参数
     * @param held held 对应的持久化查询或写入参数
     * @param pending pending 对应的持久化查询或写入参数
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE account SET balance=balance+#{balance}, reserved_balance=reserved_balance+#{held},
          pending_debit=pending_debit+#{pending}, version=version+1, updated_at=now()
        WHERE account_id=#{id} AND financial_hold=false
        """)
    int changeFunds(@Param("id") UUID id, @Param("balance") BigDecimal balance,
                    @Param("held") BigDecimal held, @Param("pending") BigDecimal pending);

    /**
     * 建立一张订单唯一的预占。
     *
     * @param orderId 订单编号
     * @param payer payer 对应的持久化查询或写入参数
     * @param receiver receiver 对应的持久化查询或写入参数
     * @param amount 固定精度金额
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO spot_order_reservation(order_id,payer_account_id,receiver_account_id,initial_amount,held)
        VALUES(#{orderId},#{payer},#{receiver},#{amount},#{amount})
        """)
    int reserve(@Param("orderId") UUID orderId, @Param("payer") UUID payer,
                @Param("receiver") UUID receiver, @Param("amount") BigDecimal amount);

    /**
     * 在账户锁之后读取最新预占；不反向先锁订单预占再锁账户。
     *
     * @param id 目标记录编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("SELECT * FROM spot_order_reservation WHERE order_id=#{id}")
    ReservationRow reservation(@Param("id") UUID id);

    /**
     * 预占增量更新，initial=held+pending+settled+released 由数据库检查。
     *
     * @param id 目标记录编号
     * @param held held 对应的持久化查询或写入参数
     * @param pending pending 对应的持久化查询或写入参数
     * @param settled settled 对应的持久化查询或写入参数
     * @param released released 对应的持久化查询或写入参数
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE spot_order_reservation SET held=held+#{held}, pending=pending+#{pending},
          settled=settled+#{settled}, released=released+#{released}, version=version+1
        WHERE order_id=#{id}
        """)
    int changeReservation(@Param("id") UUID id, @Param("held") BigDecimal held,
                          @Param("pending") BigDecimal pending, @Param("settled") BigDecimal settled,
                          @Param("released") BigDecimal released);

    /**
     * 追加分桶变更；同一业务事件在同一账户只允许记一次。
     *
     * @param key 业务幂等键
     * @param id 目标记录编号
     * @param available available 对应的持久化查询或写入参数
     * @param held held 对应的持久化查询或写入参数
     * @param pending pending 对应的持久化查询或写入参数
     * @param balance balance 对应的持久化查询或写入参数
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO spot_fund_journal(event_key,account_id,available_delta,reserved_delta,pending_delta,balance_delta)
        VALUES(#{key},#{id},#{available},#{held},#{pending},#{balance})
        """)
    int journal(@Param("key") String key, @Param("id") UUID id,
                @Param("available") BigDecimal available, @Param("held") BigDecimal held,
                @Param("pending") BigDecimal pending, @Param("balance") BigDecimal balance);

    /**
     * 从权威成交及订单预占生成不可变的双资产交割事实。
     *
     * @param row 已完成资金捕获、包含交易双方账户和金额的交割事实
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO spot_delivery(trade_id,buy_order_id,sell_order_id,buyer_quote_id,buyer_base_id,
          seller_base_id,seller_quote_id,base_asset,quote_asset,quantity,quote_amount)
        VALUES(#{tradeId,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
          #{buyOrderId,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
          #{sellOrderId,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
          #{buyerQuoteId,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
          #{buyerBaseId,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
          #{sellerBaseId,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
          #{sellerQuoteId,jdbcType=OTHER,typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler},
          #{baseAsset},#{quoteAsset},#{quantity},#{quoteAmount})
        """)
    int insertDelivery(DeliveryRow row);

    /**
     * 查询交割状态和不可变业务参数。
     *
     * @param id 目标记录编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("SELECT * FROM spot_delivery WHERE trade_id=#{id}")
    DeliveryRow delivery(@Param("id") UUID id);

    /**
     * 同一成交的重复 Worker 通过交割行锁串行，随后统一按 UUID 锁账户。
     *
     * @param id 目标记录编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("SELECT * FROM spot_delivery WHERE trade_id=#{id} FOR UPDATE")
    DeliveryRow lockDelivery(@Param("id") UUID id);

    /**
     * 两种资产都记账成功后才能迁移到唯一终态。
     *
     * @param id 目标记录编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("""
        UPDATE spot_delivery SET status='SETTLED',settled_at=now()
        WHERE trade_id=#{id} AND status='PENDING'
        """)
    int complete(@Param("id") UUID id);

    /**
     * 持久化消息级幂等，业务级幂等由 trade_id 与 SETTLED 终态保障。
     *
     * @param messageId messageId 对应的持久化查询或写入参数
     * @param tradeId 成交编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO spot_delivery_inbox(message_id,trade_id) VALUES(#{messageId},#{tradeId})
        ON CONFLICT(message_id) DO NOTHING
        """)
    int inbox(@Param("messageId") String messageId, @Param("tradeId") UUID tradeId);

    /**
     * 核对重复消息是否偷换成交编号。
     *
     * @param messageId messageId 对应的持久化查询或写入参数
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("SELECT trade_id AS account_id FROM spot_delivery_inbox WHERE message_id=#{messageId}")
    AccountId inboxTrade(@Param("messageId") String messageId);

    /**
     * 从不可变账本、分桶审计和预占明细分别重算三类余额。
     *
     * @param id 目标记录编号
     * @return 匹配的持久化快照；不存在时返回 null
     */
    @Select("""
        SELECT a.account_id,
          a.opening_balance+COALESCE((SELECT sum(CASE WHEN e.direction='CREDIT' THEN e.amount ELSE -e.amount END)
            FROM ledger_entry e WHERE e.account_id=a.account_id),0) AS expected_balance,
          COALESCE((SELECT sum(j.reserved_delta) FROM spot_fund_journal j WHERE j.account_id=a.account_id),0) AS journal_held,
          COALESCE((SELECT sum(j.pending_delta) FROM spot_fund_journal j WHERE j.account_id=a.account_id),0) AS journal_pending,
          COALESCE((SELECT sum(r.held) FROM spot_order_reservation r WHERE r.payer_account_id=a.account_id),0) AS order_held,
          COALESCE((SELECT sum(r.pending) FROM spot_order_reservation r WHERE r.payer_account_id=a.account_id),0) AS order_pending
        FROM account a WHERE a.account_id=#{id}
        """)
    RecomputedRow recompute(@Param("id") UUID id);

    /**
     * 差异默认冻结，不自动改余额或覆盖历史账本。
     *
     * @param id 目标记录编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Update("UPDATE account SET financial_hold=true,version=version+1 WHERE account_id=#{id}")
    int freeze(@Param("id") UUID id);

    /**
     * 对账差异追加审核工单，同一未结问题不重复创建。
     *
     * @param issueId issueId 对应的持久化查询或写入参数
     * @param id 目标记录编号
     * @return 受影响行数；1 表示写入或条件更新成功，0 表示幂等冲突或并发前置条件未满足
     */
    @Insert("""
        INSERT INTO reconciliation_issue(issue_id,account_id,issue_type,risk_level,status,details)
        VALUES(#{issueId},#{id},'SPOT_FUNDS_DIFF','HIGH','OPEN','现货总余额、预占或在途不一致；冻结待复核')
        ON CONFLICT(account_id,issue_type) WHERE status='OPEN' DO NOTHING
        """)
    int issue(@Param("issueId") UUID issueId, @Param("id") UUID id);

    /** 单列 UUID 显式映射。 */
    record AccountId(UUID accountId) { }
    /** 账户资金快照。 */
    record FundsRow(UUID accountId, String asset, BigDecimal balance, BigDecimal reservedBalance,
                    BigDecimal pendingDebit, boolean financialHold, BigDecimal available) { }
    /** 一张订单的资金去向守恒。 */
    record ReservationRow(UUID orderId, UUID payerAccountId, UUID receiverAccountId,
                          BigDecimal initialAmount, BigDecimal held, BigDecimal pending,
                          BigDecimal settled, BigDecimal released, long version) { }
    /** 成交驱动的交割指令；消息中不接受可篡改的金额。 */
    record DeliveryRow(UUID tradeId, UUID buyOrderId, UUID sellOrderId, UUID buyerQuoteId,
                       UUID buyerBaseId, UUID sellerBaseId, UUID sellerQuoteId,
                       String baseAsset, String quoteAsset, BigDecimal quantity, BigDecimal quoteAmount,
                       String status, Instant createdAt, Instant settledAt) { }
    /** 三路独立重算结果。 */
    record RecomputedRow(UUID accountId, BigDecimal expectedBalance, BigDecimal journalHeld,
                         BigDecimal journalPending, BigDecimal orderHeld, BigDecimal orderPending) { }
}
