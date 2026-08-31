package dev.fincore.infrastructure.persistence.mapper;

import dev.fincore.domain.TradeSyncCommand;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 成交投影同步、对账快照和投影修复 MyBatis Mapper。
 *
 * <p>权威表 {@code trade_execution} 在本 Mapper 中永远只读；修复语句只能重建或隔离
 * {@code trade_projection}，从结构上阻止修复流程篡改成交事实。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-01
 */
public interface TradeReliabilityMapper {
    /** 幂等占用成交同步事件号。 */
    @Insert("""
        INSERT INTO trade_sync_inbox(event_id, trade_id, payload_hash, status)
        VALUES (#{eventId}, #{tradeId}, #{payloadHash}, 'RECEIVED')
        ON CONFLICT(event_id) DO NOTHING
        """)
    int insertInbox(@Param("eventId") UUID eventId,
                    @Param("tradeId") UUID tradeId,
                    @Param("payloadHash") String payloadHash);

    /** 查询事件 Inbox 指纹以核验重复消息。 */
    @Select("""
        SELECT trade_id AS "tradeId", payload_hash AS "payloadHash", status
        FROM trade_sync_inbox WHERE event_id=#{eventId}
        """)
    InboxRow findInbox(@Param("eventId") UUID eventId);

    /** 幂等插入成交查询投影。 */
    @Insert("""
        INSERT INTO trade_projection(
            trade_id, symbol, maker_order_id, taker_order_id, price, quantity,
            quote_amount, trade_sequence, source_event_id, status)
        VALUES (
            #{command.tradeId}, #{command.symbol}, #{command.makerOrderId},
            #{command.takerOrderId}, #{command.price}, #{command.quantity},
            #{command.quoteAmount}, #{command.tradeSequence}, #{command.eventId}, 'ACTIVE')
        ON CONFLICT(trade_id) DO NOTHING
        """)
    int insertProjection(@Param("command") TradeSyncCommand command);

    /** 把首次接收的 Inbox 事件迁移到已处理状态。 */
    @Update("""
        UPDATE trade_sync_inbox
        SET status='PROCESSED', processed_at=now()
        WHERE event_id=#{eventId} AND status='RECEIVED'
        """)
    int markInboxProcessed(@Param("eventId") UUID eventId);

    /** 获取指定业务键的 PostgreSQL 事务锁。 */
    @Select("SELECT pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))")
    Object lock(@Param("lockKey") String lockKey);

    /** 创建 RUNNING 对账批次。 */
    @Insert("""
        INSERT INTO trade_reconciliation_run(run_id, symbol, status)
        VALUES (#{runId}, #{symbol}, 'RUNNING')
        """)
    int insertRun(@Param("runId") UUID runId, @Param("symbol") String symbol);

    /** 统计权威成交数量。 */
    @Select("SELECT COUNT(*) FROM trade_execution WHERE symbol=#{symbol}")
    long countSource(@Param("symbol") String symbol);

    /** 统计活动成交投影数量。 */
    @Select("""
        SELECT COUNT(*) FROM trade_projection
        WHERE symbol=#{symbol} AND status='ACTIVE'
        """)
    long countActiveProjection(@Param("symbol") String symbol);

    /** 使用全外连接发现漏数、错值和幽灵成交。 */
    @Select("""
        WITH expected AS (
            SELECT trade_id, symbol, maker_order_id, taker_order_id, price,
                   quantity, quote_amount, trade_sequence,
                   jsonb_build_object(
                       'symbol', symbol, 'makerOrderId', maker_order_id,
                       'takerOrderId', taker_order_id, 'price', price,
                       'quantity', quantity, 'quoteAmount', quote_amount,
                       'sequence', trade_sequence)::text AS payload
            FROM trade_execution WHERE symbol=#{symbol}
        ), actual AS (
            SELECT trade_id, symbol, maker_order_id, taker_order_id, price,
                   quantity, quote_amount, trade_sequence,
                   jsonb_build_object(
                       'symbol', symbol, 'makerOrderId', maker_order_id,
                       'takerOrderId', taker_order_id, 'price', price,
                       'quantity', quantity, 'quoteAmount', quote_amount,
                       'sequence', trade_sequence)::text AS payload
            FROM trade_projection WHERE symbol=#{symbol} AND status='ACTIVE'
        )
        SELECT COALESCE(e.trade_id, a.trade_id) AS "tradeId",
               CASE
                 WHEN e.trade_id IS NULL THEN 'EXTRA'
                 WHEN a.trade_id IS NULL THEN 'MISSING'
                 ELSE 'MISMATCH'
               END AS type,
               e.payload AS "expectedPayload", a.payload AS "actualPayload"
        FROM expected e
        FULL OUTER JOIN actual a ON e.trade_id=a.trade_id
        WHERE e.trade_id IS NULL OR a.trade_id IS NULL
           OR e.symbol IS DISTINCT FROM a.symbol
           OR e.maker_order_id IS DISTINCT FROM a.maker_order_id
           OR e.taker_order_id IS DISTINCT FROM a.taker_order_id
           OR e.price IS DISTINCT FROM a.price
           OR e.quantity IS DISTINCT FROM a.quantity
           OR e.quote_amount IS DISTINCT FROM a.quote_amount
           OR e.trade_sequence IS DISTINCT FROM a.trade_sequence
        ORDER BY COALESCE(e.trade_sequence, a.trade_sequence),
                 COALESCE(e.trade_id, a.trade_id)
        """)
    List<DetectedDifferenceRow> findDifferences(@Param("symbol") String symbol);

