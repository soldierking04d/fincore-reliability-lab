package dev.fincore.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.fincore.domain.SettlementCommand;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.infrastructure.persistence.mapper.LabScenarioMapper;
import dev.fincore.messaging.MessageSubmissionException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/** 故障注入消息必须获得 Broker 确认后才计入发布数量。 */
class LabFaultControllerTest {
    /** 已完成 Future 才能产生 acknowledgedCopies。 */
    @Test
    void reportsOnlyBrokerAcknowledgedCopies() {
        KafkaTemplate<String, Object> kafka = kafkaTemplate();
        SettlementCommand command = command();
        when(kafka.send("settlement", command.businessKey(), command)).thenReturn(
            CompletableFuture.completedFuture(new SendResult<>(
                new ProducerRecord<>("settlement", command.businessKey(), command),
                null
            ))
        );

        Map<String, Object> result = controller(kafka).duplicate(command, 3);

        assertEquals(3, result.get("acknowledgedCopies"));
    }

    /** 任一发送失败时不能返回一个虚假的成功发布数量。 */
    @Test
    void brokerFailureDoesNotReportPublishedCopies() {
        KafkaTemplate<String, Object> kafka = kafkaTemplate();
        SettlementCommand command = command();
        when(kafka.send("settlement", command.businessKey(), command)).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException("broker unavailable"))
        );

        assertThrows(MessageSubmissionException.class, () -> controller(kafka).duplicate(command, 2));
    }

    /** 创建带短确认超时的控制器。 */
    private static LabFaultController controller(KafkaTemplate<String, Object> kafka) {
        ConcurrencyProperties properties = new ConcurrencyProperties();
        properties.setKafkaSubmitTimeout(Duration.ofMillis(100));
        return new LabFaultController(mock(LabScenarioMapper.class), kafka, "settlement", properties);
    }

    /** 创建合法结算命令。 */
    private static SettlementCommand command() {
        return new SettlementCommand(
            "fault-message",
            "fault-business",
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "USDT",
            BigDecimal.ONE,
            new BigDecimal("0.01")
        );
    }

    /** 创建泛型一致的 Kafka Mock。 */
    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, Object> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
