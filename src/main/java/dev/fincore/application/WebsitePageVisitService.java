package dev.fincore.application;

import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper;
import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper.Visit;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 非关键访问日志尽力收集：入口只入有界队列，数据库失败或队满计数并丢弃，不重试。
 *
 * <p>共用现有小型调度器，固定延迟保证同一实例的批次不重叠；多实例去重交给数据库。
 * 进程退出时内存事件可能丢失；HTTP 202 仅表示已入队，不承诺持久化成功。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-24
 */
@Service
public class WebsitePageVisitService {
    /** 内存上限固定，不能由请求或环境参数放大。 */
    private static final int QUEUE_CAPACITY = 256;
    /** 单条 SQL 的写入上限。 */
    private static final int BATCH_SIZE = 32;
    /** 每轮清理上限，禁止循环清空历史数据。 */
    private static final int DELETE_LIMIT = 1024;
    /** 日志保留时间配置上限。 */
    private static final int MAX_RETENTION_DAYS = 365;
    /** 非阻塞入队，队满即丢弃。 */
    private final ArrayBlockingQueue<Visit> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** 独立日志表的持久化接口。 */
    private final WebsitePageVisitMapper mapper;
    /** 指标仅使用固定标签，绝不携带 IP 或浏览信息。 */
    private final MeterRegistry metrics;
    /** 默认三十天保留期。 */
    private final int retentionDays;

    /** 创建有界收集器并拒绝无效保留期。 */
    public WebsitePageVisitService(WebsitePageVisitMapper mapper, MeterRegistry metrics,
                                  @Value("${fincore.analytics.retention-days:30}") int retentionDays) {
        if (retentionDays < 1 || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("analytics retention days must be between 1 and 365");
        }
        this.mapper = mapper;
        this.metrics = metrics;
        this.retentionDays = retentionDays;
    }

    /** 请求线程不访问数据库；队满不会阻塞网页或资金操作。 */
    public boolean accept(Visit visit) {
        if (!queue.offer(visit)) {
            metrics.counter("fincore.analytics.dropped", "reason", "queue_full").increment();
            return false;
        }
        return true;
    }

    /** 单次取出一个有界批次；失败不重试，不把采集失败误报为已持久化。 */
    @Scheduled(fixedDelay = 1000)
    public void drain() {
        List<Visit> batch = new ArrayList<>(BATCH_SIZE);
        queue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        try {
            metrics.counter("fincore.analytics.persisted").increment(mapper.insertBatch(batch));
        } catch (RuntimeException exception) {
            // 访问日志允许丢失；不能打印可能包含参数值的数据库异常。
            metrics.counter("fincore.analytics.dropped", "reason", "database").increment(batch.size());
        }
    }

    /** 每分钟只清理一小批，失败留待下一轮；清理不占用 HTTP 请求线程。 */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void deleteExpired() {
        try {
            mapper.deleteExpired(retentionDays, DELETE_LIMIT);
        } catch (RuntimeException exception) {
            // 只记录低基数指标，不泄露日志内容或数据库错误参数。
            metrics.counter("fincore.analytics.cleanup.failures").increment();
        }
    }
}
