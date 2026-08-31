package dev.fincore.web;

import dev.fincore.application.AdvancedLabScenarioService;
import dev.fincore.application.LabScenarioService;
import dev.fincore.application.MarketCrashScenarioService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 可靠性实验场景入口。
 *
 * <p>所有接口仅在 {@code lab} Profile 下启用，用于一键运行重复投递、热点撮合、成交
 * 同步恢复和市场暴跌日等可重复实验。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Profile("lab")
@RestController
@RequestMapping("/lab/scenarios")
public class LabScenarioController {
    /** 基础资金可靠性场景。 */
    private final LabScenarioService scenarios;
    /** 撮合与投影恢复高级场景。 */
    private final AdvancedLabScenarioService advanced;
    /** 市场暴跌日复合场景。 */
    private final MarketCrashScenarioService marketCrash;

    /** 创建实验场景控制器。 */
    public LabScenarioController(LabScenarioService scenarios,
                                 AdvancedLabScenarioService advanced,
                                 MarketCrashScenarioService marketCrash) {
        this.scenarios = scenarios;
        this.advanced = advanced;
        this.marketCrash = marketCrash;
    }

    /** @return 完整资金可靠性实验报告 */
    @PostMapping("/full")
    public LabScenarioService.ScenarioReport full() {
        return scenarios.runFullScenario();
    }

    /**
     * 运行热点交易对并发撮合实验。
     *
     * @param makers 预置 Maker 数量
     * @param takers 并发 Taker 数量
     * @return 撮合吞吐与一致性检查报告
     */
    @PostMapping("/matching-burst")
    public AdvancedLabScenarioService.BurstReport matchingBurst(
        @RequestParam(defaultValue = "80") int makers,
        @RequestParam(defaultValue = "16") int takers) {
        return advanced.runMatchingBurst(makers, takers);
    }

    /** @return 成交乱序、重复、漏数、错值和幽灵数据恢复报告 */
    @PostMapping("/trade-sync-recovery")
    public AdvancedLabScenarioService.SyncRecoveryReport tradeSyncRecovery() {
        return advanced.runTradeSyncRecovery();
    }

    /** @return 市场暴跌日端到端复合故障报告 */
    @PostMapping("/market-crash-day")
    public MarketCrashScenarioService.MarketCrashReport marketCrashDay() {
        return marketCrash.runMarketCrashDay();
    }
}
