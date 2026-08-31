package dev.fincore.web;

import dev.fincore.application.ReconciliationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 余额—账本对账接口。
 *
 * <p>该接口只发现并冻结差异，不自动改写资金余额，避免对账程序掩盖真实资金问题。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {
    /** 资金对账服务。 */
    private final ReconciliationService service;

    /** @param service 资金对账服务 */
    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** @return 本次余额—账本差异报告 */
    @PostMapping("/run")
    public ReconciliationService.ReconciliationReport run() {
        return service.reconcileAll();
    }
}
