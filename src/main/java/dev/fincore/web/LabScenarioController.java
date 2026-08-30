package dev.fincore.web;

import dev.fincore.application.AdvancedLabScenarioService;
import dev.fincore.application.LabScenarioService;
import dev.fincore.application.MarketCrashScenarioService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("lab")
@RestController
@RequestMapping("/lab/scenarios")
public class LabScenarioController {
    private final LabScenarioService scenarios;
    private final AdvancedLabScenarioService advanced;
    private final MarketCrashScenarioService marketCrash;

    public LabScenarioController(LabScenarioService scenarios,
                                 AdvancedLabScenarioService advanced,
                                 MarketCrashScenarioService marketCrash) {
        this.scenarios = scenarios;
        this.advanced = advanced;
        this.marketCrash = marketCrash;
    }

    @PostMapping("/full")
    public LabScenarioService.ScenarioReport full() {
        return scenarios.runFullScenario();
    }

    @PostMapping("/matching-burst")
    public AdvancedLabScenarioService.BurstReport matchingBurst(
        @RequestParam(defaultValue = "80") int makers,
        @RequestParam(defaultValue = "16") int takers) {
        return advanced.runMatchingBurst(makers, takers);
    }

    @PostMapping("/trade-sync-recovery")
    public AdvancedLabScenarioService.SyncRecoveryReport tradeSyncRecovery() {
        return advanced.runTradeSyncRecovery();
    }

    @PostMapping("/market-crash-day")
    public MarketCrashScenarioService.MarketCrashReport marketCrashDay() {
        return marketCrash.runMarketCrashDay();
    }
}

