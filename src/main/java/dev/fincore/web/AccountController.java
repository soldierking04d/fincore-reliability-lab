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

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService service;
    public AccountController(AccountService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountService.AccountView create(@RequestBody CreateAccountRequest request) {
        return service.create(request.ownerId(), request.asset(), request.accountType(), request.openingBalance());
    }

    @GetMapping("/{id}")
    public AccountService.AccountView get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/{id}/ledger-summary")
    public Map<String, Object> summary(@PathVariable UUID id) { return service.ledgerSummary(id); }

    public record CreateAccountRequest(String ownerId, String asset, String accountType, BigDecimal openingBalance) {}
}

