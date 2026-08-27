package dev.fincore.web;

import dev.fincore.application.CompensationService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compensations")
public class CompensationController {
    private final CompensationService service;
    public CompensationController(CompensationService service) { this.service = service; }

    @PostMapping("/{originalBusinessKey}")
    public CompensationService.CompensationOutcome compensate(@PathVariable String originalBusinessKey,
                                                               @RequestBody CompensationRequest request) {
        return service.compensate(originalBusinessKey, request.reason());
    }
    public record CompensationRequest(String reason) {}
}
