import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用真实单线程池固定复用同一线程，验证失败请求之后的租户上下文隔离。
 * 这是 Java 业务上下文泄漏示例，不是 PHP 内存损坏漏洞的复现。
 * 兼容 Java 8 及以上；不处理 JVM OOM、进程终止或跨线程传播。
 */
public final class ThreadLocalIsolationDemo {
    private static final ThreadLocal<String> TENANT = new ThreadLocal<String>();

    private ThreadLocalIsolationDemo() {
    }

    /** 绑定范围覆盖完整同步操作；异常同样经过 finally，不能返回伪成功。 */
    private static <T> T inRequest(String tenant, Callable<T> action) throws Exception {
        if (TENANT.get() != null) {
            throw new IllegalStateException("请求进入前发现旧上下文");
        }
        TENANT.set(tenant);
        try {
            return action.call();
        } finally {
            TENANT.remove();
        }
    }

    public static void main(String[] args) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // 负例：同一 Worker 中，异常退出后没有解除绑定，下一任务仍能读到租户 A。
            try {
                worker.submit(() -> { TENANT.set("tenant-A"); throw new IllegalStateException("受控失败"); }).get();
                throw new AssertionError("失败任务不应成功");
            } catch (ExecutionException expected) {
                if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
            }
            String leaked = worker.submit(() -> TENANT.get()).get();
            if (!"tenant-A".equals(leaked)) throw new AssertionError("负例未在同一线程复现");
            worker.submit(() -> TENANT.remove()).get();

            // 修正：业务异常继续向上传播，且下一次匿名任务不能继承身份。
            try {
                worker.submit(() -> inRequest("tenant-A", () -> { throw new IllegalStateException("受控失败"); })).get();
                throw new AssertionError("业务异常被吞掉");
            } catch (ExecutionException expected) {
                if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
            }
            if (worker.submit(() -> TENANT.get()).get() != null) throw new AssertionError("失败路径残留租户");
            String current = worker.submit(() -> inRequest("tenant-B", () -> TENANT.get())).get();
            if (!"tenant-B".equals(current)) throw new AssertionError("当前租户绑定错误");
            if (worker.submit(() -> TENANT.get()).get() != null) throw new AssertionError("成功路径残留租户");
            System.out.println("PASS: negative leak reproduced; success/failure paths isolate the next request.");
        } finally {
            worker.shutdownNow();
        }
    }
}
