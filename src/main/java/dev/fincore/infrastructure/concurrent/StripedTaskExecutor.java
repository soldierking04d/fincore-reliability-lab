package dev.fincore.infrastructure.concurrent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.util.AbstractQueue;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按业务键分片、为撤单保留容量的有界单线程执行器。
 *
 * <p><strong>解决的问题：</strong>同一交易对的新单、成交和撤单必须有确定顺序，但不同交易对需要
 * 并行，且市场洪峰不能把无限任务留在 JVM 堆中。稳定散列把相同键固定到同一 Lane；普通命令与
 * 撤单使用独立有界容量，Worker 优先撤单并以最大连续批次防止普通命令永久饥饿。</p>
 *
 * <p><strong>CPU 设计：</strong>每个 Lane 只有一个预启动平台线程，省去同交易对任务之间的线程竞争
 * 和频繁迁移；平台线程避免数据库驱动或锁协调中的 {@code synchronized} 固定虚拟线程 carrier。
 * Lane 数限制同实例 CPU 并行度，稳定数组寻址为 O(1)。队列使用 {@link ArrayDeque}，只在 Lane
 * 准入时持有短临界区锁，不在锁内执行业务或数据库调用。</p>
 *
 * <p><strong>分配与缓存边界：</strong>每次提交仍会创建 {@link CompletableFuture} 和命令包装对象，
 * 当前目标是有界、可观测和正确，不宣称零分配撮合。若未来需要微秒级内存订单簿，应使用预分配
 * 环形缓冲、对象布局和绑核专项，不能在本执行器上删除 Future 就宣称低延迟完成。</p>
 *
 * <p><strong>正确性边界：</strong>Lane 只减少单实例竞争，跨实例顺序仍由 PostgreSQL advisory lock
 * 保证。队列采用 Abort 策略，饱和时不使用 CallerRuns 或丢弃，避免越过 Lane 顺序或静默丢单。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class StripedTaskExecutor {
    /** 连续执行优先命令的最大数量，之后至少执行一个普通命令。 */
    private static final int MAX_PRIORITY_BURST = 8;
    /** 各业务键 Lane。 */
    private final ThreadPoolExecutor[] lanes;
    /** 任务排队等待时长。 */
    private final Timer queueWait;
    /** 任务执行时长。 */
    private final Timer execution;
    /** 队列饱和拒绝计数。 */
    private final Counter rejected;
    /** 撤单保留队列饱和拒绝计数。 */
    private final Counter priorityRejected;

    /**
     * 创建有界撮合执行器并注册队列、活跃线程和拒绝指标。
     *
     * @param laneCount Lane 数量
     * @param queueCapacity 每个 Lane 的排队容量
     * @param registry Micrometer 指标注册表
     */
    public StripedTaskExecutor(int laneCount, int queueCapacity, MeterRegistry registry) {
        this(laneCount, queueCapacity, Math.max(1, Math.min(32, queueCapacity / 8)), registry);
    }

    /**
     * 创建带独立撤单保留容量的有界撮合执行器。
     *
     * @param laneCount Lane 数量
     * @param queueCapacity 每个 Lane 的普通命令容量
     * @param priorityQueueCapacity 每个 Lane 的撤单保留容量
     * @param registry Micrometer 指标注册表
     */
    public StripedTaskExecutor(int laneCount, int queueCapacity, int priorityQueueCapacity,
                               MeterRegistry registry) {
        this.lanes = new ThreadPoolExecutor[laneCount];
        for (int lane = 0; lane < laneCount; lane++) {
            int laneId = lane;
            // 每条 Lane 的双队列只由一个 Worker 消费；生产者只在极短的入队临界区竞争队列锁。
            PriorityCommandQueue queue = new PriorityCommandQueue(
                queueCapacity, priorityQueueCapacity, MAX_PRIORITY_BURST);
            // core=max=1 禁止池在压力下扩线程；预启动避免首笔订单承担线程创建和栈初始化抖动。
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                queue,
                Thread.ofPlatform().name("fincore-matching-" + lane + "-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy()
            );
            executor.prestartCoreThread();
            lanes[lane] = executor;
            // Gauge 在抓取时读取队列状态，不在每次命令提交路径额外维护聚合计数器。
            Gauge.builder("fincore.matching.lane.queue.depth", executor.getQueue(), java.util.Collection::size)
                .tag("lane", Integer.toString(laneId))
                .description("撮合 Lane 当前排队任务数")
                .register(registry);
            Gauge.builder("fincore.matching.cancel.lane.queue.depth", queue,
                    PriorityCommandQueue::prioritySize)
                .tag("lane", Integer.toString(laneId))
                .description("撮合 Lane 当前撤单保留队列排队数")
                .register(registry);
            Gauge.builder("fincore.matching.lane.active", executor, ThreadPoolExecutor::getActiveCount)
                .tag("lane", Integer.toString(laneId))
                .description("撮合 Lane 当前执行任务数")
                .register(registry);
        }
        this.queueWait = registry.timer("fincore.matching.queue.wait");
        this.execution = registry.timer("fincore.matching.execution");
        this.rejected = registry.counter("fincore.matching.queue.rejected");
        this.priorityRejected = registry.counter("fincore.matching.cancel.queue.rejected");
        Gauge.builder("fincore.matching.queue.depth.total", this, StripedTaskExecutor::queuedTaskCount)
            .description("全部撮合 Lane 排队任务总数")
            .register(registry);
        Gauge.builder("fincore.matching.cancel.queue.depth.total", this,
                StripedTaskExecutor::priorityQueuedTaskCount)
            .description("全部撮合 Lane 撤单命令排队总数")
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
        return submit(key, task, false);
    }

    /**
     * 使用独立保留容量提交优先命令。
     *
     * <p>当前仅供撤单使用。它仍进入订单所属交易对的同一 Lane，因而不会绕开已经开始的撮合事务。</p>
     */
    public <T> CompletableFuture<T> submitPriority(String key, Callable<T> task) {
        return submit(key, task, true);
    }

    /** 向普通队列或撤单保留队列提交命令。 */
    private <T> CompletableFuture<T> submit(String key, Callable<T> task, boolean priority) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        // Future 用于把 Lane 线程结果交还虚拟线程；对象数受双队列容量限制，不会无界占用堆。
        CompletableFuture<T> result = new CompletableFuture<>();
        long submittedAt = System.nanoTime();
        try {
            Runnable command = () -> {
                // nanoTime 是单调时钟，只记录排队成本；不会在热路径格式化日期或日志字符串。
                queueWait.record(System.nanoTime() - submittedAt, TimeUnit.NANOSECONDS);
                Timer.Sample sample = Timer.start();
                try {
                    result.complete(task.call());
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                } finally {
                    sample.stop(execution);
                }
            };
            lanes[laneFor(key)].execute(priority ? new PriorityRunnable(command) : command);
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            (priority ? priorityRejected : rejected).increment();
            String queueName = priority ? "cancellation reserve queue" : "matching queue";
            throw new ConcurrencyRejectedException(
                queueName + " is full; query status and retry with the same idempotency key");
        }
        return result;
    }

    /** 返回全部 Lane 当前排队任务总数。 */
    public int queuedTaskCount() {
        return Arrays.stream(lanes).mapToInt(executor -> executor.getQueue().size()).sum();
    }

    /** 返回全部 Lane 当前撤单命令排队总数。 */
    public int priorityQueuedTaskCount() {
        return Arrays.stream(lanes)
            .map(ThreadPoolExecutor::getQueue)
            .map(PriorityCommandQueue.class::cast)
            .mapToInt(PriorityCommandQueue::prioritySize)
            .sum();
    }

    /**
     * 根据稳定散列选择 Lane。
     *
     * <p>异或高 16 位改善常见短字符串 hash 的低位分布；数组长度不要求为 2 的幂，因此使用
     * {@link Math#floorMod(int, int)} 保证负 hash 也落入合法下标。这里不使用加密散列，避免在每笔
     * 命令上引入不必要 CPU 成本；跨实例安全不依赖该散列。</p>
     */
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

    /** 标记应进入撤单保留队列的命令，同时保持 {@link Runnable} 执行语义。 */
    private record PriorityRunnable(Runnable delegate) implements Runnable {
        @Override
        public void run() {
            delegate.run();
        }
    }

    /**
     * 两组独立有界容量组成的阻塞队列。
     *
     * <p>该队列只决定尚未开始命令的出队次序。当前 Worker 正在执行的事务不会被撤单抢占，避免出现
     * “前一笔成交已写一半、后一笔撤单插队”的中间状态。两个 {@link ArrayDeque} 避免链表节点的
     * 逐项分配和指针追踪；单锁保护两个队列及优先批次，使一次出队只需一个一致性临界区。</p>
     */
    private static final class PriorityCommandQueue extends AbstractQueue<Runnable>
        implements BlockingQueue<Runnable> {
        /** 普通命令队列。 */
        private final ArrayDeque<Runnable> ordinary = new ArrayDeque<>();
        /** 撤单命令队列。 */
        private final ArrayDeque<Runnable> priority = new ArrayDeque<>();
        /** 普通队列容量。 */
        private final int ordinaryCapacity;
        /** 撤单队列容量。 */
        private final int priorityCapacity;
        /** 连续优先命令上限。 */
        private final int maximumPriorityBurst;
        /** 保护双队列与批次计数的锁。 */
        private final ReentrantLock lock = new ReentrantLock();
        /** 任一队列由空变为非空时通知消费者。 */
        private final Condition notEmpty = lock.newCondition();
        /** 普通队列释放容量时通知生产者。 */
        private final Condition ordinaryNotFull = lock.newCondition();
        /** 撤单队列释放容量时通知生产者。 */
        private final Condition priorityNotFull = lock.newCondition();
        /** 已连续出队的优先命令数量。 */
        private int priorityBurst;

        private PriorityCommandQueue(int ordinaryCapacity, int priorityCapacity,
                                     int maximumPriorityBurst) {
            if (ordinaryCapacity < 1 || priorityCapacity < 1) {
                throw new IllegalArgumentException("queue capacity must be positive");
            }
            this.ordinaryCapacity = ordinaryCapacity;
            this.priorityCapacity = priorityCapacity;
            this.maximumPriorityBurst = maximumPriorityBurst;
        }

        /**
         * 非阻塞入队；两类命令分别检查自己的容量。
         *
         * <p>生产请求不在这里等待容量，队满立即交给 AbortPolicy 形成明确背压，避免占住 Web
         * 虚拟线程和对象引用等待不可预测时长。</p>
         */
        @Override
        public boolean offer(Runnable command) {
            Objects.requireNonNull(command, "command");
            lock.lock();
            try {
                ArrayDeque<Runnable> target = target(command);
                if (target.size() >= capacity(command)) {
                    return false;
                }
                target.addLast(command);
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** 阻塞入队；用于完整实现 BlockingQueue 契约。 */
        @Override
        public void put(Runnable command) throws InterruptedException {
            Objects.requireNonNull(command, "command");
            lock.lockInterruptibly();
            try {
                ArrayDeque<Runnable> target = target(command);
                Condition notFull = notFull(command);
                while (target.size() >= capacity(command)) {
                    notFull.await();
                }
                target.addLast(command);
                notEmpty.signal();
            } finally {
                lock.unlock();
            }
        }

        /** 在限定时长内等待对应命令队列出现容量。 */
        @Override
        public boolean offer(Runnable command, long timeout, TimeUnit unit)
            throws InterruptedException {
            Objects.requireNonNull(command, "command");
            long remaining = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                ArrayDeque<Runnable> target = target(command);
                Condition notFull = notFull(command);
                while (target.size() >= capacity(command)) {
                    if (remaining <= 0) {
                        return false;
                    }
                    remaining = notFull.awaitNanos(remaining);
                }
                target.addLast(command);
                notEmpty.signal();
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** 等待并获取下一条命令。 */
        @Override
        public Runnable take() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (isEmptyUnsafe()) {
                    notEmpty.await();
                }
                return dequeue();
            } finally {
                lock.unlock();
            }
        }

        /** 在限定时长内等待下一条命令。 */
        @Override
        public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            long remaining = unit.toNanos(timeout);
            lock.lockInterruptibly();
            try {
                while (isEmptyUnsafe()) {
                    if (remaining <= 0) {
                        return null;
                    }
                    remaining = notEmpty.awaitNanos(remaining);
                }
                return dequeue();
            } finally {
                lock.unlock();
            }
        }

        /** 非阻塞获取下一条命令。 */
        @Override
        public Runnable poll() {
            lock.lock();
            try {
                return isEmptyUnsafe() ? null : dequeue();
            } finally {
                lock.unlock();
            }
        }

        /** 查看下一条命令但不移除。 */
        @Override
        public Runnable peek() {
            lock.lock();
            try {
                if (shouldSelectPriorityUnsafe()) {
                    return priority.peekFirst();
                }
                return ordinary.peekFirst();
            } finally {
                lock.unlock();
            }
        }

        /** 返回两组队列尚可接收的总命令数。 */
        @Override
        public int remainingCapacity() {
            lock.lock();
            try {
                return ordinaryCapacity - ordinary.size() + priorityCapacity - priority.size();
            } finally {
                lock.unlock();
            }
        }

        /** 返回普通与撤单队列的命令总数。 */
        @Override
        public int size() {
            lock.lock();
            try {
                return ordinary.size() + priority.size();
            } finally {
                lock.unlock();
            }
        }

        /** 返回当前撤单保留队列深度。 */
        private int prioritySize() {
            lock.lock();
            try {
                return priority.size();
            } finally {
                lock.unlock();
            }
        }

        /** 返回弱一致快照迭代器，供执行器关闭与诊断使用。 */
        @Override
        public Iterator<Runnable> iterator() {
            lock.lock();
            try {
                java.util.ArrayList<Runnable> snapshot = new java.util.ArrayList<>(size());
                snapshot.addAll(priority);
                snapshot.addAll(ordinary);
                return snapshot.iterator();
            } finally {
                lock.unlock();
            }
        }

        /** 按对象移除尚未执行的命令。 */
        @Override
        public boolean remove(Object command) {
            lock.lock();
            try {
                boolean removedPriority = priority.remove(command);
                boolean removedOrdinary = ordinary.remove(command);
                if (removedPriority) {
                    priorityNotFull.signal();
                }
                if (removedOrdinary) {
                    ordinaryNotFull.signal();
                }
                return removedPriority || removedOrdinary;
            } finally {
                lock.unlock();
            }
        }

        /** 将当前命令全部转移到目标集合。 */
        @Override
        public int drainTo(Collection<? super Runnable> target) {
            return drainTo(target, Integer.MAX_VALUE);
        }

        /** 按优先出队规则最多转移指定数量的命令。 */
        @Override
        public int drainTo(Collection<? super Runnable> target, int maximum) {
            Objects.requireNonNull(target, "target");
            if (target == this) {
                throw new IllegalArgumentException("cannot drain queue to itself");
            }
            lock.lock();
            try {
                int transferred = 0;
                while (transferred < maximum && !isEmptyUnsafe()) {
                    target.add(dequeue());
                    transferred++;
                }
                return transferred;
            } finally {
                lock.unlock();
            }
        }

        /** 根据命令标记选择普通或撤单队列。 */
        private ArrayDeque<Runnable> target(Runnable command) {
            return command instanceof PriorityRunnable ? priority : ordinary;
        }

        /** 返回命令所属队列的容量。 */
        private int capacity(Runnable command) {
            return command instanceof PriorityRunnable ? priorityCapacity : ordinaryCapacity;
        }

        /** 返回命令所属队列的容量条件。 */
        private Condition notFull(Runnable command) {
            return command instanceof PriorityRunnable ? priorityNotFull : ordinaryNotFull;
        }

        /** 锁内判断双队列是否均为空。 */
        private boolean isEmptyUnsafe() {
            return ordinary.isEmpty() && priority.isEmpty();
        }

        /**
         * 锁内判断下一条是否应选择撤单队列。
         *
         * <p>有撤单且尚未达到连续配额时优先撤单；普通队列为空时即使达到配额也继续处理撤单，
         * 避免为了形式公平让 Lane 空转。</p>
         */
        private boolean shouldSelectPriorityUnsafe() {
            if (priority.isEmpty()) {
                return false;
            }
            return priorityBurst < maximumPriorityBurst || ordinary.isEmpty();
        }

        /**
         * 锁内按撤单优先规则出队，并在连续八笔撤单后让一个普通命令前进。
         */
        private Runnable dequeue() {
            // 优先判断只做常量次数分支，不扫描队列；撤单最多连续推进八条后让普通命令前进。
            if (shouldSelectPriorityUnsafe()) {
                priorityBurst++;
                Runnable command = priority.removeFirst();
                priorityNotFull.signal();
                return command;
            }
            priorityBurst = 0;
            Runnable command = ordinary.removeFirst();
            ordinaryNotFull.signal();
            return command;
        }
    }
}
