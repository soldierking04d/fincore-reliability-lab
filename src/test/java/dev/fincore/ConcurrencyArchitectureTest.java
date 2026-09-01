package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 高并发线程模型、批处理和 JVM 运行基线的源码架构守卫。 */
class ConcurrencyArchitectureTest {

    /** Java 21、虚拟线程和显式平台线程隔离必须同时存在。 */
    @Test
    void java21UsesVirtualThreadsOnlyOutsidePinnedSubsystems() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String configuration = Files.readString(Path.of(
            "src/main/java/dev/fincore/infrastructure/concurrent/ConcurrencyConfiguration.java"));
        String listener = Files.readString(Path.of(
            "src/main/java/dev/fincore/messaging/SettlementListener.java"));

        assertTrue(pom.contains("<java.version>21</java.version>"));
        assertTrue(application.contains("virtual:\n      enabled: true"));
        assertTrue(configuration.contains("ThreadPoolTaskExecutor"));
        assertTrue(configuration.contains("setListenerTaskExecutor"));
        assertTrue(configuration.contains("VirtualThreadMetrics"));
        assertTrue(listener.contains("settlementKafkaListenerContainerFactory"));
        assertTrue(application.contains("max.block.ms: 1000"));
    }

    /** 撮合必须使用有界队列和拒绝策略，禁止无界任务积压。 */
    @Test
    void matchingAdmissionIsBoundedAndKeyed() throws IOException {
        String executor = Files.readString(Path.of(
            "src/main/java/dev/fincore/infrastructure/concurrent/StripedTaskExecutor.java"));
        String coordinator = Files.readString(Path.of(
            "src/main/java/dev/fincore/application/MatchingCommandCoordinator.java"));

        assertTrue(executor.contains("ArrayBlockingQueue"));
        assertTrue(executor.contains("AbortPolicy"));
        assertFalse(executor.contains("LinkedBlockingQueue"));
        assertTrue(coordinator.contains("command.symbol()"));
        assertTrue(coordinator.contains("same idempotency key"));
    }

    /** Outbox 必须异步批量发送并批量回写，不能退回逐条同步等待。 */
    @Test
    void outboxUsesBoundedAsyncBatching() throws IOException {
        String publisher = Files.readString(Path.of(
            "src/main/java/dev/fincore/messaging/OutboxPublisher.java"));
        String mapper = Files.readString(Path.of(
            "src/main/java/dev/fincore/infrastructure/persistence/mapper/OutboxMapper.java"));

        assertTrue(publisher.contains("CompletableFuture.allOf"));
        assertTrue(mapper.contains("markPublishedBatch"));
        assertTrue(mapper.contains("releaseForRetryBatch"));
        assertTrue(mapper.contains("FOR UPDATE SKIP LOCKED"));
        String uuidBinding = "#{eventId,javaType=java.util.UUID,jdbcType=OTHER,"
            + "typeHandler=dev.fincore.infrastructure.persistence.type.PostgresUuidTypeHandler}";
        assertTrue(mapper.indexOf(uuidBinding) != mapper.lastIndexOf(uuidBinding),
            "Outbox 成功与失败批量回写都必须显式绑定 PostgreSQL UUID");
    }

    /** 容器 JVM 必须限制堆比例、输出 GC/JFR 证据并在 OOM 时退出。 */
    @Test
    void runtimeProfilesKeepGcDiagnosticsAndContainerLimits() throws IOException {
        String g1 = Files.readString(Path.of("config/jvm/g1.options"));
        String zgc = Files.readString(Path.of("config/jvm/zgc.options"));
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(g1.contains("UseG1GC"));
        assertTrue(zgc.contains("UseZGC"));
        assertTrue(g1.contains("MaxRAMPercentage"));
        assertTrue(g1.contains("StartFlightRecording"));
        assertTrue(g1.contains("-Xlog:gc"));
        assertTrue(g1.contains("ExitOnOutOfMemoryError"));
        assertTrue(dockerfile.contains("eclipse-temurin:21-jre"));
    }
}
