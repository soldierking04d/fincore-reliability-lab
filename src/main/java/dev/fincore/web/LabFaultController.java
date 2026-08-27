package dev.fincore.web;

import dev.fincore.domain.SettlementCommand;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("lab")
@RestController
@RequestMapping("/lab/faults")
public class LabFaultController {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafka;
    private final String topic;

    public LabFaultController(JdbcTemplate jdbc, KafkaTemplate<String, Object> kafka,
                              @Value("${fincore.kafka.settlement-topic}") String topic) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.topic = topic;
    }

    @PostMapping("/duplicate-message")
    public Map<String, Object> duplicate(@RequestBody SettlementCommand command,
                                         @RequestParam(defaultValue = "10") int copies) {
        if (copies < 1 || copies > 1000) throw new IllegalArgumentException("copies must be between 1 and 1000");
        for (int i = 0; i < copies; i++) kafka.send(topic, command.businessKey(), command);
        return Map.of("publishedCopies", copies, "messageId", command.messageId());
    }

    @PostMapping("/accounts/{accountId}/corrupt-balance")
    public Map<String, Object> corruptBalance(@PathVariable UUID accountId, @RequestParam BigDecimal delta) {
        int changed = jdbc.update("UPDATE account SET balance=balance+?, updated_at=now() WHERE account_id=?", delta, accountId);
        return Map.of("updated", changed == 1, "warning", "LAB PROFILE ONLY: ledger was intentionally bypassed");
    }
}

