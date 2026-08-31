package dev.fincore.application;

import dev.fincore.domain.TradeSyncCommand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成交派生投影的同步、对账与自动修复服务。
 *
 * <p>{@code trade_execution} 是不可修改的权威成交事实，{@code trade_projection} 只是可重建的查询投影。
 * 同步入口使用事件 Inbox 与载荷指纹抵御重复和冲突重放；对账使用全外连接识别漏数、错值和幽灵成交；
 * 修复只重建或隔离投影，绝不回写权威成交、订单或资金账本。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Service
public class TradeReliabilityService {
    /** 数据库访问入口。 */
    private final JdbcTemplate jdbc;
    /** 已处理同步事件计数器。 */
    private final Counter synced;
    /** 重复同步事件计数器。 */
    private final Counter duplicateEvents;
    /** 已发现差异计数器。 */
    private final Counter differences;
    /** 已修复或隔离投影计数器。 */
    private final Counter repaired;

    /**
     * 创建成交可靠性服务并注册业务指标。
     *
     * @param jdbc 数据库访问入口
     * @param registry Micrometer 指标注册表
     */
    public TradeReliabilityService(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.synced = registry.counter("fincore.trade.sync.processed");
        this.duplicateEvents = registry.counter("fincore.trade.sync.duplicate");
        this.differences = registry.counter("fincore.trade.reconciliation.differences");
        this.repaired = registry.counter("fincore.trade.reconciliation.repaired");
    }

    /**
     * 幂等地把一条权威成交同步到查询投影。
     *
     * @param command 带唯一事件号的成交同步命令
     * @return 同步结果以及是否为重复事件
     */
    @Transactional
    public SyncOutcome apply(TradeSyncCommand command) {
        String payloadHash = fingerprint(command);
        // 先占用事件号；数据库唯一约束是跨进程幂等的最终防线。
        int insertedEvent = jdbc.update("""
            INSERT INTO trade_sync_inbox(event_id, trade_id, payload_hash, status)
            VALUES (?, ?, ?, 'RECEIVED')
            ON CONFLICT(event_id) DO NOTHING
            """, command.eventId(), command.tradeId(), payloadHash);

        InboxRecord inbox = jdbc.queryForObject("""
            SELECT trade_id, payload_hash, status
            FROM trade_sync_inbox WHERE event_id=?
            """, (rs, row) -> new InboxRecord(
                rs.getObject("trade_id", UUID.class),
                rs.getString("payload_hash"),
                rs.getString("status")), command.eventId());

        if (inbox == null || !inbox.tradeId().equals(command.tradeId())
            || !inbox.payloadHash().equals(payloadHash)) {
            throw new IllegalArgumentException(
                "同一事件号携带了冲突成交内容 / conflicting event replay");
        }
        if (insertedEvent == 0) {
            duplicateEvents.increment();
            return new SyncOutcome(command.eventId(), command.tradeId(), true, false, inbox.status());
        }

        // 成交号也具有唯一约束；冲突时必须核验不可变字段，不能静默吞掉错值。
        int projected = jdbc.update("""
            INSERT INTO trade_projection(
                trade_id, symbol, maker_order_id, taker_order_id, price, quantity,
                quote_amount, trade_sequence, source_event_id, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
            ON CONFLICT(trade_id) DO NOTHING
            """, command.tradeId(), command.symbol(), command.makerOrderId(),
            command.takerOrderId(), command.price(), command.quantity(),
            command.quoteAmount(), command.tradeSequence(), command.eventId());

        if (projected == 0) {
            requireSameProjection(command);
        }
        jdbc.update("""
            UPDATE trade_sync_inbox SET status='PROCESSED', processed_at=now()
            WHERE event_id=? AND status='RECEIVED'
            """, command.eventId());
        synced.increment();
        return new SyncOutcome(command.eventId(), command.tradeId(), false,
            projected == 1, "PROCESSED");
    }

