package dev.fincore;

import static org.junit.jupiter.api.Assertions.*;
import dev.fincore.application.LabScenarioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("lab")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "fincore.outbox-delay-ms=3600000",
    "fincore.outbox-recovery-delay-ms=3600000",
    "spring.kafka.producer.properties.max.block.ms=1000",
    "spring.kafka.producer.properties.delivery.timeout.ms=2000",
    "spring.kafka.producer.properties.request.timeout.ms=1000"
})
class LabScenarioIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired LabScenarioService scenarios;

    @Test void fullAutomatedScenarioPasses() {
        var report = scenarios.runFullScenario();
        assertFalse(report.checks().isEmpty());
        assertTrue(report.checks().values().stream().allMatch(value -> value.startsWith("PASS")));
    }
}

