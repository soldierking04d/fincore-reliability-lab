package dev.fincore.web;

import dev.fincore.application.SettlementService;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.messaging.MessageSubmissionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

/**
 * 结算命令接入与结果查询接口。
 *
 * <p>POST 接口只把命令写入 Kafka，不绕过 Worker Fencing 直接调用资金结算服务。
 * 实际资金处理由 {@code SettlementListener} 获取有效 Lease 后执行。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@RestController
@RequestMapping("/api/settlements")
public class SettlementController {
    /** 结算结果查询服务。 */
    private final SettlementService service;
    /** Kafka 结算命令发送器。 */
    private final KafkaTemplate<String, Object> kafka;
    /** 结算命令 Topic。 */
    private final String topic;
    /** 等待 Broker 确认接收的最长时间。 */
    private final long submitTimeoutNanos;

    /** 创建结算接入控制器。 */
    public SettlementController(SettlementService service, KafkaTemplate<String, Object> kafka,
                                @Value("${fincore.kafka.settlement-topic}") String topic,
                                ConcurrencyProperties properties) {
        this.service = service;
        this.kafka = kafka;
        this.topic = topic;
        this.submitTimeoutNanos = properties.getKafkaSubmitTimeout().toNanos();
    }

    /**
     * 提交结算命令并等待 Kafka Broker 确认持久接收。
     *
     * @param command 结算命令
     * @return Broker 已接收回执；该回执不代表资金已经结算成功
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Accepted submit(@RequestBody SettlementCommand command) {
        try {
            // 只有 Broker 确认后才返回 202；等待发生在虚拟线程，不占用一条 Tomcat 平台线程。
            kafka.send(topic, command.businessKey(), command)
                .get(submitTimeoutNanos, TimeUnit.NANOSECONDS);
            return new Accepted(command.messageId(), command.businessKey(), "ACCEPTED");
        } catch (TimeoutException | ExecutionException exception) {
            throw new MessageSubmissionException(
                "Kafka acknowledgement failed or is unknown; retry with the same messageId and businessKey",
                exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MessageSubmissionException("Kafka acknowledgement wait interrupted", exception);
        } catch (RuntimeException exception) {
            throw new MessageSubmissionException(
                "Kafka submission failed before acknowledgement; retry with the same messageId and businessKey",
                exception
            );
        }
    }

    /**
     * 查询结算业务的当前处理结果。
     *
     * @param businessKey 结算业务键
     * @return 结算状态和说明
     */
    @GetMapping("/{businessKey}")
    public SettlementOutcome get(@PathVariable String businessKey) {
        return service.get(businessKey);
    }

    /**
     * 异步命令接收回执。
     *
     * @param messageId 消息编号
     * @param businessKey 结算业务键
     * @param status 固定为 ACCEPTED
     */
    public record Accepted(String messageId, String businessKey, String status) {
    }
}
