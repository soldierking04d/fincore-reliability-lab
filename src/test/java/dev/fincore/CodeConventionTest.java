package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Java 文档契约与基础格式的自动回归测试。
 *
 * <p>该测试随 Maven 测试阶段执行，防止后续新增生产类型时遗漏 Javadoc、作者、版本或包说明。
 * 更细的本地检查仍由 scripts/verify-code-conventions.sh 提供。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
class CodeConventionTest {

    /** 生产 Java 源码根目录。 */
    private static final String MAIN_JAVA_DIRECTORY = "src/main/java";

    /** 通配符导入表达式。 */
    private static final Pattern WILDCARD_IMPORT =
        Pattern.compile("(?m)^import .+\\.\\*;");

    /** 未明确声明受检异常回滚范围的事务注解。 */
    private static final Pattern TRANSACTION_WITHOUT_ROLLBACK_FOR =
        Pattern.compile("@Transactional\\b(?!\\s*\\([^)]*\\brollbackFor\\s*=)");

    /**
     * 必须持续说明性能机制和正确性边界的核心运行文件。
     *
     * <p>DTO 和枚举不强行添加 CPU 话术；本清单只覆盖确实决定线程、锁、分配、批量或 I/O 的代码。</p>
     */
    private static final List<String> CORE_RUNTIME_DOCUMENTS = List.of(
        "src/main/java/dev/fincore/FinCoreApplication.java",
        "src/main/java/dev/fincore/application/MatchingCommandCoordinator.java",
        "src/main/java/dev/fincore/application/MatchingService.java",
        "src/main/java/dev/fincore/application/SettlementService.java",
        "src/main/java/dev/fincore/application/SpotDeliveryService.java",
        "src/main/java/dev/fincore/application/SpotFundsService.java",
        "src/main/java/dev/fincore/application/TradeReliabilityService.java",
        "src/main/java/dev/fincore/application/TradingLifecycleService.java",
        "src/main/java/dev/fincore/application/TradingOrderCoordinator.java",
        "src/main/java/dev/fincore/application/WorkerLeaseManager.java",
        "src/main/java/dev/fincore/domain/FeeShardRouter.java",
        "src/main/java/dev/fincore/domain/ShardRouter.java",
        "src/main/java/dev/fincore/domain/TradingIdentifiers.java",
        "src/main/java/dev/fincore/domain/UuidOrder.java",
        "src/main/java/dev/fincore/infrastructure/concurrent/ConcurrencyConfiguration.java",
        "src/main/java/dev/fincore/infrastructure/concurrent/ConcurrencyProperties.java",
        "src/main/java/dev/fincore/infrastructure/concurrent/StripedTaskExecutor.java",
        "src/main/java/dev/fincore/infrastructure/concurrent/VirtualTaskExecutors.java",
        "src/main/java/dev/fincore/infrastructure/persistence/mapper/LedgerMapper.java",
        "src/main/java/dev/fincore/infrastructure/persistence/mapper/MatchingMapper.java",
        "src/main/java/dev/fincore/infrastructure/persistence/mapper/OutboxMapper.java",
        "src/main/java/dev/fincore/infrastructure/persistence/mapper/SpotFundsMapper.java",
        "src/main/java/dev/fincore/messaging/OutboxPublisher.java",
        "src/main/java/dev/fincore/messaging/SettlementListener.java"
    );

    /**
     * 验证每个生产类型都保留完整的类型级文档信息。
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void productionTypesKeepDocumentationContract() throws IOException {
        List<Path> sources = javaSources(MAIN_JAVA_DIRECTORY);
        assertFalse(sources.isEmpty(), "未找到生产 Java 源码");

        for (Path source : sources) {
            String content = Files.readString(source);
            if ("package-info.java".equals(source.getFileName().toString())) {
                assertTrue(content.contains("/**"), () -> "包说明缺少 Javadoc：" + source);
                continue;
            }
            assertTrue(content.contains("/**"), () -> "缺少 Javadoc：" + source);
            assertTrue(content.contains("@author"), () -> "缺少 @author：" + source);
            assertTrue(content.contains("@since"), () -> "缺少 @since：" + source);
        }
    }

    /**
     * 验证全部 Java 源码不包含通配符导入和 Tab 缩进。
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void allJavaSourcesAvoidWildcardImportsAndTabs() throws IOException {
        List<Path> sources = Stream.concat(
            javaSources(MAIN_JAVA_DIRECTORY).stream(),
            javaSources("src/test/java").stream()
        ).toList();

        for (Path source : sources) {
            String content = Files.readString(source);
            assertFalse(WILDCARD_IMPORT.matcher(content).find(),
                () -> "禁止通配符导入：" + source);
            assertFalse(content.contains("\t"), () -> "禁止 Tab 缩进：" + source);
        }
    }

    /**
     * 验证核心热路径文档不会退化成只有一句“负责什么”的空泛描述。
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void coreRuntimeDocumentationExplainsProblemCpuAndBoundary() throws IOException {
        for (String file : CORE_RUNTIME_DOCUMENTS) {
            String content = Files.readString(Path.of(file));
            assertTrue(content.contains("解决的问题："), () -> "缺少问题背景：" + file);
            assertTrue(content.contains("CPU"), () -> "缺少 CPU/资源机制：" + file);
            assertTrue(content.contains("边界："), () -> "缺少正确性或使用边界：" + file);
        }
    }

    /**
     * 验证所有显式事务都声明受检异常回滚策略。
     *
     * <p>资金、账本、状态、Inbox 与 Outbox 必须作为一个原子单元提交；即使未来业务方法增加
     * 受检异常，也不能沿用 Spring 默认策略而提交部分金融状态。</p>
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void financialTransactionsDeclareRollbackFor() throws IOException {
        for (Path source : javaSources(MAIN_JAVA_DIRECTORY)) {
            String content = Files.readString(source);
            assertFalse(TRANSACTION_WITHOUT_ROLLBACK_FOR.matcher(content).find(),
                () -> "事务必须声明 rollbackFor：" + source);
        }
    }

    /** 验证本轮逐文件注释索引存在，并保留真实性声明和验证入口。 */
    @Test
    void commentCoverageIndexTracksCpuHotspotsAndNonClaims() throws IOException {
        String content = Files.readString(Path.of("docs/code-comment-coverage.md"));
        assertTrue(content.contains("CPU 优化热点索引"), "索引缺少 CPU 优化热点");
        assertTrue(content.contains("不宣称已经实现"), "索引缺少真实性边界");
        assertTrue(content.contains("StripedTaskExecutor"), "索引缺少撮合执行器");
        assertTrue(content.contains("PostgresUuidTypeHandler"), "索引缺少 UUID 类型处理器");
    }

    /**
     * 递归列出指定目录中的 Java 源码。
     *
     * @param directory 相对于项目根目录的源码目录
     * @return 按路径排序的 Java 文件
     * @throws IOException 遍历目录失败时抛出
     */
    private static List<Path> javaSources(String directory) throws IOException {
        try (Stream<Path> paths = Files.walk(Path.of(directory))) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }
}
