package dev.fincore.web;

import dev.fincore.application.TradeReliabilityService;
import dev.fincore.domain.TradeSyncCommand;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成交投影同步、对账和修复接口。
 *
 * <p>权威数据来自 {@code trade_execution}，查询投影可以重建或隔离。接口不会为了
 * 让数量相等而修改权威成交、资金余额或历史账本。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/trade-reliability")
public class TradeReliabilityController {
    /** 成交同步与恢复服务。 */
    private final TradeReliabilityService reliability;

    /** @param reliability 成交同步与恢复服务 */
    public TradeReliabilityController(TradeReliabilityService reliability) {
        this.reliability = reliability;
    }

    /**
     * 幂等应用一条成交同步事件。
     *
     * @param command 成交同步命令
     * @return 同步结果
     */
    @PostMapping("/sync-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TradeReliabilityService.SyncOutcome sync(
        @RequestBody TradeSyncCommand command) {
        return reliability.apply(command);
    }

    /**
     * 对指定交易对执行权威成交与活动投影的全量对账。
     *
     * @param symbol 交易对
     * @return 包含 MISSING、MISMATCH 和 EXTRA 的对账报告
     */
    @PostMapping("/reconciliation-runs")
    public TradeReliabilityService.ReconciliationReport reconcile(
        @RequestParam String symbol) {
        return reliability.reconcile(symbol);
    }

    /**
     * 查询历史对账批次及其差异明细。
     *
     * @param runId 对账批次编号
     * @return 对账报告
     */
    @GetMapping("/reconciliation-runs/{runId}")
    public TradeReliabilityService.ReconciliationReport getRun(
        @PathVariable UUID runId) {
        return reliability.loadRun(runId);
    }

    /**
     * 幂等修复指定对账批次。
     *
     * @param runId 对账批次编号
     * @param request 修复幂等键
     * @return 重建与隔离数量
     */
    @PostMapping("/reconciliation-runs/{runId}/repairs")
    public TradeReliabilityService.RepairOutcome repair(
        @PathVariable UUID runId,
        @RequestBody RepairRequest request) {
        return reliability.repair(runId, request.idempotencyKey());
    }

    /** @param idempotencyKey 修复请求幂等键 */
    public record RepairRequest(String idempotencyKey) {
    }
}