    /**
     * 对比权威成交与活动投影，冻结一份可审计的差异快照。
     *
     * <p>使用可重复读隔离级别和交易对 advisory lock，使计数、差异明细和批次状态基于同一事实窗口。</p>
     *
     * @param rawSymbol 待对账交易对
     * @return 对账批次及全部差异
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ReconciliationReport reconcile(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        lock("trade-reconcile:" + symbol);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO trade_reconciliation_run(run_id, symbol, status)
            VALUES (?, ?, 'RUNNING')
            """, runId, symbol);

        long sourceCount = count("""
            SELECT COUNT(*) FROM trade_execution WHERE symbol=?
            """, symbol);
        long projectionCount = count("""
            SELECT COUNT(*) FROM trade_projection WHERE symbol=? AND status='ACTIVE'
            """, symbol);

        List<DetectedDifference> found = jdbc.query("""
            WITH expected AS (
                SELECT trade_id, symbol, maker_order_id, taker_order_id, price,
                       quantity, quote_amount, trade_sequence,
                       jsonb_build_object(
                           'symbol', symbol, 'makerOrderId', maker_order_id,
                           'takerOrderId', taker_order_id, 'price', price,
                           'quantity', quantity, 'quoteAmount', quote_amount,
                           'sequence', trade_sequence)::text AS payload
                FROM trade_execution WHERE symbol=?
            ), actual AS (
                SELECT trade_id, symbol, maker_order_id, taker_order_id, price,
                       quantity, quote_amount, trade_sequence,
                       jsonb_build_object(
                           'symbol', symbol, 'makerOrderId', maker_order_id,
                           'takerOrderId', taker_order_id, 'price', price,
                           'quantity', quantity, 'quoteAmount', quote_amount,
                           'sequence', trade_sequence)::text AS payload
                FROM trade_projection WHERE symbol=? AND status='ACTIVE'
            )
            SELECT COALESCE(e.trade_id, a.trade_id) AS trade_id,
                   CASE
                     WHEN e.trade_id IS NULL THEN 'EXTRA'
                     WHEN a.trade_id IS NULL THEN 'MISSING'
                     ELSE 'MISMATCH'
                   END AS difference_type,
                   e.payload AS expected_payload,
                   a.payload AS actual_payload
            FROM expected e FULL OUTER JOIN actual a ON e.trade_id=a.trade_id
            WHERE e.trade_id IS NULL OR a.trade_id IS NULL
               OR e.symbol IS DISTINCT FROM a.symbol
               OR e.maker_order_id IS DISTINCT FROM a.maker_order_id
               OR e.taker_order_id IS DISTINCT FROM a.taker_order_id
               OR e.price IS DISTINCT FROM a.price
               OR e.quantity IS DISTINCT FROM a.quantity
               OR e.quote_amount IS DISTINCT FROM a.quote_amount
               OR e.trade_sequence IS DISTINCT FROM a.trade_sequence
            ORDER BY COALESCE(e.trade_sequence, a.trade_sequence), COALESCE(e.trade_id, a.trade_id)
            """, (rs, row) -> new DetectedDifference(
                rs.getObject("trade_id", UUID.class),
                rs.getString("difference_type"),
                rs.getString("expected_payload"),
                rs.getString("actual_payload")), symbol, symbol);

        int missing = 0;
        int mismatch = 0;
        int extra = 0;
        for (DetectedDifference item : found) {
            jdbc.update("""
                INSERT INTO trade_reconciliation_difference(
                    difference_id, run_id, trade_id, difference_type,
                    expected_payload, actual_payload)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), runId, item.tradeId(), item.type(),
                item.expectedPayload(), item.actualPayload());
            if ("MISSING".equals(item.type())) {
                missing++;
            } else if ("MISMATCH".equals(item.type())) {
                mismatch++;
            } else {
                extra++;
            }
        }
        String status = found.isEmpty() ? "CLEAN" : "DIFFERENCE_FOUND";
        jdbc.update("""
            UPDATE trade_reconciliation_run
            SET status=?, source_count=?, projection_count=?, missing_count=?,
                mismatch_count=?, extra_count=?, completed_at=now()
            WHERE run_id=? AND status='RUNNING'
            """, status, sourceCount, projectionCount, missing, mismatch, extra, runId);
        differences.increment(found.size());
        return loadRun(runId);
    }

    /**
     * 幂等修复指定对账批次中的开放差异。
     *
     * <p>MISSING 和 MISMATCH 从权威成交重建；EXTRA 只标记为隔离，不删除数据，以保留调查证据。</p>
     *
     * @param runId 存在差异的对账批次
     * @param idempotencyKey 调用方提供的修复幂等键
     * @return 修复、隔离数量和重复执行标记
     */
    @Transactional
    public RepairOutcome repair(UUID runId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > 120) {
            throw new IllegalArgumentException(
                "修复幂等键不能为空且不能超过 120 字符 / invalid idempotency key");
        }
        lock("trade-repair:" + runId);
        RepairableRun run = jdbc.queryForObject("""
            SELECT symbol, status
            FROM trade_reconciliation_run WHERE run_id=? FOR UPDATE
            """, (rs, row) -> new RepairableRun(
                rs.getString("symbol"), rs.getString("status")), runId);
        if (run == null || !"DIFFERENCE_FOUND".equals(run.status())) {
            throw new IllegalStateException(
                "只有存在差异的对账批次允许修复 / run has no repairable differences");
        }

