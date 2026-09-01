package dev.fincore.messaging;

/**
 * Kafka 命令是否持久接收处于失败或未知状态时抛出的可重试异常。
 *
 * <p>调用方必须携带相同 messageId 和 businessKey 重试。即使 Broker 实际已经收到但确认丢失，
 * Inbox 与数据库唯一约束也只允许一次资金效果。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public class MessageSubmissionException extends RuntimeException {
    /** 创建消息提交异常。 */
    public MessageSubmissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
