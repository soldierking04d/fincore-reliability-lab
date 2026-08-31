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

    /** 通配符导入表达式。 */
    private static final Pattern WILDCARD_IMPORT =
        Pattern.compile("(?m)^import .+\\.\\*;");

    /**
     * 验证每个生产类型都保留完整的类型级文档信息。
     *
     * @throws IOException 读取源码失败时抛出
     */
    @Test
    void productionTypesKeepDocumentationContract() throws IOException {
        List<Path> sources = javaSources("src/main/java");
        assertFalse(sources.isEmpty(), "未找到生产 Java 源码");

        for (Path source : sources) {
            String content = Files.readString(source);
            if (source.getFileName().toString().equals("package-info.java")) {
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
            javaSources("src/main/java").stream(),
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
