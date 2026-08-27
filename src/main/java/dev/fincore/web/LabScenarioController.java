package dev.fincore.web;

import dev.fincore.application.LabScenarioService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("lab")
@RestController
@RequestMapping("/lab/scenarios")
public class LabScenarioController {
    private final LabScenarioService scenarios;
    public LabScenarioController(LabScenarioService scenarios) { this.scenarios = scenarios; }

    @PostMapping("/full")
    public LabScenarioService.ScenarioReport full() { return scenarios.runFullScenario(); }
}

