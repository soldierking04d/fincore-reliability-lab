package dev.fincore.application;

import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.infrastructure.concurrent.ConcurrencyProperties;
import dev.fincore.infrastructure.concurrent.ConcurrencyTimeoutException;
import dev.fincore.infrastructure.concurrent.StripedTaskExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * 完整交易入口的有界并发协调器。
 *
 * <p>用户、风控、账户、行情与撮合按照交易对进入同一有界 Lane，避免前置检查绕过撮合的过载保护。
 * 跨实例的最终顺序仍由数据库风控行锁和撮合 advisory lock 保证。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.2.0
 */
@Service
public class TradingOrderCoordinator {
    /** 完整交易生命周期服务。 */
    private final TradingLifecycleService tradingLifecycleService;
    /** 按交易对分片的有界执行器。 */
    private final StripedTaskExecutor executor;
    /** HTTP 请求等待完整业务结果的最大时间。 */
    private final long waitTimeoutNanos;

    /** 创建完整交易入口协调器。 */
    public TradingOrderCoordinator(TradingLifecycleService tradingLifecycleService,
                                   StripedTaskExecutor executor,
                                   ConcurrencyProperties properties) {
        this.tradingLifecycleService = tradingLifecycleService;
        this.executor = executor;
        this.waitTimeoutNanos = properties.getMatchingWaitTimeout().toNanos();
    }

    /** 按交易对串行执行盘前检查和撮合。 */
    public TradingLifecycleService.TradingOrderResult place(PlaceOrderCommand command) {
        return await(executor.submit(command.symbol(), () -> tradingLifecycleService.place(command)));
    }

    /** 等待执行结果并保留原始领域异常。 */
    private <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(waitTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new ConcurrencyTimeoutException(
                "trading result timed out; query or retry with the same clientOrderId",
                exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ConcurrencyTimeoutException("trading wait interrupted", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("trading task failed", exception.getCause());
        }
    }
}
