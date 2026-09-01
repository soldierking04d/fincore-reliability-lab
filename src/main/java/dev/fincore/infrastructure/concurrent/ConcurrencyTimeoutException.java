package dev.fincore.infrastructure.concurrent;

/**
 * 调用方等待高并发任务完成超时时抛出的可重试异常。
 *
 * <p>超时只结束 HTTP 等待，不会粗暴中断已经进入数据库事务的任务。调用方应使用同一幂等键查询
 * 或重试，数据库唯一约束会保证最多一次金融效果。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public class ConcurrencyTimeoutException extends RuntimeException {
    /** 创建等待超时异常。 */
    public ConcurrencyTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
