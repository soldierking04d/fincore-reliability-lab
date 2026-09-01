package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 五份技术治理台账的持续集成回归测试。
 *
 * <p>台账使用 JSON 兼容的 YAML 1.2 子集，因此不需要为测试额外引入 YAML 解析依赖。本测试确保服务、
 * 风险、指标、技术雷达和审计证据之间使用稳定编号关联，避免网站和管理文档演进后只剩不可验证的描述。</p>
 *
 * @author FinCore Reliability Lab
 * @since 0.5.0
 */
class GovernanceRegistryTest {

    /** 治理目录。 */
    private static final Path GOVERNANCE_DIRECTORY = Path.of("governance");

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 验证服务目录的责任、复查周期和依赖关系。
     *
     * @throws IOException 台账读取失败时抛出
     */
    @Test
    void serviceCatalogKeepsOwnershipAndValidDependencies() throws IOException {
        JsonNode services = registry("services.yaml").path("services");
        Set<String> identifiers = identifiers(services, "serviceId");

        for (JsonNode service : services) {
            String identifier = requiredText(service, "serviceId");
            assertFalse(requiredText(service, "owner").equals(requiredText(service, "deputy")),
                () -> "服务 Owner 与 Deputy 不能相同：" + identifier);
            requiredArray(service, "capabilities");
            requiredArray(service, "repoPaths");
            requiredArray(service, "dataClasses");
            requiredArray(service, "runbooks");
            assertReviewWindow(service, "lastReviewed", "nextReview", identifier);
            for (JsonNode dependency : service.path("dependencies")) {
                assertTrue(identifiers.contains(dependency.asText()),
                    () -> "服务依赖不存在：" + identifier + " -> " + dependency.asText());
            }
        }
    }

    /**
     * 验证风险评分、接受期限以及风险到服务和控制的交叉引用。
     *
     * @throws IOException 台账读取失败时抛出
     */
    @Test
    void riskRegisterKeepsScoresAndAcceptanceExpiring() throws IOException {
        JsonNode risks = registry("risks.yaml").path("risks");
        Set<String> services = identifiers(registry("services.yaml").path("services"), "serviceId");
        Set<String> controls = identifiers(registry("audit-evidence.yaml").path("controls"), "controlId");
        identifiers(risks, "riskId");

        for (JsonNode risk : risks) {
            String identifier = requiredText(risk, "riskId");
            assertEquals(risk.path("probability").asInt() * risk.path("impact").asInt(),
                risk.path("score").asInt(), () -> "风险评分必须等于概率乘影响：" + identifier);
            assertReferencesExist(risk, "linkedServices", services, identifier);
            assertReferencesExist(risk, "controls", controls, identifier);
            assertReviewWindow(risk, "lastReviewed", "nextReview", identifier);
            if ("accepted".equals(risk.path("status").asText())) {
                requiredText(risk, "acceptedBy");
                LocalDate.parse(requiredText(risk, "acceptanceExpiresAt"));
            } else {
                assertTrue(risk.path("acceptedBy").isNull(),
                    () -> "未接受风险不能填写 acceptedBy：" + identifier);
                assertTrue(risk.path("acceptanceExpiresAt").isNull(),
                    () -> "未接受风险不能填写接受到期日：" + identifier);
            }
        }
    }

    /**
     * 验证指标权威级别，防止网站固定回放曲线冒充真实运行指标。
     *
     * @throws IOException 台账读取失败时抛出
     */
    @Test
    void metricCatalogSeparatesDemonstrationTelemetry() throws IOException {
        JsonNode metrics = registry("metrics.yaml").path("metrics");
        identifiers(metrics, "metricId");

        for (JsonNode metric : metrics) {
            String identifier = requiredText(metric, "metricId");
            requiredText(metric, "definition");
            requiredText(metric, "owner");
            requiredText(metric, "steward");
            requiredArray(metric, "qualityRules");
            requiredArray(metric, "thresholds");
            assertReviewWindow(metric, "lastReviewed", "nextReview", identifier);
            if (metric.path("demoOnly").asBoolean()) {
                assertEquals("demonstration", metric.path("authority").asText(),
                    () -> "演示指标必须标记 demonstration：" + identifier);
                assertEquals("website_replay_telemetry", metric.path("source").asText(),
                    () -> "演示指标必须声明网站回放来源：" + identifier);
            } else {
                assertFalse("demonstration".equals(metric.path("authority").asText()),
                    () -> "非演示指标不能使用 demonstration 权威级别：" + identifier);
            }
        }
    }

