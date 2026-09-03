package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.SpotDeliveryService;
import dev.fincore.application.TradingLifecycleService;
import dev.fincore.application.TradingLifecycleScenarioService;
import dev.fincore.application.SpotFundsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.SpotDeliveryCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.apache.kafka.clients.admin.Admin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.utility.MountableFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.awaitility.Awaitility.await;

/**
 * 真正的 PostgreSQL → Outbox → Kafka → 有围栏 Worker → 双资产账本端到端验收。
 *
 * <p>消费者暂停时断言在途保留，恢复消费后再重放，不能用直接调用 settle 代替消息链路。</p>
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.kafka.listener.auto-startup=false", "spring.task.scheduling.enabled=true",
    "fincore.worker.id=spot-kafka-test", "fincore.outbox-delay-ms=100",
    "fincore.concurrency.settlement-consumers=2", "spring.profiles.active=lab"
})
@Import(SpotDeliveryKafkaIntegrationTest.Topics.class)
class SpotDeliveryKafkaIntegrationTest {
    /** 独立 PostgreSQL，不能指向现网。 */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    /** 固定版本的真实 Kafka Broker。 */
    @Container
    static KafkaContainer kafka = new KafkaContainer(KafkaVolumeRecoveryIntegrationTest.KAFKA_IMAGE);

    /** 注入隔离基础设施。 */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    /** 受控下单服务。 */
    @Autowired TradingLifecycleService trading;
    /** 只读交割查询，不从测试绕过 Kafka 执行结算。 */
    @Autowired SpotDeliveryService deliveries;
    /** 精确暂停、恢复本测试消费者。 */
    @Autowired KafkaListenerEndpointRegistry listeners;
    /** 用真实消息重放验证唯一资金效果。 */
    @Autowired KafkaTemplate<String, Object> producer;
    /** 独立数据库验收。 */
    @Autowired JdbcTemplate jdbc;
    /** 固定公开实验也必须经实际 Kafka 路径到达交割完成。 */
    @Autowired TradingLifecycleScenarioService scenario;
    /** 只在本套件随机端口访问真实 HTTP/Lane 入口，不允许配置公网压测目标。 */
    @LocalServerPort int port;
    /** 本测试应用 HTTP 客户端。 */
    @Autowired TestRestTemplate http;
    /** 分桶与不可变账本核验。 */
    @Autowired SpotFundsService funds;
    /** 读取实际队列、连接池等采样，缺少的指标保留为空。 */
    @Autowired MeterRegistry metrics;
    /** 把测量值保存为 CI 附件，不预填吞吐数字。 */
    @Autowired ObjectMapper json;

    /** 消费者停顿后重启，已发布成交不得丢失，重复 Kafka 消息不重复入账。 */
    @Test
    @Order(1)
    void outboxBrokerWorkerPipelineRecoversAfterConsumerPause() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String base = "KF" + suffix.toUpperCase();
        String symbol = base + "-USDT";
        String seller = "ks-" + suffix;
        String buyer = "kb-" + suffix;
        prepare(seller, base, "10");
        prepare(buyer, "USDT", "1000");
        trading.publishQuote(symbol, new BigDecimal("100"), "LAB", Instant.now());
        trading.place(order(seller, symbol, OrderSide.SELL));
        UUID trade = trading.place(order(buyer, symbol, OrderSide.BUY)).matching().trades().getFirst().tradeId();

        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND event_type='SPOT_DVP_REQUESTED' AND status='PUBLISHED'",
            Integer.class, trade.toString()) == 1);
        assertEquals("PENDING", deliveries.get(trade).status());
        amount("200", "pending_debit", buyer, "USDT");
        amount("1000", "balance", buyer, "USDT");

        listeners.start();
        await().atMost(Duration.ofSeconds(30)).until(() -> "SETTLED".equals(deliveries.get(trade).status()));
        producer.send("spot.delivery.commands.v1", trade.toString(),
            new SpotDeliveryCommand("broker-replay-" + trade, trade)).get(10, java.util.concurrent.TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).until(() -> jdbc.queryForObject(
            "SELECT count(*) FROM spot_delivery_inbox WHERE trade_id=?", Integer.class, trade) == 2);
        amount("800", "balance", buyer, "USDT");
        amount("2", "balance", buyer, base);
        amount("8", "balance", seller, base);
        amount("200", "balance", seller, "USDT");
        amount("0", "pending_debit", buyer, "USDT");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE business_key LIKE ?",
            Integer.class, "spot:" + trade + ":%"));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM shard_lease WHERE owner_id='spot-kafka-test'", Integer.class) > 0);

        var report = scenario.run();
        assertEquals("PASS", report.finalStatus());
        assertEquals(12, report.checks().size());
        assertTrue(report.checks().values().stream().allMatch(value -> value.startsWith("PASS：")));
        assertEquals("SETTLED", report.funds().deliveryStatus());
        assertEquals(4, report.funds().reconciledAccounts());
        assertEquals(0, report.funds().buyerQuoteBalance().compareTo(new BigDecimal("800")));
    }

    /**
     * 固定 16 并发、64 个独立市场，经 HTTP 和有界 Lane 提交 128 张订单，等待实际 Kafka 交割。
     * 不设置吞吐宣传目标：CI 机器不是线上机器，报告只能证明该次样本和资金不变量。
     */
    @Test
    @Order(2)
    void boundedHttpLoadCompletesAndReconcilesEveryAsset() throws Exception {
        listeners.start();
        var requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(15));
        http.getRestTemplate().setRequestFactory(requestFactory);
        List<LoadFixture> fixtures = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            fixtures.add(fixture());
        }
        // 准备账户不计入业务吞吐；行情在起跑前刷新，不能拿过期快照制造虚假性能失败。
        fixtures.forEach(f -> trading.publishQuote(f.symbol(), new BigDecimal("100"), "LAB", Instant.now()));
        List<Long> requestNanos = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<UUID> trades = new java.util.concurrent.CopyOnWriteArrayList<>();
        long gcBefore = gcMillis();
        long started = System.nanoTime();
        List<Map<String, Object>> samples = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var sampler = Executors.newSingleThreadScheduledExecutor();
             var workers = Executors.newFixedThreadPool(16)) {
            sampler.scheduleAtFixedRate(() -> samples.add(sample()), 0, 100, TimeUnit.MILLISECONDS);
            var futures = fixtures.stream().map(f -> workers.submit(() -> {
                trading.publishQuote(f.symbol(), new BigDecimal("100"), "LAB", Instant.now());
                postOrder(order(f.seller(), f.symbol(), OrderSide.SELL), requestNanos);
                JsonNode response = postOrder(order(f.buyer(), f.symbol(), OrderSide.BUY), requestNanos);
                trades.add(UUID.fromString(response.path("matching").path("trades").get(0).path("tradeId").asText()));
            })).toList();
            for (var future : futures) future.get(60, TimeUnit.SECONDS);
            long acceptedNanos = System.nanoTime() - started;
            await().atMost(Duration.ofSeconds(60)).until(() -> trades.size() == 64 && trades.stream()
                .allMatch(trade -> "SETTLED".equals(deliveries.get(trade).status())));
            long completedNanos = System.nanoTime() - started;
            for (LoadFixture f : fixtures) assertFunds(f);
            await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE status <> 'PUBLISHED'", Integer.class) == 0);
            requestNanos.sort(Long::compareTo);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("scope", "isolated CI, fixed finite sample; not production capacity or sustained load");
            report.put("recordedAt", Instant.now().toString());
            report.put("processors", Runtime.getRuntime().availableProcessors());
            report.put("javaVersion", System.getProperty("java.version"));
            report.put("concurrency", 16);
            report.put("httpRequests", requestNanos.size());
            report.put("httpRejected", 0);
            report.put("completedTrades", trades.size());
            report.put("acceptedOrdersPerSecond", 128_000_000_000.0 / acceptedNanos);
            report.put("completedTradesPerSecond", 64_000_000_000.0 / completedNanos);
            report.put("elapsedMillis", completedNanos / 1_000_000.0);
            report.put("requestP95Millis", percentile(requestNanos, 0.95));
            report.put("requestP99Millis", percentile(requestNanos, 0.99));
            report.put("gcCollectionMillisDelta", gcMillis() - gcBefore);
            report.put("reconciledAccounts", fixtures.size() * 4);
            report.put("fundDifferences", 0);
            report.put("samples", samples);
            evidence("bounded-http-load.json", report);
            sampler.shutdownNow();
        }
    }

    /** 已落 Broker 的待交割通知跨同容器重启仍可消费；仅重启本套件创建的测试容器。 */
    @Test
    @Order(3)
    void brokerRestartPreservesPublishedPendingDelivery() throws Exception {
        stopListeners();
        LoadFixture f = fixture();
        trading.place(order(f.seller(), f.symbol(), OrderSide.SELL));
        UUID trade = trading.place(order(f.buyer(), f.symbol(), OrderSide.BUY)).matching().trades().getFirst().tradeId();
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE aggregate_id=? AND event_type='SPOT_DVP_REQUESTED' AND status='PUBLISHED'",
            Integer.class, trade.toString()) == 1);
        assertEquals("PENDING", deliveries.get(trade).status());
        long started = System.nanoTime();
        kafka.getDockerClient().stopContainerCmd(kafka.getContainerId()).withTimeout(30).exec();
        kafka.getDockerClient().startContainerCmd(kafka.getContainerId()).exec();
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            try (var admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers(),
                "request.timeout.ms", "2000", "default.api.timeout.ms", "2000"))) {
                admin.listTopics().names().get(2, TimeUnit.SECONDS);
                return true;
            } catch (Exception unavailable) {
                return false;
            }
        });
        listeners.start();
        await().atMost(Duration.ofSeconds(90)).until(() -> "SETTLED".equals(deliveries.get(trade).status()));
        long recoveryMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        producer.send("spot.delivery.commands.v1", trade.toString(),
            new SpotDeliveryCommand("after-broker-restart-" + trade, trade)).get(20, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
            "SELECT count(*) FROM spot_delivery_inbox WHERE trade_id=?", Integer.class, trade) >= 2);
        assertFunds(f);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_transaction WHERE business_key LIKE ?",
            Integer.class, "spot:" + trade + ":%"));
        evidence("broker-recovery.json", Map.of("recordedAt", Instant.now().toString(),
            "scope", "isolated same-container broker restart; not disk loss or multi-broker failover",
            "recoveryMillis", recoveryMillis, "tradeId", trade, "status", "SETTLED",
            "balancedAssetTransactions", 2, "fundDifferences", 0));
    }

    /**
     * 把真实迁移后的数据库备份还原到全新测试容器，逐表比较行数与规范化内容摘要。
     * 只在测试夹具上执行，不接受外部连接地址，不删除或覆盖源库。
     */
    @Test
    @Order(4)
    void backupRestoresIntoNewDatabaseWithIdenticalFinancialFacts() throws Exception {
        // 即使单独运行此测试也创建资金事实，避免用空库恢复制造通过。
        listeners.start();
        LoadFixture f = fixture();
        trading.place(order(f.seller(), f.symbol(), OrderSide.SELL));
        UUID trade = trading.place(order(f.buyer(), f.symbol(), OrderSide.BUY)).matching().trades().getFirst().tradeId();
        await().atMost(Duration.ofSeconds(60)).until(() -> "SETTLED".equals(deliveries.get(trade).status()));
        assertFunds(f);
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status <> 'PUBLISHED'", Integer.class) == 0);
        stopListeners();
        var before = databaseDigest(jdbc);
        var dump = postgres.execInContainer("pg_dump", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
            "-Fc", "--no-owner", "--no-privileges", "-f", "/tmp/fincore-recovery.dump");
        assertEquals(0, dump.getExitCode(), dump.getStderr());
        Path archive = Files.createTempFile("fincore-test-only-", ".dump");
        try (var restored = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.copyFileFromContainer("/tmp/fincore-recovery.dump", archive.toString());
            restored.start();
            restored.copyFileToContainer(MountableFile.forHostPath(archive), "/tmp/fincore-recovery.dump");
            long started = System.nanoTime();
            var result = restored.execInContainer("pg_restore", "-U", restored.getUsername(), "-d", restored.getDatabaseName(),
                "--exit-on-error", "--no-owner", "--no-privileges", "/tmp/fincore-recovery.dump");
            assertEquals(0, result.getExitCode(), result.getStderr());
            var recovered = new JdbcTemplate(new DriverManagerDataSource(restored.getJdbcUrl(),
                restored.getUsername(), restored.getPassword()));
            assertEquals(before, databaseDigest(recovered));
            assertEquals(before, databaseDigest(jdbc), "备份期间源库金融事实发生变化，不能比较不一致快照");
            assertTrue(recovered.queryForObject("SELECT count(*) FROM spot_delivery WHERE status='SETTLED'", Integer.class) > 0);
            evidence("database-restore.json", Map.of("recordedAt", Instant.now().toString(),
                "scope", "new isolated PostgreSQL container; not an online restore or disaster RPO claim",
                "restoreMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                "tables", before, "identical", true));
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /** 两个真实模拟账户与一个独立市场，所有查询都绑定本次编号。 */
    private LoadFixture fixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String base = "RC" + suffix.toUpperCase();
        LoadFixture f = new LoadFixture(base, base + "-USDT", "rcs-" + suffix, "rcb-" + suffix);
        prepare(f.seller(), base, "10");
        prepare(f.buyer(), "USDT", "1000");
        trading.publishQuote(f.symbol(), new BigDecimal("100"), "LAB", Instant.now());
        return f;
    }

    /** 非 2xx 或业务拒绝立即失败，不自动重发写请求，也不伪造零错误统计。 */
    private JsonNode postOrder(PlaceOrderCommand command, List<Long> durations) {
        long start = System.nanoTime();
        var response = http.postForEntity("http://localhost:" + port + "/api/trading/orders", command, JsonNode.class);
        durations.add(System.nanoTime() - start);
        assertEquals(200, response.getStatusCode().value(), String.valueOf(response.getBody()));
        JsonNode body = java.util.Objects.requireNonNull(response.getBody());
        assertEquals("APPROVED", body.path("preTradeDecision").path("decision").asText(), body.toString());
        return body;
    }

    /** 每个资产账户都从数据库重算账本和预占，不只核对成交数。 */
    private void assertFunds(LoadFixture f) {
        amount("800", "balance", f.buyer(), "USDT");
        amount("2", "balance", f.buyer(), f.base());
        amount("8", "balance", f.seller(), f.base());
        amount("200", "balance", f.seller(), "USDT");
        for (String user : List.of(f.buyer(), f.seller())) {
            for (String asset : List.of(f.base(), "USDT")) {
                UUID account = jdbc.queryForObject("SELECT account_id FROM account WHERE owner_id=? AND asset=?", UUID.class, user, asset);
                assertTrue(funds.reconcile(account), user + ":" + asset);
            }
        }
    }

    /** 只核对数据表，不把运行时连接信息、口令或序列的分配缓存写进公开报告。 */
    private Map<String, Object> databaseDigest(JdbcTemplate database) {
        Map<String, Object> result = new java.util.TreeMap<>();
        for (String table : database.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            if (!table.matches("[a-z_][a-z0-9_]*")) throw new IllegalStateException("unexpected table name");
            result.put(table, database.queryForMap("SELECT count(*) AS rows, md5(coalesce(string_agg(row_to_json(t)::text, E'\\n' ORDER BY row_to_json(t)::text), '')) AS digest FROM " + table + " t"));
        }
        return result;
    }

    /** 等待全部 Listener 真正停止，避免停止/启动竞争造成测试假失败。 */
    private void stopListeners() throws InterruptedException {
        CountDownLatch stopped = new CountDownLatch(1);
        listeners.stop(stopped::countDown);
        assertTrue(stopped.await(30, TimeUnit.SECONDS), "Kafka Listener 未在时限内停止");
        assertTrue(listeners.getListenerContainers().stream().noneMatch(container -> container.isRunning()));
    }

    /** 性能指标允许浮点，所有交易金额仍使用 BigDecimal。 */
    private Map<String, Object> sample() {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("at", Instant.now().toString());
        for (String name : List.of("fincore.matching.queue.depth.total", "hikaricp.connections.active", "hikaricp.connections.pending", "process.cpu.usage")) {
            var gauge = metrics.find(name).gauge();
            double value = gauge == null ? Double.NaN : gauge.value();
            sample.put(name, Double.isFinite(value) ? value : null);
        }
        sample.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        return sample;
    }

    /** GC 累计耗时是 JVM 采样，不伪装为单次最大 STW 停顿。 */
    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    /** 有限样本 nearest-rank 分位数，只用于性能统计。 */
    private static double percentile(List<Long> sorted, double quantile) {
        return sorted.get((int) Math.ceil(sorted.size() * quantile) - 1) / 1_000_000.0;
    }

    /** CI 保存可核验 JSON；只有断言完成后才写入成功证据。 */
    private void evidence(String name, Map<String, ?> report) throws Exception {
        Path directory = Path.of("target", "runtime-evidence");
        Files.createDirectories(directory);
        json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name).toFile(), report);
    }

    /** 隔离资金夹具。 */
    private record LoadFixture(String base, String symbol, String seller, String buyer) { }

    /** 构造已开户且通过 KYC 的隔离用户。 */
    private void prepare(String user, String asset, String balance) {
        trading.registerCustomer(user, user, "CN");
        trading.reviewKyc(user, "VERIFIED");
        trading.openTradingAccount(user, asset, new BigDecimal(balance));
        trading.configureRisk(user, "LOW", true, new BigDecimal("10000"),
            new BigDecimal("20000"), new BigDecimal("0.20"));
    }

    /** 构造两单位限价单。 */
    private static PlaceOrderCommand order(String user, String symbol, OrderSide side) {
        return new PlaceOrderCommand(UUID.randomUUID().toString(), user, symbol, side, OrderType.LIMIT,
            new BigDecimal("100"), new BigDecimal("2"));
    }

    /** 列名来自测试固定白名单，业务输入只用参数绑定。 */
    private void amount(String expected, String column, String user, String asset) {
        if (!java.util.Set.of("balance", "pending_debit").contains(column)) {
            throw new IllegalArgumentException("test column not allowed");
        }
        assertEquals(0, new BigDecimal(expected).compareTo(jdbc.queryForObject(
            "SELECT " + column + " FROM account WHERE owner_id=? AND asset=?", BigDecimal.class, user, asset)));
    }

    /** 显式建立所有测试 Topic，不依赖 Broker 自动建 Topic 行为。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        /** 仅在隔离 Kafka 测试中创建单副本双分区 Topic。 */
        @Bean
        KafkaAdmin.NewTopics testTopics() {
            NewTopic[] topics = java.util.stream.Stream.of("settlement.commands.v1", "settlement.events.v1",
                "matching.events.v1", "spot.delivery.commands.v1")
                .map(name -> TopicBuilder.name(name).partitions(2).replicas(1).build()).toArray(NewTopic[]::new);
            return new KafkaAdmin.NewTopics(topics);
        }
    }
}
