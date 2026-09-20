package dev.fincore.application;

import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 把金融结果指标绑定到实际数据库提交，而非业务方法返回。
 *
 * <p>加入外层事务时只在最外层提交后增加计数；回滚、提交失败或没有真实事务均不计成功。
 * 指标不参与账本一致性，不替代数据库事实，也不用于幂等或重试控制。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
public final class TransactionMetrics {
    /** 指标故障仅写诊断，不把已提交资金伪装成事务失败。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionMetrics.class);

    /** 纯静态辅助类型不需要实例。 */
    private TransactionMetrics() {
    }

    /**
     * 在当前真实事务成功提交后增加一次结果计数。
     *
     * @param counter 表示已提交结果的固定低基数指标
     */
    public static void incrementAfterCommit(Counter counter) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    counter.increment();
                } catch (RuntimeException exception) {
                    // 此时数据库已经提交；观测系统的失败不能改变资金结果或诱发误报回滚。
                    LOGGER.warn("Committed financial result metric could not be recorded", exception);
                }
            }
        });
    }
}
