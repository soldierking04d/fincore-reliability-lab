package dev.fincore.application;

import dev.fincore.domain.TradeSyncCommand;
import dev.fincore.domain.TradingIdentifiers;
import dev.fincore.infrastructure.persistence.mapper.TradeReliabilityMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成交派生投影的同步、对账与自动修复服务。
 *
 * <p><strong>解决的问题：</strong>成交同步可能重复、乱序、漏数、错值或产生幽灵投影，本服务让查询
 * 投影持续收敛到不可修改的 {@code trade_execution} 权威事实。</p>
 *
 * <p><strong>执行链路：</strong>同步入口使用 Inbox 和载荷指纹抵御重复/冲突重放；对账使用一致事实
 * 窗口与全外连接识别 MISSING、MISMATCH、EXTRA；修复只重建或隔离投影。</p>
 *
 * <p><strong>CPU 与 I/O：</strong>单条同步只计算一次 SHA-256 并执行常量次数据库操作；哈希成本用于
 * 阻止同事件号偷换载荷。全量差异比较交给数据库集合运算，不在 JVM 构造两份全集；对账按交易对
 * 串行并应在生产中分页、限速，避免与撮合争抢数据库 CPU 和连接。</p>
 *
 * <p><strong>正确性边界：</strong>修复只操作派生投影，绝不回写权威成交、订单、余额或历史账本；
 * 幽灵数据先隔离留证，不为得到 CLEAN 结果而删除事实。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Service
public class TradeReliabilityService {
    /** 对账批次存在可修复差异时的唯一准入状态。 */
    private static final String DIFFERENCE_FOUND_STATUS = "DIFFERENCE_FOUND";
    /** 成交同步、对账与投影修复持久化接口。 */
    private final TradeReliabilityMapper tradeMapper;
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
     * @param tradeMapper 成交可靠性持久化接口
     * @param registry Micrometer 指标注册表
     */
    public TradeReliabilityService(TradeReliabilityMapper tradeMapper, MeterRegistry registry) {
        this.tradeMapper = tradeMapper;
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
    @Transactional(rollbackFor = Exception.class)
    public SyncOutcome apply(TradeSyncCommand command) {
        // 指纹只计算一次并复用；其 CPU 成本换来跨重试的完整载荷一致性证明。
        String payloadHash = fingerprint(command);
        // 先占用事件号；数据库唯一约束是跨进程幂等的最终防线。
        int insertedEvent = tradeMapper.insertInbox(
            command.eventId(), command.tradeId(), payloadHash);
        TradeReliabilityMapper.InboxRow inbox = tradeMapper.findInbox(command.eventId());

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
        int projected = tradeMapper.insertProjection(command);

        if (projected == 0) {
            requireSameProjection(command);
        }
        tradeMapper.markInboxProcessed(command.eventId());
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
    @Transactional(isolation = Isolation.REPEATABLE_READ, rollbackFor = Exception.class)
    public ReconciliationReport reconcile(String rawSymbol) {
        String symbol = normalizeSymbol(rawSymbol);
        lock("trade-reconcile:" + symbol);
        UUID runId = UUID.randomUUID();
        tradeMapper.insertRun(runId, symbol);

        long sourceCount = tradeMapper.countSource(symbol);
        long projectionCount = tradeMapper.countActiveProjection(symbol);
        // 全外连接与比较在数据库侧完成，JVM 只接收差异，不加载两份完整成交集合。
        List<TradeReliabilityMapper.DetectedDifferenceRow> found =
            tradeMapper.findDifferences(symbol);

        int missing = 0;
        int mismatch = 0;
        int extra = 0;
        for (TradeReliabilityMapper.DetectedDifferenceRow item : found) {
            tradeMapper.insertDifference(UUID.randomUUID(), runId, item);
            if ("MISSING".equals(item.type())) {
                missing++;
            } else if ("MISMATCH".equals(item.type())) {
                mismatch++;
            } else {
                extra++;
            }
        }
        String status = found.isEmpty() ? "CLEAN" : "DIFFERENCE_FOUND";
        tradeMapper.completeRun(runId, status, sourceCount, projectionCount,
            missing, mismatch, extra);
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
    @Transactional(rollbackFor = Exception.class)
    public RepairOutcome repair(UUID runId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > 120) {
            throw new IllegalArgumentException(
                "修复幂等键不能为空且不能超过 120 字符 / invalid idempotency key");
        }
        lock("trade-repair:" + runId);
        TradeReliabilityMapper.RepairableRunRow run = tradeMapper.lockRun(runId);
        if (run == null || !DIFFERENCE_FOUND_STATUS.equals(run.status())) {
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
        tradeMapper.insertRepair(repairId, runId, idempotencyKey);
        List<TradeReliabilityMapper.DifferenceRow> open = tradeMapper.lockOpenDifferences(runId);

        int rebuilt = 0;
        int quarantined = 0;
        for (TradeReliabilityMapper.DifferenceRow item : open) {
            int sourceExists = tradeMapper.countSourceByTradeId(item.tradeId());
            if (sourceExists == 0) {
                // 没有权威成交的投影属于幽灵数据：保留记录并隔离，不做物理删除。
                quarantined += tradeMapper.quarantineGhost(item.tradeId());
            } else {
                // 权威成交存在时采用 UPSERT 重建完整投影，修复过程不会修改事实表。
                int changed = tradeMapper.rebuildProjection(item.tradeId());
                if (changed != 1) {
                    throw new IllegalStateException(
                        "权威成交重建失败 / authoritative trade rebuild failed");
                }
                rebuilt++;
            }
            tradeMapper.markDifferenceRepaired(item.differenceId());
        }

        tradeMapper.completeRepair(repairId, rebuilt, quarantined,
            "仅修复成交派生投影，不修改订单、成交事实或资金账本");
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
        TradeReliabilityMapper.ReconciliationSummaryRow summary = tradeMapper.findRun(runId);
        if (summary == null) {
            throw new IllegalStateException("对账批次不存在 / run not found");
        }
        List<Difference> items = tradeMapper.findRunDifferences(runId).stream()
            .map(TradeReliabilityService::toDifference)
            .toList();
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
        return tradeMapper.countActiveProjection(normalizeSymbol(rawSymbol));
    }

    /** 校验重复成交号对应的全部不可变字段均与首次同步一致。 */
    private void requireSameProjection(TradeSyncCommand command) {
        TradeReliabilityMapper.ProjectionRow existing = tradeMapper.findProjection(command.tradeId());
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
        TradeReliabilityMapper.RepairRow row = tradeMapper.findRepair(idempotencyKey);
        return row == null ? null : new RepairOutcome(
            row.repairId(), row.runId(), row.idempotencyKey(), row.status(),
            row.repairedCount(), row.quarantinedCount(), true);
    }

    /** 获取当前事务持有的 advisory lock。 */
    private void lock(String key) {
        tradeMapper.lock(key);
    }

    /** 把 Mapper 差异快照转换为应用层对外模型。 */
    private static Difference toDifference(TradeReliabilityMapper.DifferenceRow row) {
        return new Difference(row.differenceId(), row.tradeId(), row.type(),
            row.expectedPayload(), row.actualPayload(), row.status());
    }

    /** 规范化并校验交易对格式。 */
    private static String normalizeSymbol(String rawSymbol) {
        Objects.requireNonNull(rawSymbol, "symbol");
        String symbol = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (!TradingIdentifiers.isSymbol(symbol)) {
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
