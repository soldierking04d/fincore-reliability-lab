package dev.fincore.infrastructure.concurrent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 按业务键分片的有界撮合执行器并发契约测试。 */
class StripedTaskExecutorTest {
    /** 用于证明同键串行顺序的任务数量。 */
    private static final int SERIAL_TASK_COUNT = 20;

    /** 相同业务键必须严格串行，并固定使用平台线程。 */
    @Test
    void sameKeyRunsSerially() throws Exception {
        StripedTaskExecutor executor = new StripedTaskExecutor(2, 32, new SimpleMeterRegistry());
        try {
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximumActive = new AtomicInteger();
            AtomicBoolean virtualThreadObserved = new AtomicBoolean();
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());
            List<CompletableFuture<Integer>> futures = new ArrayList<>();

            for (int index = 0; index < SERIAL_TASK_COUNT; index++) {
                int sequence = index;
                futures.add(executor.submit("BTC-USDT", () -> {
                    int current = active.incrementAndGet();
                    maximumActive.accumulateAndGet(current, Math::max);
                    virtualThreadObserved.compareAndSet(false, Thread.currentThread().isVirtual());
                    order.add(sequence);
                    active.decrementAndGet();
                    return sequence;
                }));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);

            assertEquals(1, maximumActive.get());
            assertEquals(java.util.stream.IntStream.range(0, SERIAL_TASK_COUNT).boxed().toList(), order);
            assertFalse(virtualThreadObserved.get(), "撮合 Lane 必须使用平台线程");
        } finally {
            executor.shutdown();
        }
    }

    /** 不同 Lane 能够同时执行，避免冷门交易对被热点交易对阻塞。 */
    @Test
    void differentLanesExecuteInParallel() throws Exception {
        StripedTaskExecutor executor = new StripedTaskExecutor(2, 8, new SimpleMeterRegistry());
        try {
            String firstKey = "symbol-0";
            String secondKey = java.util.stream.IntStream.range(1, 1_000)
                .mapToObj(value -> "symbol-" + value)
                .filter(key -> executor.laneFor(key) != executor.laneFor(firstKey))
                .findFirst()
                .orElseThrow();
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            CompletableFuture<Boolean> first = executor.submit(firstKey, () -> awaitBoth(entered, release));
            CompletableFuture<Boolean> second = executor.submit(secondKey, () -> awaitBoth(entered, release));

            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS), "不同 Lane 应同时开始执行");
            } finally {
                release.countDown();
            }
            assertTrue(first.get(1, TimeUnit.SECONDS));
            assertTrue(second.get(1, TimeUnit.SECONDS));
        } finally {
            executor.shutdown();
        }
    }

    /** Lane 正在执行且队列已满时必须明确拒绝第三个任务。 */
    @Test
    void saturatedLaneRejectsInsteadOfGrowingWithoutBound() throws Exception {
        StripedTaskExecutor executor = new StripedTaskExecutor(1, 1, new SimpleMeterRegistry());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Boolean> running = executor.submit("ETH-USDT", () -> {
                started.countDown();
                return release.await(2, TimeUnit.SECONDS);
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            CompletableFuture<Integer> queued = executor.submit("ETH-USDT", () -> 2);

            assertThrows(ConcurrencyRejectedException.class,
                () -> executor.submit("ETH-USDT", () -> 3));
            try {
                assertEquals(1, executor.queuedTaskCount());
            } finally {
                release.countDown();
            }
            assertTrue(running.get(1, TimeUnit.SECONDS));
            assertEquals(2, queued.get(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /**
     * 普通下单队列已经饱和时，撤单仍应使用独立保留容量进入同一 Lane，并在当前任务结束后
     * 先于普通积压执行。这样既不破坏交易对内的单线程顺序，也不会让用户在行情波动时因
     * 新单洪峰而失去退出风险的通道。
     */
    @Test
    void cancellationUsesReservedCapacityAndOvertakesOrdinaryBacklog() throws Exception {
        StripedTaskExecutor executor = new StripedTaskExecutor(1, 1, 1, new SimpleMeterRegistry());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());
        try {
            CompletableFuture<String> running = executor.submit("BTC-USDT", () -> {
                started.countDown();
                release.await(2, TimeUnit.SECONDS);
                executionOrder.add("running-order");
                return "running-order";
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            CompletableFuture<String> queuedOrder = executor.submit("BTC-USDT", () -> {
                executionOrder.add("queued-order");
                return "queued-order";
            });

            assertThrows(ConcurrencyRejectedException.class,
                () -> executor.submit("BTC-USDT", () -> "overflow-order"));
            CompletableFuture<String> cancellation = executor.submitPriority("BTC-USDT", () -> {
                executionOrder.add("cancellation");
                return "cancellation";
            });

            try {
                assertEquals(2, executor.queuedTaskCount());
            } finally {
                release.countDown();
            }
            assertEquals("running-order", running.get(1, TimeUnit.SECONDS));
            assertEquals("cancellation", cancellation.get(1, TimeUnit.SECONDS));
            assertEquals("queued-order", queuedOrder.get(1, TimeUnit.SECONDS));
            assertEquals(List.of("running-order", "cancellation", "queued-order"), executionOrder);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    /** 等待两个 Lane 都进入任务后再统一放行。 */
    private static boolean awaitBoth(CountDownLatch entered, CountDownLatch release)
        throws InterruptedException {
        entered.countDown();
        return release.await(2, TimeUnit.SECONDS);
    }
}
