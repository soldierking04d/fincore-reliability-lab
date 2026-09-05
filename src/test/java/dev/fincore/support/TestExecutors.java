package dev.fincore.support;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 测试专用的显式有界线程池工厂。
 *
 * <p>线程数量、队列容量、拒绝策略和名称全部可见，避免测试代码使用 {@code Executors} 默认
 * 工厂后掩盖无界队列，也让失败报告能定位到具体并发场景。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.4.0
 */
public final class TestExecutors {
    /** 足以容纳当前有限测试夹具，同时保持硬上限。 */
    private static final int TEST_QUEUE_CAPACITY = 1_024;

    /** 工具类不允许实例化。 */
    private TestExecutors() {
        throw new IllegalStateException("utility class");
    }

    /**
     * 创建固定平台线程数和有界等待队列的测试执行器。
     *
     * @param threads 平台线程数
     * @param namePrefix 线程名称前缀
     * @return 必须由测试关闭的执行器
     */
    public static ExecutorService fixedThreadPool(int threads, String namePrefix) {
        if (threads < 1) {
            throw new IllegalArgumentException("测试线程数必须大于零");
        }
        ThreadFactory factory = namedFactory(namePrefix);
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(TEST_QUEUE_CAPACITY), factory,
            new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 创建单线程测试调度器。
     *
     * @param namePrefix 线程名称前缀
     * @return 必须由测试关闭的调度器
     */
    public static ScheduledExecutorService singleThreadScheduler(String namePrefix) {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(
            1, namedFactory(namePrefix), new ThreadPoolExecutor.AbortPolicy());
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** 按前缀创建可诊断的平台线程工厂。 */
    private static ThreadFactory namedFactory(String namePrefix) {
        Objects.requireNonNull(namePrefix, "namePrefix");
        if (namePrefix.isBlank()) {
            throw new IllegalArgumentException("测试线程名称前缀不能为空");
        }
        return Thread.ofPlatform().name(namePrefix, 0L).factory();
    }
}
