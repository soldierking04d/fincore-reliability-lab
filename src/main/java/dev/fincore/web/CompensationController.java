package dev.fincore.web;

import dev.fincore.application.CompensationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反向账本补偿接口。
 *
 * <p>补偿始终创建独立业务单和反向账本，不修改原始成功流水。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/compensations")
public class CompensationController {
    /** 补偿应用服务。 */
    private final CompensationService service;

    /** @param service 补偿应用服务 */
    public CompensationController(CompensationService service) {
        this.service = service;
    }

    /**
     * 对指定原始业务执行幂等补偿。
     *
     * @param originalBusinessKey 原始结算业务键
     * @param request 补偿原因
     * @return 补偿处理结果
     */
    @PostMapping("/{originalBusinessKey}")
    public CompensationService.CompensationOutcome compensate(@PathVariable String originalBusinessKey,
                                                               @RequestBody CompensationRequest request) {
        return service.compensate(originalBusinessKey, request.reason());
    }

    /** @param reason 补偿原因，进入审计记录 */
    public record CompensationRequest(String reason) {
    }
}
