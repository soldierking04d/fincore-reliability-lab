package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * MyBatis 持久化架构与金融数据不可变约束测试。
 *
 * <p>该测试以源码约束的方式阻止生产服务重新依赖 Spring JDBC，同时禁止 Mapper
 * 使用字符串直替参数，并保护账本分录与权威成交记录不被更新或删除。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
class PersistenceArchitectureTest {

    /** 生产源码根目录。 */
    private static final Path MAIN_SOURCE = Path.of("src/main/java");

    /** MyBatis Mapper 源码目录。 */
    private static final Path MAPPER_SOURCE = MAIN_SOURCE.resolve(
        "dev/fincore/infrastructure/persistence/mapper"
    );

    /**
     * 验证生产代码只能通过 MyBatis Mapper 访问数据库。
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void productionCodeUsesMyBatisInsteadOfDirectJdbc() throws IOException {
        String source = joinJavaSources(MAIN_SOURCE);
        String pom = Files.readString(Path.of("pom.xml"));
        String application = Files.readString(
            MAIN_SOURCE.resolve("dev/fincore/FinCoreApplication.java")
        );

        assertFalse(source.contains("org.springframework.jdbc.core.JdbcTemplate"),
            "生产代码禁止直接依赖 JdbcTemplate");
        assertFalse(source.contains("NamedParameterJdbcTemplate"),
            "生产代码禁止直接依赖 NamedParameterJdbcTemplate");
        assertTrue(pom.contains("mybatis-spring-boot-starter"),
            "项目必须引入 MyBatis Spring Boot Starter");
        assertTrue(application.contains("@MapperScan"),
            "Spring Boot 启动类必须扫描 MyBatis Mapper");
    }

    /**
     * 验证 Mapper 数量与参数绑定方式满足工程约束。
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void mappersUsePreparedBindingsAndCoverCoreDomains() throws IOException {
        List<Path> mappers = javaSources(MAPPER_SOURCE).stream()
            .filter(path -> !path.getFileName().toString().equals("package-info.java"))
            .toList();
        String mapperSource = joinJavaSources(MAPPER_SOURCE);

        assertTrue(mappers.size() >= 11, "核心领域至少需要 11 个 MyBatis Mapper");
        assertFalse(mapperSource.contains("${"),
            "Mapper 禁止使用可能造成 SQL 注入的字符串直替参数");
    }

    /**
     * 验证账本和权威成交事实保持只增不改。
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void financialFactsRemainAppendOnly() throws IOException {
        String ledgerMapper = Files.readString(MAPPER_SOURCE.resolve("LedgerMapper.java"))
            .toLowerCase();
        String tradeMapper = Files.readString(
            MAPPER_SOURCE.resolve("TradeReliabilityMapper.java")
        ).toLowerCase();

        assertFalse(ledgerMapper.contains("update ledger_entry"),
            "账本分录禁止原地更新");
        assertFalse(ledgerMapper.contains("delete from ledger_entry"),
            "账本分录禁止删除");
        assertFalse(tradeMapper.contains("update trade_execution"),
            "权威成交事实禁止原地更新");
        assertFalse(tradeMapper.contains("delete from trade_execution"),
            "权威成交事实禁止删除");
    }

    /**
     * 合并指定目录中的全部 Java 源码。
     *
     * @param root 源码根目录
     * @return 合并后的源码文本
     * @throws IOException 遍历或读取源码失败时抛出
     */
    private static String joinJavaSources(Path root) throws IOException {
        StringBuilder content = new StringBuilder();
        for (Path source : javaSources(root)) {
            content.append(Files.readString(source)).append('\n');
        }
        return content.toString();
    }

    /**
     * 递归列出指定目录中的 Java 源码。
     *
     * @param root 源码根目录
     * @return 按路径排序的 Java 文件
     * @throws IOException 遍历目录失败时抛出
     */
    private static List<Path> javaSources(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }
}
