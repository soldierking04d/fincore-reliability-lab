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
 * <p>Spring MVC 使用 Java 21 虚拟线程承接大量短时阻塞请求；撮合 Lane、Kafka Consumer 和
 * 定时任务显式隔离到有界平台线程。这样既降低 I/O 等待线程的内存成本，也避免 Kafka 客户端或
 * 数据库锁内部同步代码固定虚拟线程载体。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConcurrencyProperties.class)
public class ConcurrencyConfiguration {
    /** 重试日志不打印消息载荷，首次及每分钟保留原因供定位。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(ConcurrencyConfiguration.class);
    /** 注册虚拟线程固定载体和提交失败指标，应用关闭时同时关闭内部 JFR 流。 */
    @Bean(destroyMethod = "close")
    VirtualThreadMetrics virtualThreadMetrics() {
        return new VirtualThreadMetrics();
    }

    /** 创建按交易对分片的有界撮合执行器。 */
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
     * 并发数完全一致，不使用无界任务队列。</p>
     */
    @Bean(name = "settlementKafkaConsumerExecutor")
    ThreadPoolTaskExecutor settlementKafkaConsumerExecutor(ConcurrencyProperties properties) {
        properties.validate();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
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

    /** 创建使用平台线程的 Kafka Listener 容器，避免继承全局虚拟线程执行器。 */
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
            if (attempt == 1 || attempt % 60 == 0) {
                LOGGER.warn("资金消息等待恢复：topic={}, partition={}, offset={}, attempt={}",
                    record.topic(), record.partition(), record.offset(), attempt, exception);
            }
        });
        factory.setCommonErrorHandler(errors);
        return factory;
    }

    /** 创建独立平台线程调度器，隔离 Outbox 发布与异常抢占恢复。 */
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
