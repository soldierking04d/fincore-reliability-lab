package dev.fincore.infrastructure.concurrent;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 集中创建只用于实验编排的 Java 21 虚拟线程执行器。
 *
 * <p><strong>解决的问题：</strong>实验场景需要大量短生命周期、以等待数据库和闩锁为主的任务；
 * 每任务一个虚拟线程能降低平台线程和栈内存占用，同时把命名规则集中到一个入口。</p>
 *
 * <p><strong>CPU 与资源边界：</strong>虚拟线程不是 CPU 并行度放大器，也没有传统线程池队列容量。
 * 生产请求仍必须先经过撮合 Lane、数据库连接池和 Kafka 并发度等有界准入；本工厂只允许用于
 * {@code lab} 场景和离线模拟，并要求调用方通过 try-with-resources 或 finally 关闭。</p>
 *
 * <p><strong>P3C 兼容说明：</strong>Alibaba P3C 2.1.1 发布时尚无 Java 21 虚拟线程，会把所有
 * {@link Executors} 工厂调用一概判为传统无界线程池。此处仅在工厂方法定向抑制
 * {@code PMD.ThreadPoolCreationRule}，其余代码禁止直接创建执行器；线程命名和调用边界由本类补足。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.4.0
 */
public final class VirtualTaskExecutors {

    /** 工具类不允许实例化。 */
    private VirtualTaskExecutors() {
        throw new IllegalStateException("utility class");
    }

    /**
     * 创建带场景前缀的每任务虚拟线程执行器。
     *
     * @param threadNamePrefix 非空且以短横线结尾的线程名称前缀
     * @return 必须由调用方关闭的虚拟线程执行器
     */
    @SuppressWarnings("PMD.ThreadPoolCreationRule")
    public static ExecutorService newPerTaskExecutor(String threadNamePrefix) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        if (threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("虚拟线程名称前缀不能为空");
        }
        ThreadFactory factory = Thread.ofVirtual().name(threadNamePrefix, 0L).factory();
        return Executors.newThreadPerTaskExecutor(factory);
    }
}
