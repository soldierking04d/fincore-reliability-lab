package dev.fincore.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.fincore.application.SettlementService;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.messaging.MessageSubmissionException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** 结算 HTTP 接入等待 Kafka 持久接收确认的契约测试。 */
class SettlementControllerTest {

    /** 只有 Kafka Future 成功完成后才能返回 ACCEPTED。 */
    @Test
    void returnsAcceptedOnlyAfterBrokerAcknowledgement() {
        KafkaTemplate<String, Object> kafka = kafkaTemplate();
        SettlementCommand command = command();
        CompletableFuture<SendResult<String, Object>> acknowledged =
            CompletableFuture.completedFuture(new SendResult<>(
                new ProducerRecord<>("settlement", command.businessKey(), command), null));
        when(kafka.send("settlement", command.businessKey(), command)).thenReturn(acknowledged);
        SettlementController controller = controller(kafka);

        SettlementController.Accepted accepted = controller.submit(command);

        assertEquals("ACCEPTED", accepted.status());
        assertEquals(command.messageId(), accepted.messageId());
    }

    /** Broker 明确失败时必须返回可重试异常，不能伪装成 202。 */
    @Test
    void brokerFailureDoesNotReturnFalseAcceptance() {
        KafkaTemplate<String, Object> kafka = kafkaTemplate();
        SettlementCommand command = command();
        when(kafka.send("settlement", command.businessKey(), command))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        SettlementController controller = controller(kafka);

        assertThrows(MessageSubmissionException.class, () -> controller.submit(command));
    }

    /** 创建只测试发送行为的控制器。 */
    private static SettlementController controller(KafkaTemplate<String, Object> kafka) {
        ConcurrencyProperties properties = new ConcurrencyProperties();
        properties.setKafkaSubmitTimeout(Duration.ofMillis(100));
        return new SettlementController(mock(SettlementService.class), kafka, "settlement", properties);
    }

    /** 创建合法且账户彼此不同的结算命令。 */
    private static SettlementCommand command() {
        return new SettlementCommand(
            "message-1",
            "business-1",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "USDT",
            BigDecimal.ONE,
            new BigDecimal("0.01")
        );
    }

    /** 创建带正确泛型的 KafkaTemplate Mock。 */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, Object> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
