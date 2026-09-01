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
 * <p>写命令按交易对进入同一有界 Lane，减少同一实例内对 PostgreSQL advisory lock 的无效竞争；
 * 数据库锁继续负责跨实例串行化。查询不进入写队列，避免慢查询阻塞下单。HTTP 等待超时时不取消
 * 已开始的事务，客户端必须用原 {@code clientOrderId} 查询或重试。</p>
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

    /** 按规范化交易对串行提交新订单。 */
    public MatchingResult place(PlaceOrderCommand command) {
        return await(executor.submit(command.symbol(), () -> matching.place(command)));
    }

    /** 按订单所属交易对串行执行撤单。 */
    public OrderView cancel(UUID orderId, String userId) {
        OrderView snapshot = matching.get(orderId);
        return await(executor.submit(snapshot.symbol(), () -> matching.cancel(orderId, userId)));
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

    /** 等待任务结果并保留原始领域异常语义。 */
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
