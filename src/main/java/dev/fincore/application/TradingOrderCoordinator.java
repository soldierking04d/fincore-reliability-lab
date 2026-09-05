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
 * <p><strong>解决的问题：</strong>盘前检查与撮合必须共享同一过载合同，不能先让所有请求打满用户、
 * 行情和账户数据库，再到撮合层才限流。</p>
 *
 * <p><strong>线程与 CPU：</strong>Web 虚拟线程只负责提交和等待；用户、风控、账户和撮合事务在
 * symbol 对应的固定平台线程执行。同标的串行降低锁竞争，不同标的并行；固定超时预先转为纳秒，
 * 请求路径不重复分配 Duration 转换对象。</p>
 *
 * <p><strong>正确性边界：</strong>本地 Lane 不代替数据库。跨实例最终顺序仍由风控行锁和撮合
 * advisory lock 保证；超时结果未知时必须复用原 {@code clientOrderId} 查询或重试。</p>
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

    /** 按交易对串行执行盘前检查和撮合，使准入、决定、订单与资金预占共享背压。 */
    public TradingLifecycleService.TradingOrderResult place(PlaceOrderCommand command) {
        return await(executor.submit(command.symbol(), () -> tradingLifecycleService.place(command)));
    }

    /** 等待执行结果并保留原始领域异常；超时不尝试中断已经开始的金融事务。 */
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
