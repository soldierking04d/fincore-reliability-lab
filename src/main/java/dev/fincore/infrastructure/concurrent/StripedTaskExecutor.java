package dev.fincore.infrastructure.concurrent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 按业务键分片的有界单线程执行器。
 *
 * <p>相同键始终进入同一 Lane 并严格串行，不同键可由多个 Lane 并行处理。撮合线程显式使用平台
 * 线程，避免数据库驱动或锁协调中的 {@code synchronized} 导致虚拟线程固定载体线程。队列采用
 * Abort 策略，饱和时不会调用方运行或静默丢弃，以免破坏同一键顺序。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class StripedTaskExecutor {
    /** 各业务键 Lane。 */
    private final ThreadPoolExecutor[] lanes;
    /** 任务排队等待时长。 */
    private final Timer queueWait;
    /** 任务执行时长。 */
    private final Timer execution;
    /** 队列饱和拒绝计数。 */
    private final Counter rejected;

    /**
     * 创建有界撮合执行器并注册队列、活跃线程和拒绝指标。
     *
     * @param laneCount Lane 数量
     * @param queueCapacity 每个 Lane 的排队容量
     * @param registry Micrometer 指标注册表
     */
    public StripedTaskExecutor(int laneCount, int queueCapacity, MeterRegistry registry) {
        this.lanes = new ThreadPoolExecutor[laneCount];
        for (int lane = 0; lane < laneCount; lane++) {
            int laneId = lane;
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("fincore-matching-" + lane + "-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
            );
            executor.prestartCoreThread();
            lanes[lane] = executor;
            Gauge.builder("fincore.matching.lane.queue.depth", executor.getQueue(), java.util.Collection::size)
                .tag("lane", Integer.toString(laneId))
                .description("撮合 Lane 当前排队任务数")
                .register(registry);
            Gauge.builder("fincore.matching.lane.active", executor, ThreadPoolExecutor::getActiveCount)
                .tag("lane", Integer.toString(laneId))
                .description("撮合 Lane 当前执行任务数")
                .register(registry);
        }
        this.queueWait = registry.timer("fincore.matching.queue.wait");
        this.execution = registry.timer("fincore.matching.execution");
        this.rejected = registry.counter("fincore.matching.queue.rejected");
        Gauge.builder("fincore.matching.queue.depth.total", this, StripedTaskExecutor::queuedTaskCount)
            .description("全部撮合 Lane 排队任务总数")
            .register(registry);
    }

    /**
     * 按业务键提交任务。
     *
     * @param key 决定 Lane 的稳定业务键
     * @param task 实际业务任务
     * @param <T> 结果类型
     * @return 可等待的任务结果
     * @throws ConcurrencyRejectedException 对应 Lane 已饱和时抛出
     */
    public <T> CompletableFuture<T> submit(String key, Callable<T> task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        CompletableFuture<T> result = new CompletableFuture<>();
        long submittedAt = System.nanoTime();
        try {
            lanes[laneFor(key)].execute(() -> {
                queueWait.record(System.nanoTime() - submittedAt, TimeUnit.NANOSECONDS);
                Timer.Sample sample = Timer.start();
                try {
                    result.complete(task.call());
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                } finally {
                    sample.stop(execution);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            rejected.increment();
            throw new ConcurrencyRejectedException("matching queue is full; retry with the same idempotency key");
        }
        return result;
    }

    /** 返回全部 Lane 当前排队任务总数。 */
    public int queuedTaskCount() {
        return Arrays.stream(lanes).mapToInt(executor -> executor.getQueue().size()).sum();
    }

    /** 根据稳定散列选择 Lane。 */
    int laneFor(String key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return Math.floorMod(hash, lanes.length);
    }

    /** 应用关闭时停止接收新任务并等待已提交任务完成。 */
    @PreDestroy
    public void shutdown() {
        for (ThreadPoolExecutor executor : lanes) {
            executor.shutdown();
        }
        for (ThreadPoolExecutor executor : lanes) {
            try {
                if (!executor.awaitTermination(20, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
}