    /** 冻结单条对账差异。 */
    @Insert("""
        INSERT INTO trade_reconciliation_difference(
            difference_id, run_id, trade_id, difference_type,
            expected_payload, actual_payload)
        VALUES (#{differenceId}, #{runId}, #{item.tradeId}, #{item.type},
                #{item.expectedPayload}, #{item.actualPayload})
        """)
    int insertDifference(@Param("differenceId") UUID differenceId,
                         @Param("runId") UUID runId,
                         @Param("item") DetectedDifferenceRow item);

    /** 结束对账批次并保存统计快照。 */
    @Update("""
        UPDATE trade_reconciliation_run
        SET status=#{status}, source_count=#{sourceCount},
            projection_count=#{projectionCount}, missing_count=#{missingCount},
            mismatch_count=#{mismatchCount}, extra_count=#{extraCount}, completed_at=now()
        WHERE run_id=#{runId} AND status='RUNNING'
        """)
    int completeRun(@Param("runId") UUID runId,
                    @Param("status") String status,
                    @Param("sourceCount") long sourceCount,
                    @Param("projectionCount") long projectionCount,
                    @Param("missingCount") int missingCount,
                    @Param("mismatchCount") int mismatchCount,
                    @Param("extraCount") int extraCount);

    /** 锁定待修复对账批次。 */
    @Select("""
        SELECT symbol, status
        FROM trade_reconciliation_run
        WHERE run_id=#{runId}
        FOR UPDATE
        """)
    RepairableRunRow lockRun(@Param("runId") UUID runId);

    /** 查询修复幂等键对应的已有任务。 */
    @Select("""
        SELECT repair_id AS "repairId", run_id AS "runId",
               idempotency_key AS "idempotencyKey", status,
               repaired_count AS "repairedCount",
               quarantined_count AS "quarantinedCount"
        FROM trade_projection_repair
        WHERE idempotency_key=#{idempotencyKey}
        """)
    RepairRow findRepair(@Param("idempotencyKey") String idempotencyKey);

    /** 创建 PROCESSING 修复任务。 */
    @Insert("""
        INSERT INTO trade_projection_repair(repair_id, run_id, idempotency_key, status)
        VALUES (#{repairId}, #{runId}, #{idempotencyKey}, 'PROCESSING')
        """)
    int insertRepair(@Param("repairId") UUID repairId,
                     @Param("runId") UUID runId,
                     @Param("idempotencyKey") String idempotencyKey);

    /** 锁定批次中的全部开放差异，幽灵成交优先处理。 */
    @Select("""
        SELECT difference_id AS "differenceId", trade_id AS "tradeId",
               difference_type AS type, expected_payload AS "expectedPayload",
               actual_payload AS "actualPayload", status
        FROM trade_reconciliation_difference
        WHERE run_id=#{runId} AND status='OPEN'
        ORDER BY CASE WHEN difference_type='EXTRA' THEN 0 ELSE 1 END, difference_id
        FOR UPDATE
        """)
    List<DifferenceRow> lockOpenDifferences(@Param("runId") UUID runId);

    /** 判断权威成交事实是否存在。 */
    @Select("SELECT COUNT(*) FROM trade_execution WHERE trade_id=#{tradeId}")
    int countSourceByTradeId(@Param("tradeId") UUID tradeId);

    /** 隔离没有权威事实对应的活动幽灵投影。 */
    @Update("""
        UPDATE trade_projection projection
        SET status='QUARANTINED', version=version+1, updated_at=now()
        WHERE projection.trade_id=#{tradeId} AND projection.status='ACTIVE'
          AND NOT EXISTS (
              SELECT 1 FROM trade_execution authoritative
              WHERE authoritative.trade_id=projection.trade_id)
        """)
    int quarantineGhost(@Param("tradeId") UUID tradeId);

