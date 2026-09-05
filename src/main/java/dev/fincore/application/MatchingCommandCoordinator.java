package dev.fincore.application;

import dev.fincore.domain.MatchingResult;
import dev.fincore.domain.OrderBookView;
import dev.fincore.domain.OrderView;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.TradeView;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.infrastructure.concurrent.ConcurrencyTimeoutException;
import dev.fincore.infrastructure.concurrent.StripedTaskExecutor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * 撮合命令的并发准入与交易对 Lane 协调器。
 *
 * <p><strong>解决的问题：</strong>把新单和撤单送入同一交易对顺序域，并在普通队列饱和时为撤单
 * 保留容量；查询不进入写 Lane，避免慢查询阻塞下单。</p>
 *
 * <p><strong>线程与 CPU：</strong>调用方通常是虚拟线程，实际写事务在固定 Lane 平台线程执行。
 * 相同 symbol 不会由多个本地线程同时争抢数据库锁，不同 symbol 才并行使用 CPU；等待 Future
 * 不消耗一条专用 Tomcat 平台线程。撤单只优先于尚未开始的任务，不能抢占正在提交的事务。</p>
 *
 * <p><strong>正确性边界：</strong>Lane 只减少本实例的 PostgreSQL advisory lock 无效竞争，数据库锁
 * 继续负责跨实例串行化。HTTP 等待超时不取消已开始事务，客户端必须用原
 * {@code clientOrderId} 查询或重试。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
@Service
public class MatchingCommandCoordinator {
    /** 保证金融事务与撮合顺序的应用服务。 */
    private final MatchingService matching;
    /** 按交易对分片的有界执行器。 */
    private final StripedTaskExecutor executor;
    /** Web 请求最多等待撮合结果的时间。 */
    private final long waitTimeoutNanos;

    /** 创建撮合并发协调器。 */
    public MatchingCommandCoordinator(MatchingService matching, StripedTaskExecutor executor,
                                      ConcurrencyProperties properties) {
        this.matching = matching;
        this.executor = executor;
        this.waitTimeoutNanos = properties.getMatchingWaitTimeout().toNanos();
    }

    /** 按规范化交易对串行提交新订单；队列饱和由执行器明确拒绝，不在调用方线程执行。 */
    public MatchingResult place(PlaceOrderCommand command) {
        return await(executor.submit(command.symbol(), () -> matching.place(command)));
    }

    /**
     * 按订单所属交易对串行执行撤单。
     *
     * <p>第一次只读查询用于找到 symbol，不代表撤单成功；真正状态仍由 Lane 内事务重新加锁核验。</p>
     */
    public OrderView cancel(UUID orderId, String userId) {
        OrderView snapshot = matching.get(orderId);
        return await(executor.submitPriority(snapshot.symbol(), () -> matching.cancel(orderId, userId)));
    }

    /** 查询订单快照，不占用撮合写 Lane。 */
    public OrderView get(UUID orderId) {
        return matching.get(orderId);
    }

    /** 查询聚合订单簿，不占用撮合写 Lane。 */
    public OrderBookView book(String symbol, int depth) {
        return matching.book(symbol, depth);
    }

    /** 查询最近成交，不占用撮合写 Lane。 */
    public List<TradeView> recentTrades(String symbol, int limit) {
        return matching.recentTrades(symbol, limit);
    }

    /**
     * 等待任务结果并保留原始领域异常语义。
     *
     * <p>纳秒时长在构造时预计算，避免每次请求重复做 Duration 转换。Future 等待超时只释放 HTTP
     * 调用方，不调用 cancel(true)，因为任务可能已跨过数据库提交点。</p>
     */
    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(waitTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new ConcurrencyTimeoutException(
                "matching result timed out; query or retry with the same idempotency key",
                exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyTimeoutException("matching wait interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("matching task failed", exception.getCause());
        }
    }
}
