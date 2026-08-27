package dev.fincore.web;

import dev.fincore.application.ReconciliationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {
    private final ReconciliationService service;
    public ReconciliationController(ReconciliationService service) { this.service = service; }

    @PostMapping("/run")
    public ReconciliationService.ReconciliationReport run() { return service.reconcileAll(); }
}

