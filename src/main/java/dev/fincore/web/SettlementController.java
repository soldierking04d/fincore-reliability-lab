package dev.fincore.web;

import dev.fincore.application.SettlementService;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settlements")
public class SettlementController {
    private final SettlementService service;
    private final KafkaTemplate<String, Object> kafka;
    private final String topic;

    public SettlementController(SettlementService service, KafkaTemplate<String, Object> kafka,
                                @Value("${fincore.kafka.settlement-topic}") String topic) {
        this.service = service;
        this.kafka = kafka;
        this.topic = topic;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted submit(@RequestBody SettlementCommand command) {
        kafka.send(topic, command.businessKey(), command);
        return new Accepted(command.messageId(), command.businessKey(), "ACCEPTED");
    }

    @GetMapping("/{businessKey}")
    public SettlementOutcome get(@PathVariable String businessKey) { return service.get(businessKey); }

    public record Accepted(String messageId, String businessKey, String status) {}
}
