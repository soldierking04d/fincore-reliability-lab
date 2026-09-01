import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

public class ValidateProject {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(root.resolve("pom.xml").toFile());
        List<String> required = List.of(
            "docker-compose.yml", "Dockerfile", "README.md", "README.en.md", "AGENTS.md",
            "LICENSE", "CONTRIBUTING.md", "docs/showcase/github-social-preview.jpg",
            "src/main/resources/application.yml", "src/main/resources/db/migration/V1__baseline.sql",
            "src/main/resources/db/migration/V2__operational_hardening.sql",
            "src/main/resources/db/migration/V5__concurrency_indexes.sql",
            "src/main/java/dev/fincore/FinCoreApplication.java",
            "src/main/java/dev/fincore/application/SettlementService.java",
            "src/main/java/dev/fincore/application/MatchingCommandCoordinator.java",
            "src/main/java/dev/fincore/application/WorkerLeaseManager.java",
            "src/main/java/dev/fincore/infrastructure/concurrent/StripedTaskExecutor.java",
            "src/main/java/dev/fincore/application/LabScenarioService.java",
            "src/test/java/dev/fincore/SettlementIntegrationTest.java",
            "config/jvm/g1.options", "config/jvm/zgc.options",
            "benchmarks/mixed-workload.js",
            "docs/high-concurrency-jvm-tuning.md"
        );
        for (String file : required) {
            if (!Files.isRegularFile(root.resolve(file)) || Files.size(root.resolve(file)) == 0) {
                throw new IllegalStateException("required file missing or empty: " + file);
            }
        }
        String compose = Files.readString(root.resolve("docker-compose.yml"));
        for (String service : List.of(
            "postgres:", "kafka:", "app:", "prometheus:", "grafana:", "performance-runner:")) {
            if (!compose.contains(service)) throw new IllegalStateException("compose service missing: " + service);
        }
        String migration = Files.readString(root.resolve("src/main/resources/db/migration/V1__baseline.sql"));
        for (String table : List.of("account", "settlement_order", "ledger_entry", "inbox_message", "outbox_event")) {
            if (!migration.contains("CREATE TABLE " + table)) throw new IllegalStateException("table missing: " + table);
        }
        String hardening = Files.readString(root.resolve("src/main/resources/db/migration/V2__operational_hardening.sql"));
        for (String token : List.of("fee_aggregation", "claimed_at", "publisher_id")) {
            if (!hardening.contains(token)) throw new IllegalStateException("hardening migration missing: " + token);
        }
        System.out.println("Project structure and XML validation passed");
    }
}
