import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** 完整验收门禁：报告缺失、关键套件缺失、零测试、跳过或失败均不能发布。 */
class VerifyTestReports {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args.length == 0 ? "target/surefire-reports" : args[0]);
        Set<String> required = new HashSet<>(Set.of(
            "dev.fincore.DerivativesLabIntegrationTest", "dev.fincore.SettlementIntegrationTest",
            "dev.fincore.MatchingIntegrationTest", "dev.fincore.TradingLifecycleIntegrationTest",
            "dev.fincore.TradeReliabilityIntegrationTest", "dev.fincore.FeeAggregationIntegrationTest",
            "dev.fincore.LabScenarioIntegrationTest", "dev.fincore.SpotFundsIntegrationTest",
            "dev.fincore.SpotDeliveryKafkaIntegrationTest", "dev.fincore.KafkaVolumeRecoveryIntegrationTest"));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        long tests = 0, skipped = 0, failures = 0, errors = 0;
        try (var reports = Files.list(directory)) {
            for (Path report : reports.filter(p -> p.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                var suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
                long count = Long.parseLong(suite.getAttribute("tests"));
                if (suite.getAttribute("name").equals("dev.fincore.SpotDeliveryKafkaIntegrationTest") && count < 4) {
                    throw new IllegalStateException("缺少真实 Kafka、HTTP 并发、Broker 恢复或数据库恢复验收");
                }
                if (count > 0) required.remove(suite.getAttribute("name"));
                tests += count;
                skipped += Long.parseLong(suite.getAttribute("skipped"));
                failures += Long.parseLong(suite.getAttribute("failures"));
                errors += Long.parseLong(suite.getAttribute("errors"));
            }
        }
        System.out.printf("完整验收：tests=%d, skipped=%d, failures=%d, errors=%d%n", tests, skipped, failures, errors);
        Path evidence = directory.getParent().resolve("runtime-evidence");
        Set<String> requiredEvidence = Set.of("bounded-http-load.json", "broker-recovery.json",
            "database-restore.json", "kafka-volume-migration.json");
        Set<String> missingEvidence = new HashSet<>();
        for (String file : requiredEvidence) {
            Path path = evidence.resolve(file);
            if (!Files.isRegularFile(path) || !Files.readString(path).contains("\"scope\"")) {
                missingEvidence.add(file);
            }
        }
        if (tests == 0 || skipped != 0 || failures != 0 || errors != 0 || !required.isEmpty()) {
            throw new IllegalStateException("禁止发布不完整测试结果；缺失套件=" + required);
        }
        if (!missingEvidence.isEmpty()) {
            throw new IllegalStateException("禁止发布缺少运行证据的构建；缺失=" + missingEvidence);
        }
    }
}