    /** 从权威成交事实完整重建缺失或错值投影。 */
    @Insert("""
        INSERT INTO trade_projection(
            trade_id, symbol, maker_order_id, taker_order_id, price,
            quantity, quote_amount, trade_sequence, source_event_id,
            status, version, synced_at, updated_at)
        SELECT trade_id, symbol, maker_order_id, taker_order_id, price,
               quantity, quote_amount, trade_sequence, NULL,
               'ACTIVE', 1, now(), now()
        FROM trade_execution WHERE trade_id=#{tradeId}
        ON CONFLICT(trade_id) DO UPDATE SET
            symbol=EXCLUDED.symbol,
            maker_order_id=EXCLUDED.maker_order_id,
            taker_order_id=EXCLUDED.taker_order_id,
            price=EXCLUDED.price,
            quantity=EXCLUDED.quantity,
            quote_amount=EXCLUDED.quote_amount,
            trade_sequence=EXCLUDED.trade_sequence,
            status='ACTIVE',
            version=trade_projection.version+1,
            updated_at=now()
        """)
    int rebuildProjection(@Param("tradeId") UUID tradeId);

    /** 把已经处理的差异迁移为 REPAIRED。 */
    @Update("""
        UPDATE trade_reconciliation_difference
        SET status='REPAIRED', repaired_at=now()
        WHERE difference_id=#{differenceId} AND status='OPEN'
        """)
    int markDifferenceRepaired(@Param("differenceId") UUID differenceId);

    /** 保存修复任务的成功统计。 */
    @Update("""
        UPDATE trade_projection_repair
        SET status='SUCCESS', repaired_count=#{repairedCount},
            quarantined_count=#{quarantinedCount}, detail=#{detail}, completed_at=now()
        WHERE repair_id=#{repairId} AND status='PROCESSING'
        """)
    int completeRepair(@Param("repairId") UUID repairId,
                       @Param("repairedCount") int repairedCount,
                       @Param("quarantinedCount") int quarantinedCount,
                       @Param("detail") String detail);

    /** 查询对账批次汇总。 */
    @Select("""
        SELECT run_id AS "runId", symbol, status, source_count AS "sourceCount",
               projection_count AS "projectionCount", missing_count AS "missingCount",
               mismatch_count AS "mismatchCount", extra_count AS "extraCount",
               completed_at AS "completedAt"
        FROM trade_reconciliation_run
        WHERE run_id=#{runId}
        """)
    ReconciliationSummaryRow findRun(@Param("runId") UUID runId);

    /** 查询对账批次已经冻结的全部差异。 */
    @Select("""
        SELECT difference_id AS "differenceId", trade_id AS "tradeId",
               difference_type AS type, expected_payload AS "expectedPayload",
               actual_payload AS "actualPayload", status
        FROM trade_reconciliation_difference
        WHERE run_id=#{runId}
        ORDER BY difference_type, trade_id
        """)
    List<DifferenceRow> findRunDifferences(@Param("runId") UUID runId);

    /** 按成交号查询现有投影的全部不可变字段。 */
    @Select("""
        SELECT trade_id AS "tradeId", symbol, maker_order_id AS "makerOrderId",
               taker_order_id AS "takerOrderId", price, quantity,
               quote_amount AS "quoteAmount", trade_sequence AS sequence, status
        FROM trade_projection
        WHERE trade_id=#{tradeId}
        """)
    ProjectionRow findProjection(@Param("tradeId") UUID tradeId);

    /** Inbox 指纹核验快照。 */
    record InboxRow(UUID tradeId, String payloadHash, String status) {
    }

    /** 已存在成交投影的不可变字段快照。 */
    record ProjectionRow(UUID tradeId, String symbol, UUID makerOrderId, UUID takerOrderId,
                         BigDecimal price, BigDecimal quantity, BigDecimal quoteAmount,
                         long sequence, String status) {
    }

    /** SQL 新发现、尚未持久化的差异。 */
    record DetectedDifferenceRow(UUID tradeId, String type,
                                 String expectedPayload, String actualPayload) {
    }

    /** 已冻结对账差异快照。 */
    record DifferenceRow(UUID differenceId, UUID tradeId, String type,
                         String expectedPayload, String actualPayload, String status) {
    }

    /** 待修复对账批次状态。 */
    record RepairableRunRow(String symbol, String status) {
    }

    /** 已存在修复任务快照。 */
    record RepairRow(UUID repairId, UUID runId, String idempotencyKey, String status,
                     int repairedCount, int quarantinedCount) {
    }

    /** 对账批次汇总快照。 */
    record ReconciliationSummaryRow(UUID runId, String symbol, String status,
                                    long sourceCount, long projectionCount,
                                    int missingCount, int mismatchCount, int extraCount,
                                    Instant completedAt) {
    }
}
