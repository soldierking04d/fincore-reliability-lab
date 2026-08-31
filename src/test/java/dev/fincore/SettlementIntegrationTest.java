package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.AccountService;
import dev.fincore.application.SettlementService;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 重复消息只产生一次资金效果的结算集成测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.task.scheduling.enabled=false"
})
class SettlementIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired AccountService accounts;
    @Autowired SettlementService settlements;

    @Test void duplicateMessageHasExactlyOneFinancialEffect() {
        var payer = accounts.create("payer", "USDT", "USER", new BigDecimal("100"));
        var payee = accounts.create("payee", "USDT", "USER", BigDecimal.ZERO);
        var fee = accounts.create("fee-00", "USDT", "SYSTEM_FEE", BigDecimal.ZERO);
        SettlementCommand command = new SettlementCommand("msg-1", "order-1", payer.accountId(),
            payee.accountId(), fee.accountId(), "USDT", new BigDecimal("10"), new BigDecimal("1"));

        assertEquals(SettlementStatus.SUCCESS, settlements.settle(command).status());
        assertTrue(settlements.settle(command).duplicate());
        assertEquals(0, accounts.get(payer.accountId()).balance().compareTo(new BigDecimal("89")));
        assertEquals(0, accounts.get(payee.accountId()).balance().compareTo(new BigDecimal("10")));
        assertEquals(0, accounts.get(fee.accountId()).balance().compareTo(new BigDecimal("1")));
    }
}
