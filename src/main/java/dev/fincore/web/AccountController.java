package dev.fincore.web;

import dev.fincore.application.AccountService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账户与账本摘要查询接口。
 *
 * <p>本控制器只负责 HTTP 参数映射，账户创建和账本计算统一委托给
 * {@link AccountService}，避免在接入层复制金融业务规则。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    /** 账户应用服务。 */
    private final AccountService service;

    /** @param service 账户应用服务 */
    public AccountController(AccountService service) {
        this.service = service;
    }

    /**
     * 创建实验账户。
     *
     * @param request 账户所有者、资产、类型和期初余额
     * @return 新创建的账户快照
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.AccountView create(@RequestBody CreateAccountRequest request) {
        return service.create(request.ownerId(), request.asset(), request.accountType(), request.openingBalance());
    }

    /**
     * 查询账户当前余额与基础信息。
     *
     * @param id 账户编号
     * @return 账户快照
     */
    @GetMapping("/{id}")
    public AccountService.AccountView get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * 查询按不可变账本重新汇总的账户金额。
     *
     * @param id 账户编号
     * @return 期初余额、账本净额和当前余额
     */
    @GetMapping("/{id}/ledger-summary")
    public Map<String, Object> summary(@PathVariable UUID id) {
        return service.ledgerSummary(id);
    }

    /**
     * 创建账户的 HTTP 请求。
     *
     * @param ownerId 账户所有者
     * @param asset 资产代码
     * @param accountType 账户类型
     * @param openingBalance 期初余额
     */
    public record CreateAccountRequest(
        String ownerId,
        String asset,
        String accountType,
        BigDecimal openingBalance
    ) {
    }
}
