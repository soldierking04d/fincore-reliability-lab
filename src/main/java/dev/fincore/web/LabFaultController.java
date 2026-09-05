package dev.fincore.web;

import dev.fincore.domain.SettlementCommand;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.infrastructure.persistence.mapper.LabScenarioMapper;
import dev.fincore.messaging.MessageSubmissionException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供实验环境使用的故障注入接口。
 *
 * <p>控制器受 {@code lab} Profile 保护，生产配置不会加载。它可以制造 Kafka 重复投递
 * 和“余额绕过账本”故障，用于验证 Inbox 幂等与余额—账本对账能力。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Profile("lab & !public-demo")
@RestController
@RequestMapping("/lab/faults")
public class LabFaultController {
    /** 单次接口允许注入的最大重复消息数，防止实验端点无界放大。 */
    private static final int MAX_DUPLICATE_COPIES = 1_000;
    /** 用于注入受控数据库故障的 MyBatis Mapper。 */
    private final LabScenarioMapper labMapper;
    /** 用于制造重复投递的 Kafka 客户端。 */
    private final KafkaTemplate<String, Object> kafka;
    /** 结算命令 Topic。 */
    private final String topic;
    /** 等待全部故障注入消息获得 Broker 确认的最长时间。 */
    private final long submitTimeoutNanos;

    /** 创建实验故障注入控制器。 */
    public LabFaultController(LabScenarioMapper labMapper, KafkaTemplate<String, Object> kafka,
                              @Value("${fincore.kafka.settlement-topic}") String topic,
                              ConcurrencyProperties properties) {
        this.labMapper = labMapper;
        this.kafka = kafka;
        this.topic = topic;
        this.submitTimeoutNanos = properties.getKafkaSubmitTimeout().toNanos();
    }

    /**
     * 向 Kafka 重复发布同一条结算命令。
     *
     * @param command 结算命令
     * @param copies 重复发布次数，限制为 1 至 1000
     * @return Broker 已确认的发布次数和消息编号
     */
    @PostMapping("/duplicate-message")
    public Map<String, Object> duplicate(@RequestBody SettlementCommand command,
                                         @RequestParam(defaultValue = "10") int copies) {
        if (copies < 1 || copies > MAX_DUPLICATE_COPIES) {
            throw new IllegalArgumentException("copies must be between 1 and 1000");
        }
        CompletableFuture<?>[] acknowledgements = new CompletableFuture<?>[copies];
        try {
            for (int i = 0; i < copies; i++) {
                acknowledgements[i] = kafka.send(topic, command.businessKey(), command);
            }
            CompletableFuture.allOf(acknowledgements).get(submitTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException exception) {
            throw new MessageSubmissionException(
                "duplicate injection acknowledgement failed or is unknown; reuse the same messageId",
                exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MessageSubmissionException("duplicate injection acknowledgement wait interrupted", exception);
        } catch (RuntimeException exception) {
            throw new MessageSubmissionException("duplicate injection failed before acknowledgement", exception);
        }
        return Map.of("acknowledgedCopies", copies, "messageId", command.messageId());
    }

    /**
     * 故意绕过账本修改账户余额，制造可被对账发现的差异。
     *
     * @param accountId 目标账户
     * @param delta 余额改变量
     * @return 是否成功修改以及实验警告
     */
    @PostMapping("/accounts/{accountId}/corrupt-balance")
    public Map<String, Object> corruptBalance(@PathVariable UUID accountId, @RequestParam BigDecimal delta) {
        int changed = labMapper.injectBalanceCorruption(accountId, delta);
        return Map.of(
            "updated", changed == 1,
            "warning", "仅限实验环境：本次修改故意绕过账本"
        );
    }
}