    /**
     * 验证技术雷达和审计控制的证据文件、退出条件与跨台账引用。
     *
     * @throws IOException 台账读取失败时抛出
     */
    @Test
    void radarAndAuditCatalogKeepReviewableEvidence() throws IOException {
        JsonNode radar = registry("technology-radar.yaml").path("technologies");
        JsonNode controls = registry("audit-evidence.yaml").path("controls");
        Set<String> serviceIds = identifiers(registry("services.yaml").path("services"), "serviceId");
        Set<String> riskIds = identifiers(registry("risks.yaml").path("risks"), "riskId");
        identifiers(radar, "technologyId");
        identifiers(controls, "controlId");

        for (JsonNode technology : radar) {
            String identifier = requiredText(technology, "technologyId");
            requiredArray(technology, "constraints");
            requiredArray(technology, "successCriteria");
            requiredArray(technology, "exitCriteria");
            assertReviewWindow(technology, "lastReviewed", "nextReview", identifier);
            if ("adopt".equals(technology.path("ring").asText())) {
                assertEvidenceExists(requiredArray(technology, "evidence"), identifier);
            }
        }

        for (JsonNode control : controls) {
            String identifier = requiredText(control, "controlId");
            assertReferencesExist(control, "linkedServices", serviceIds, identifier);
            assertReferencesExist(control, "linkedRisks", riskIds, identifier);
            assertReviewWindow(control, "lastVerified", "nextReview", identifier);
            if (Set.of("demonstrated", "independently_verified").contains(control.path("status").asText())) {
                assertEvidenceExists(requiredArray(control, "evidence"), identifier);
            }
        }
    }

    /**
     * 读取治理台账。
     *
     * @param fileName 文件名
     * @return 台账根节点
     * @throws IOException 文件不存在或格式错误时抛出
     */
    private static JsonNode registry(String fileName) throws IOException {
        Path path = GOVERNANCE_DIRECTORY.resolve(fileName);
        assertTrue(Files.isRegularFile(path), () -> "缺少治理台账：" + path);
        return JSON.readTree(path.toFile());
    }

    /**
     * 收集数组中的唯一编号。
     *
     * @param entries 台账条目
     * @param field 编号字段
     * @return 唯一编号集合
     */
    private static Set<String> identifiers(JsonNode entries, String field) {
        assertTrue(entries.isArray() && !entries.isEmpty(), () -> "台账数组不能为空：" + field);
        Set<String> values = new HashSet<>();
        for (JsonNode entry : entries) {
            String value = requiredText(entry, field);
            assertTrue(values.add(value), () -> "治理编号重复：" + value);
        }
        return values;
    }

    /**
     * 验证引用的编号均存在。
     *
     * @param node 条目节点
     * @param field 引用字段
     * @param availableIds 可用编号
     * @param ownerId 当前条目编号
     */
    private static void assertReferencesExist(JsonNode node, String field, Set<String> availableIds,
                                              String ownerId) {
        JsonNode references = node.path(field);
        assertTrue(references.isArray(), () -> "引用字段必须是数组：" + ownerId + "." + field);
        for (JsonNode reference : references) {
            assertTrue(availableIds.contains(reference.asText()),
                () -> "跨台账引用不存在：" + ownerId + " -> " + reference.asText());
        }
    }

    /**
     * 验证证据文件真实存在。
     *
     * @param evidence 证据路径数组
     * @param ownerId 当前条目编号
     */
    private static void assertEvidenceExists(JsonNode evidence, String ownerId) {
        for (JsonNode pathNode : evidence) {
            Path path = Path.of(pathNode.asText());
            assertTrue(Files.exists(path), () -> "治理证据不存在：" + ownerId + " -> " + path);
        }
    }

    /**
     * 验证下次复查晚于本次复查。
     *
     * @param node 条目节点
     * @param reviewedField 上次复查字段
     * @param nextField 下次复查字段
     * @param ownerId 当前条目编号
     */
    private static void assertReviewWindow(JsonNode node, String reviewedField, String nextField, String ownerId) {
        LocalDate reviewed = LocalDate.parse(requiredText(node, reviewedField));
        LocalDate next = LocalDate.parse(requiredText(node, nextField));
        assertTrue(next.isAfter(reviewed), () -> "下次复查必须晚于本次复查：" + ownerId);
    }

    /**
     * 读取并验证必填文本字段。
     *
     * @param node 所属节点
     * @param field 字段名
     * @return 非空文本
     */
    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        assertTrue(value.isTextual() && !value.asText().isBlank(), () -> "缺少必填文本字段：" + field);
        return value.asText();
    }

    /**
     * 读取并验证必填数组字段。
     *
     * @param node 所属节点
     * @param field 字段名
     * @return 非空数组
     */
    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        assertTrue(value.isArray() && !value.isEmpty(), () -> "必填数组不能为空：" + field);
        return value;
    }
}