        RepairOutcome previous = findRepair(idempotencyKey);
        if (previous != null) {
            if (!previous.runId().equals(runId)) {
                throw new IllegalArgumentException(
                    "修复幂等键已被其他批次占用 / idempotency key conflict");
            }
            return new RepairOutcome(previous.repairId(), previous.runId(),
                previous.idempotencyKey(), previous.status(), previous.repairedCount(),
                previous.quarantinedCount(), true);
        }

        // 与撮合写入共用交易对级数据库锁，等待在途权威成交提交后再重新核验。
        lock(run.symbol());

        UUID repairId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO trade_projection_repair(
                repair_id, run_id, idempotency_key, status)
            VALUES (?, ?, ?, 'PROCESSING')
            """, repairId, runId, idempotencyKey);

        List<Difference> open = jdbc.query("""
            SELECT difference_id, trade_id, difference_type, expected_payload,
                   actual_payload, status
            FROM trade_reconciliation_difference
            WHERE run_id=? AND status='OPEN'
            ORDER BY CASE WHEN difference_type='EXTRA' THEN 0 ELSE 1 END,
                     difference_id
            FOR UPDATE
            """, TradeReliabilityService::mapDifference, runId);

        int rebuilt = 0;
        int quarantined = 0;
        for (Difference item : open) {
            Integer sourceExists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM trade_execution WHERE trade_id=?
                """, Integer.class, item.tradeId());
            if (sourceExists == null || sourceExists == 0) {
                // 没有权威成交的投影属于幽灵数据：保留记录并隔离，不做物理删除。
                quarantined += jdbc.update("""
                    UPDATE trade_projection projection
                    SET status='QUARANTINED', version=version+1, updated_at=now()
                    WHERE projection.trade_id=? AND projection.status='ACTIVE'
                      AND NOT EXISTS (
                          SELECT 1 FROM trade_execution authoritative
                          WHERE authoritative.trade_id=projection.trade_id
                      )
                    """, item.tradeId());
            } else {
                // 权威成交存在时采用 UPSERT 重建完整投影，修复过程不会修改事实表。
                int changed = jdbc.update("""
                    INSERT INTO trade_projection(
                        trade_id, symbol, maker_order_id, taker_order_id, price,
                        quantity, quote_amount, trade_sequence, source_event_id,
                        status, version, synced_at, updated_at)
                    SELECT trade_id, symbol, maker_order_id, taker_order_id, price,
                           quantity, quote_amount, trade_sequence, NULL,
                           'ACTIVE', 1, now(), now()
                    FROM trade_execution WHERE trade_id=?
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
                    """, item.tradeId());
                if (changed != 1) {
                    throw new IllegalStateException(
                        "权威成交重建失败 / authoritative trade rebuild failed");
                }
                rebuilt++;
            }
            jdbc.update("""
                UPDATE trade_reconciliation_difference
                SET status='REPAIRED', repaired_at=now()
                WHERE difference_id=? AND status='OPEN'
                """, item.differenceId());
        }

        jdbc.update("""
            UPDATE trade_projection_repair
            SET status='SUCCESS', repaired_count=?, quarantined_count=?,
                detail=?, completed_at=now()
            WHERE repair_id=? AND status='PROCESSING'
            """, rebuilt, quarantined,
            "仅修复成交派生投影，不修改订单、成交事实或资金账本 / projection-only repair",
            repairId);
        repaired.increment(rebuilt + quarantined);
        return new RepairOutcome(repairId, runId, idempotencyKey,
            "SUCCESS", rebuilt, quarantined, false);
    }

    /**
     * 加载已经持久化的对账批次。
     *
     * @param runId 对账批次编号
     * @return 批次汇总和差异明细
     */
    public ReconciliationReport loadRun(UUID runId) {
        ReconciliationSummary summary = jdbc.queryForObject("""
            SELECT run_id, symbol, status, source_count, projection_count,
                   missing_count, mismatch_count, extra_count, completed_at
            FROM trade_reconciliation_run WHERE run_id=?
            """, (rs, row) -> new ReconciliationSummary(
                rs.getObject("run_id", UUID.class),
                rs.getString("symbol"),
                rs.getString("status"),
                rs.getLong("source_count"),
                rs.getLong("projection_count"),
                rs.getInt("missing_count"),
                rs.getInt("mismatch_count"),
                rs.getInt("extra_count"),
                rs.getObject("completed_at", java.time.OffsetDateTime.class)
                    .toInstant()), runId);
        List<Difference> items = jdbc.query("""
            SELECT difference_id, trade_id, difference_type, expected_payload,
                   actual_payload, status
            FROM trade_reconciliation_difference
            WHERE run_id=? ORDER BY difference_type, trade_id
            """, TradeReliabilityService::mapDifference, runId);
        if (summary == null) {
            throw new IllegalStateException("对账批次不存在 / run not found");
        }
        return new ReconciliationReport(summary.runId(), summary.symbol(), summary.status(),
            summary.sourceCount(), summary.projectionCount(), summary.missingCount(),
            summary.mismatchCount(), summary.extraCount(), summary.completedAt(), items);
    }

    /**
     * 统计交易对的活动投影数量。
     *
     * @param rawSymbol 交易对
     * @return 活动投影数量
     */
    public long activeProjectionCount(String rawSymbol) {
        return count("""
            SELECT COUNT(*) FROM trade_projection WHERE symbol=? AND status='ACTIVE'
            """, normalizeSymbol(rawSymbol));
    }

    /** 校验重复成交号对应的全部不可变字段均与首次同步一致。 */
    private void requireSameProjection(TradeSyncCommand command) {
        Projection existing = jdbc.queryForObject("""
            SELECT trade_id, symbol, maker_order_id, taker_order_id, price,
                   quantity, quote_amount, trade_sequence, status
            FROM trade_projection WHERE trade_id=?
            """, (rs, row) -> new Projection(
                rs.getObject("trade_id", UUID.class),
                rs.getString("symbol"),
                rs.getObject("maker_order_id", UUID.class),
                rs.getObject("taker_order_id", UUID.class),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("quote_amount"),
                rs.getLong("trade_sequence"),
                rs.getString("status")), command.tradeId());
        boolean same = existing != null
            && existing.symbol().equals(command.symbol())
            && existing.makerOrderId().equals(command.makerOrderId())
            && existing.takerOrderId().equals(command.takerOrderId())
            && existing.price().compareTo(command.price()) == 0
            && existing.quantity().compareTo(command.quantity()) == 0
            && existing.quoteAmount().compareTo(command.quoteAmount()) == 0
            && existing.sequence() == command.tradeSequence()
            && "ACTIVE".equals(existing.status());
        if (!same) {
            throw new IllegalArgumentException(
                "同一成交号出现不可变字段冲突 / conflicting immutable trade");
        }
    }

    /** 按幂等键查找已执行的修复任务。 */
    private RepairOutcome findRepair(String idempotencyKey) {
        return jdbc.query("""
            SELECT repair_id, run_id, idempotency_key, status,
                   repaired_count, quarantined_count
            FROM trade_projection_repair WHERE idempotency_key=?
            """, (rs, row) -> new RepairOutcome(
                rs.getObject("repair_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("status"),
                rs.getInt("repaired_count"),
                rs.getInt("quarantined_count"),
                true), idempotencyKey).stream().findFirst().orElse(null);
    }

    /** 执行交易对维度的单值计数查询。 */
    private long count(String sql, String symbol) {
        Long value = jdbc.queryForObject(sql, Long.class, symbol);
        return value == null ? 0 : value;
    }

    /** 获取当前事务持有的 advisory lock。 */
    private void lock(String key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            (rs, row) -> Boolean.TRUE, key);
    }

    /** 将差异结果集映射为不可变差异对象。 */
    private static Difference mapDifference(ResultSet rs, int row) throws SQLException {
        return new Difference(
            rs.getObject("difference_id", UUID.class),
            rs.getObject("trade_id", UUID.class),
            rs.getString("difference_type"),
            rs.getString("expected_payload"),
            rs.getString("actual_payload"),
            rs.getString("status"));
    }

    /** 规范化并校验交易对格式。 */
    private static String normalizeSymbol(String rawSymbol) {
        Objects.requireNonNull(rawSymbol, "symbol");
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (!symbol.matches("[A-Z0-9]{2,20}-[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException(
                "交易对必须使用 BASE-QUOTE 格式 / invalid symbol");
        }
        return symbol;
    }

    /** 使用规范化字段生成 SHA-256 载荷指纹，用于识别事件号冲突。 */
    private static String fingerprint(TradeSyncCommand command) {
        String canonical = String.join("|",
            command.tradeId().toString(), command.symbol(),
            command.makerOrderId().toString(), command.takerOrderId().toString(),
            decimal(command.price()), decimal(command.quantity()),
            decimal(command.quoteAmount()), Long.toString(command.tradeSequence()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "运行环境缺少 SHA-256 / SHA-256 unavailable", exception);
        }
    }

    /** 将金额转成忽略 scale 的稳定字符串，保证数值相同的金额指纹一致。 */
    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** Inbox 中用于核验重复事件的最小快照。 */
    private record InboxRecord(UUID tradeId, String payloadHash, String status) {
    }

    /** 已存在成交投影的不可变字段快照。 */
    private record Projection(UUID tradeId, String symbol, UUID makerOrderId,
                              UUID takerOrderId, BigDecimal price, BigDecimal quantity,
                              BigDecimal quoteAmount, long sequence, String status) {
    }

    /** 本次对账 SQL 发现、尚未持久化的差异。 */
    private record DetectedDifference(UUID tradeId, String type,
                                      String expectedPayload, String actualPayload) {
    }

    /** 进入修复事务时锁定的对账批次状态。 */
    private record RepairableRun(String symbol, String status) {
    }

    /** 对账批次数据库快照。 */
    private record ReconciliationSummary(UUID runId, String symbol, String status,
                                         long sourceCount, long projectionCount,
                                         int missingCount, int mismatchCount, int extraCount,
                                         Instant completedAt) {
    }

    /**
     * 成交同步结果。
     *
     * @param eventId 事件编号
     * @param tradeId 成交编号
     * @param duplicateEvent 是否为重复事件
     * @param projectionInserted 是否首次插入投影
     * @param status Inbox 最终状态
     */
    public record SyncOutcome(UUID eventId, UUID tradeId, boolean duplicateEvent,
                              boolean projectionInserted, String status) {
    }

    /**
     * 单条成交投影差异。
     *
     * @param differenceId 差异编号
     * @param tradeId 成交编号
     * @param type 差异类型：MISSING、MISMATCH 或 EXTRA
     * @param expectedPayload 权威成交快照
     * @param actualPayload 实际投影快照
     * @param status 差异处理状态
     */
    public record Difference(UUID differenceId, UUID tradeId, String type,
                             String expectedPayload, String actualPayload, String status) {
    }

    /**
     * 成交对账报告。
     *
     * @param runId 对账批次编号
     * @param symbol 交易对
     * @param status CLEAN 或 DIFFERENCE_FOUND
     * @param sourceCount 权威成交数量
     * @param projectionCount 活动投影数量
     * @param missingCount 漏同步数量
     * @param mismatchCount 错值数量
     * @param extraCount 幽灵成交数量
     * @param completedAt 完成时间
     * @param differences 差异明细
     */
    public record ReconciliationReport(UUID runId, String symbol, String status,
                                       long sourceCount, long projectionCount,
                                       int missingCount, int mismatchCount, int extraCount,
                                       Instant completedAt, List<Difference> differences) {
    }

    /**
     * 成交投影修复结果。
     *
     * @param repairId 修复任务编号
     * @param runId 对账批次编号
     * @param idempotencyKey 修复幂等键
     * @param status 修复状态
     * @param repairedCount 重建投影数量
     * @param quarantinedCount 隔离幽灵投影数量
     * @param duplicate 是否为重复调用
     */
    public record RepairOutcome(UUID repairId, UUID runId, String idempotencyKey,
                                String status, int repairedCount, int quarantinedCount,
                                boolean duplicate) {
    }
}
