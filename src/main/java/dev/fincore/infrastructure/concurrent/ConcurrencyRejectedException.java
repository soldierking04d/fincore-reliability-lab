package dev.fincore.infrastructure.concurrent;

/**
 * 有界执行队列已经饱和时抛出的可重试异常。
 *
 * <p>明确拒绝比无界堆积更安全：调用方能够携带原幂等键退避重试，服务则不会因为排队对象持续
 * 占用堆内存而在压力峰值下发生连锁故障。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public class ConcurrencyRejectedException extends RuntimeException {
    /** 创建过载拒绝异常。 */
    public ConcurrencyRejectedException(String message) {
        super(message);
    }
}
