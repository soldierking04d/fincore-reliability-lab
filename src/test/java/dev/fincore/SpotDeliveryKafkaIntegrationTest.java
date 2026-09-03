package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.SpotDeliveryService;
import dev.fincore.application.TradingLifecycleService;
import dev.fincore.application.TradingLifecycleScenarioService;
import dev.fincore.domain.OrderSide;
import dev.fincore.domain.OrderType;
import dev.fincore.domain.PlaceOrderCommand;
import dev.fincore.domain.SpotDeliveryCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
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
@SpringBootTest(properties = {
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
    static KafkaContainer kafka = new KafkaContainer("apache/kafka-native:3.8.0");

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

    /** 消费者停顿后重启，已发布成交不得丢失，重复 Kafka 消息不重复入账。 */
    @Test
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
