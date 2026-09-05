package dev.fincore.infrastructure.concurrent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.java21.instrument.binder.jdk.VirtualThreadMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 高并发执行资源的集中配置。
 *
 * <p><strong>解决的问题：</strong>不同子系统不能共享一个“越忙越扩”的线程池。Web、撮合、Kafka
 * 和调度任务的阻塞方式、顺序要求及生命周期不同，必须在这里统一分配执行资源。</p>
 *
 * <p><strong>CPU 模型：</strong>Spring MVC 使用 Java 21 虚拟线程承接短时阻塞请求，降低等待
 * JDBC/Kafka 时的平台线程与栈内存成本；撮合 Lane 使用固定单平台线程，减少同交易对上下文切换
 * 和无效锁竞争；Kafka Consumer 与定时任务也显式使用有界平台线程，避免底层同步代码造成虚拟
 * 线程 carrier pinning。线程数只能结合容器可用处理器、队深、CPU 饱和度和尾延迟调整。</p>
 *
 * <p><strong>正确性边界：</strong>线程隔离只负责准入和调度，不提供跨实例顺序或金融幂等；最终边界
 * 仍是 PostgreSQL 锁、唯一约束、事务和 Epoch Fencing。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConcurrencyProperties.class)
public class ConcurrencyConfiguration {
    /** 重试日志不打印消息载荷，首次及每分钟保留原因供定位。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencyConfiguration.class);
    /** 持续失败时每隔多少次重试再次记录一条诊断日志。 */
    private static final int RETRY_LOG_INTERVAL = 60;
    /**
     * 注册虚拟线程 pinning 和提交失败指标。
     *
     * <p>该 Binder 使用 JFR 事件观察虚拟线程是否因同步块或本地调用长期占住 carrier；它不自动
     * 解决 pinning，告警后仍需用 JFR 栈定位具体锁点。</p>
     */
    @Bean(destroyMethod = "close")
    VirtualThreadMetrics virtualThreadMetrics() {
        return new VirtualThreadMetrics();
    }

    /**
     * 创建按交易对分片的有界撮合执行器。
     *
     * <p>一个 Lane 只有一个平台线程，Lane 数决定同实例最大撮合 CPU 并行度；盲目超过可用核心数
     * 会增加运行队列和上下文切换，不能提升同交易对吞吐。</p>
     */
    @Bean
    StripedTaskExecutor matchingTaskExecutor(ConcurrencyProperties properties,
                                             MeterRegistry registry) {
        properties.validate();
        return new StripedTaskExecutor(
            properties.getMatchingLanes(),
            properties.getMatchingQueueCapacityPerLane(),
            properties.getMatchingCancelQueueCapacityPerLane(),
            registry
        );
    }

    /**
     * 为 Kafka 长生命周期 Consumer 创建固定数量的平台线程。
     *
     * <p>每个 Consumer 在自己的线程内调用非线程安全的 Kafka Consumer API，线程数与 Listener
     * 并发数完全一致，不使用无界任务队列。线程池不承担撮合命令，避免 rebalance 或 Broker 抖动
     * 占用撮合 CPU 预算。</p>
     */
    @Bean(name = "settlementKafkaConsumerExecutor")
    ThreadPoolTaskExecutor settlementKafkaConsumerExecutor(ConcurrencyProperties properties) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // effectiveSettlementConsumers 使用容器感知 CPU 数封顶，防止大机器配置照搬到小配额容器。
        int consumerConcurrency = properties.effectiveSettlementConsumers();
        executor.setCorePoolSize(consumerConcurrency);
        executor.setMaxPoolSize(consumerConcurrency);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("fincore-settlement-consumer-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * 创建使用平台线程的 Kafka Listener 容器，避免继承全局虚拟线程执行器。
     *
     * <p>Record 级确认保证异常返回时不提交当前 Offset；CPU 优化不能改成“先批量确认后处理”，否则
     * 进程崩溃会把未落账消息伪装成已完成。</p>
     */
    @Bean(name = "settlementKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> settlementKafkaListenerContainerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ConsumerFactory<Object, Object> consumerFactory,
        ConcurrencyProperties properties,
        @Qualifier("settlementKafkaConsumerExecutor") AsyncTaskExecutor consumerExecutor) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setConcurrency(properties.effectiveSettlementConsumers());
        factory.getContainerProperties().setListenerTaskExecutor(consumerExecutor);
        // 资金命令失败不得由默认“重试耗尽后记录日志并跳过”策略确认。
        // 阻塞所在分区并退避，数据库在途仍保留；需人工处置的坏消息必须走审核，不静默丢弃。
        DefaultErrorHandler errors = new DefaultErrorHandler(
            (record, exception) -> { throw new IllegalStateException("financial command cannot be discarded", exception); },
            new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.setClassifications(java.util.Map.of(Exception.class, true), true);
        errors.setAckAfterHandle(false);
        errors.setRetryListeners((record, exception, attempt) -> {
            if (attempt == 1 || attempt % RETRY_LOG_INTERVAL == 0) {
                LOGGER.warn("资金消息等待恢复：topic={}, partition={}, offset={}, attempt={}",
                    record.topic(), record.partition(), record.offset(), attempt, exception);
            }
        });
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    /**
     * 创建独立平台线程调度器，隔离 Outbox 发布与异常抢占恢复。
     *
     * <p>调度池固定大小，避免 Broker 或数据库变慢时无限创建线程；优雅关闭最多等待 30 秒，让已
     * 抢占批次完成或保留为可恢复状态。</p>
     */
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(ConcurrencyProperties properties) {
        properties.validate();
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getSchedulerThreads());
        scheduler.setThreadNamePrefix("fincore-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
