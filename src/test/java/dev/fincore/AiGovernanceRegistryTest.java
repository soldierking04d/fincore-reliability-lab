package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * AI 用例登记与金融权限硬边界的持续集成回归测试。
 *
 * <p>本测试不评价模型的主观表现，而是确保任何被登记为已落地的 AI 能力都有可追溯证据，
 * 高风险能力保留人工审批，敏感数据用例保持只读，并且 AI 永远不能获得自主资金写权限。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
class AiGovernanceRegistryTest {

    /** AI 用例登记文件。 */
    private static final Path REGISTRY = Path.of("ai/use-cases.json");

    /** JSON 解析器。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 允许的交付状态。 */
    private static final Set<String> STATUSES = Set.of("planned", "pilot", "landed", "retired");

    /** 允许的数据等级。 */
    private static final Set<String> DATA_CLASSES = Set.of(
        "public", "synthetic_source", "internal_operational", "sensitive_customer", "restricted_financial"
    );

    /**
     * 验证项目级默认策略始终拒绝 AI 自主资金写入和原始生产数据输入。
     *
     * @throws IOException 登记文件读取失败时抛出
     */
    @Test
    void policyKeepsFinancialAuthorityBoundaries() throws IOException {
        JsonNode policy = registry().path("policy");

        assertTrue(policy.path("defaultDeny").asBoolean(), "AI 权限必须默认拒绝");
        assertFalse(policy.path("autonomousFinancialWritesAllowed").asBoolean(),
            "AI 不得自主修改资金或权威金融事实");
        assertTrue(policy.path("landedRequiresPublicEvidence").asBoolean(),
            "已落地能力必须包含公开证据");
        assertTrue(policy.path("highRiskRequiresHumanApproval").asBoolean(),
            "高风险能力必须保留人工审批");
        assertFalse(policy.path("rawProductionDataAllowed").asBoolean(),
            "不得把原始生产数据直接交给 AI");
    }

    /**
     * 验证每个用例的责任人、边界、评测、回退和监控证据均完整且互不冲突。
     *
     * @throws IOException 登记文件读取失败时抛出
     */
    @Test
    void useCasesKeepEvidenceAndHumanControl() throws IOException {
        JsonNode useCases = registry().path("useCases");
        assertTrue(useCases.isArray() && !useCases.isEmpty(), "至少登记一个 AI 用例");

        Set<String> identifiers = new HashSet<>();
        for (JsonNode useCase : useCases) {
            String identifier = requiredText(useCase, "id");
            assertTrue(identifiers.add(identifier), () -> "AI 用例编号重复：" + identifier);
            assertTrue(STATUSES.contains(requiredText(useCase, "status")),
                () -> "AI 用例状态非法：" + identifier);
            assertTrue(DATA_CLASSES.contains(requiredText(useCase, "dataClass")),
                () -> "AI 数据等级非法：" + identifier);

            requiredText(useCase, "owner");
            requiredText(useCase, "businessOutcome");
            requiredArray(useCase, "allowedInputs");
            requiredArray(useCase, "prohibitedActions");
            requiredArray(useCase.path("evaluation"), "hardVetoes");
            requiredText(useCase, "fallback");
            requiredArray(useCase, "monitoring");

            if ("high".equals(useCase.path("riskTier").asText())) {
                assertTrue(useCase.path("humanApproval").path("required").asBoolean(),
                    () -> "高风险 AI 用例缺少人工批准：" + identifier);
            }
            String classification = useCase.path("dataClass").asText();
            if (Set.of("sensitive_customer", "restricted_financial").contains(classification)) {
                assertEquals("read_only", useCase.path("authority").asText(),
                    () -> "敏感或受限数据用例必须只读：" + identifier);
            }
            if ("landed".equals(useCase.path("status").asText())) {
                JsonNode evidence = requiredArray(useCase.path("evaluation"), "evidence");
                for (JsonNode pathNode : evidence) {
                    Path evidencePath = Path.of(pathNode.asText());
                    assertTrue(Files.exists(evidencePath),
                        () -> "已落地 AI 用例证据不存在：" + evidencePath);
                }
            }
        }
    }

    /**
     * 读取 AI 用例登记。
     *
     * @return 登记根节点
     * @throws IOException 登记文件不存在或格式错误时抛出
     */
    private static JsonNode registry() throws IOException {
        assertTrue(Files.isRegularFile(REGISTRY), "缺少 AI 用例登记文件");
        return JSON.readTree(REGISTRY.toFile());
    }

    /**
     * 读取并验证必填文本字段。
     *
     * @param node 所属 JSON 节点
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
     * @param node 所属 JSON 节点
     * @param field 字段名
     * @return 非空数组
     */
    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = node.path(field);
        assertNotNull(value, () -> "缺少必填数组字段：" + field);
        assertTrue(value.isArray() && !value.isEmpty(), () -> "必填数组不能为空：" + field);
        return value;
    }
}
