package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 隔离验证容器写层 → 持久卷迁移：不依赖“挂了卷所以数据就持久化”的错误推断。
 * 所有容器、卷均由本测试创建；不连接现网，也不执行全局清理。
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaVolumeRecoveryIntegrationTest {
    /** 2026-09-04 从实际服务器读取的 Apache Kafka 4.3.1 镜像摘要；迁移不顺便升级 Broker。 */
    static final String KAFKA_IMAGE = "apache/kafka@sha256:77e3df9054047a88b520d0cc46e16696d3b22022e1d580aeccd2632df6532837";
    /** 固定测试消息主题。 */
    private static final String TOPIC = "volume-migration-proof";
    /** 固定测试消费组，恢复后必须保持已提交的 offset。 */
    private static final String GROUP = "volume-migration-group";

    /** 原容器停止后复制一致数据，再在新容器挂载同一持久卷；验证日志和消费者进度。 */
    @Test
    void migrateStoppedBrokerDataToNamedVolumeAndResumeCommittedOffset() throws Exception {
        var docker = DockerClientFactory.instance().client();
        String volume = "fincore-ci-kafka-" + UUID.randomUUID();
        docker.createVolumeCmd().withName(volume).withLabels(Map.of("fincore.scope", "isolated-ci-test")).exec();
        long started = System.nanoTime();
        try (var original = new KafkaContainer(KAFKA_IMAGE).withEnv("KAFKA_LOG_DIRS", "/tmp/fincore-before")) {
            original.start();
            try (var admin = Admin.create(Map.of("bootstrap.servers", original.getBootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
            }
            produce(original.getBootstrapServers(), 0, 10);
            TopicPartition partition = new TopicPartition(TOPIC, 0);
            try (var consumer = consumer(original.getBootstrapServers())) {
                consumer.assign(List.of(partition));
                consumer.seek(partition, 0);
                assertEquals(List.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"), read(consumer, 10));
                consumer.commitSync(Map.of(partition, new OffsetAndMetadata(5)), Duration.ofSeconds(15));
            }
            // Docker stop 等待 Kafka 优雅停机后才拷贝，不把在线目录的任意拷贝称为一致备份。
            docker.stopContainerCmd(original.getContainerId()).withTimeout(30).exec();
            try (var helper = new GenericContainer<>(KAFKA_IMAGE)
                .withCreateContainerCmdModifier(command -> {
                    command.withEntrypoint("/bin/sh");
                    command.withUser("0");
                    command.getHostConfig().withBinds(new Bind(volume, new Volume("/var/lib/kafka/data")));
                }).withCommand("-c", "sleep 300")) {
                helper.start();
                try (var archive = docker.copyArchiveFromContainerCmd(original.getContainerId(), "/tmp/fincore-before/.").exec()) {
                    docker.copyArchiveToContainerCmd(helper.getContainerId()).withRemotePath("/var/lib/kafka/data")
                        .withTarInputStream(archive).exec();
                }
                // 生产迁移中的 docker cp 会把宿主机备份归给 root；先复现，再验证脚本要求的修复。
                var ownership = helper.execInContainer("sh", "-ec",
                    "chown -R 0:0 /var/lib/kafka/data; chmod 700 /var/lib/kafka/data; "
                        + "test \"$(stat -c %u:%g /var/lib/kafka/data)\" = 0:0; "
                        + "chown -R 1000:1000 /var/lib/kafka/data; chmod 755 /var/lib/kafka/data; "
                        + "test \"$(stat -c %u:%g /var/lib/kafka/data)\" = 1000:1000");
                assertEquals(0, ownership.getExitCode(), ownership.getStderr());
                var metadata = helper.execInContainer("test", "-f", "/var/lib/kafka/data/meta.properties");
                assertEquals(0, metadata.getExitCode(), "复制后根目录必须包含集群元数据，不得套错一层目录");
            }
            try (var recovered = new KafkaContainer(KAFKA_IMAGE).withEnv("KAFKA_LOG_DIRS", "/var/lib/kafka/data")
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withBinds(new Bind(volume, new Volume("/var/lib/kafka/data"))))) {
                recovered.start();
                try (var consumer = consumer(recovered.getBootstrapServers())) {
                    consumer.assign(List.of(partition));
                    var committed = consumer.committed(java.util.Set.of(partition), Duration.ofSeconds(20)).get(partition);
                    assertEquals(5, committed.offset());
                    consumer.seek(partition, committed.offset());
                    assertEquals(List.of("5", "6", "7", "8", "9"), read(consumer, 5));
                    produce(recovered.getBootstrapServers(), 10, 11);
                    assertEquals(List.of("10"), read(consumer, 1));
                    assertEquals(11, consumer.endOffsets(List.of(partition)).get(partition));
                }
            }
            Path report = Path.of("target/runtime-evidence/kafka-volume-migration.json");
            Files.createDirectories(report.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), Map.of(
                "recordedAt", Instant.now().toString(), "kafkaImage", KAFKA_IMAGE,
                "scope", "isolated stopped-container copy into named volume; not online migration or HA",
                "originalMessages", 10, "restoredCommittedOffset", 5, "finalEndOffset", 11,
                "elapsedMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                "ownershipRepairVerified", true, "verified", true));
        } finally {
            // 唯一删除目标来自本方法刚创建的随机测试卷；禁止 volume prune 等全局命令。
            docker.removeVolumeCmd(volume).exec();
        }
    }

    /** 每条记录等待 Broker 确认，不能仅统计客户端已发起的请求。 */
    private static void produce(String bootstrap, int first, int end) throws Exception {
        try (var producer = new KafkaProducer<String, String>(Map.of("bootstrap.servers", bootstrap,
            "acks", "all", "key.serializer", StringSerializer.class.getName(), "value.serializer", StringSerializer.class.getName()))) {
            for (int index = first; index < end; index++) {
                producer.send(new ProducerRecord<>(TOPIC, "proof", Integer.toString(index))).get(20, TimeUnit.SECONDS);
            }
        }
    }

    /** 读取真实消费组记录，禁用自动提交以精确验证位点。 */
    private static KafkaConsumer<String, String> consumer(String bootstrap) {
        return new KafkaConsumer<>(Map.of("bootstrap.servers", bootstrap, "group.id", GROUP,
            "enable.auto.commit", "false", "auto.offset.reset", "earliest",
            "key.deserializer", StringDeserializer.class.getName(), "value.deserializer", StringDeserializer.class.getName()));
    }

    /** 有限超时读取，缺失、重复或额外消息都会使精确断言失败。 */
    private static List<String> read(KafkaConsumer<String, String> consumer, int expected) {
        List<String> values = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (values.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach(record -> values.add(record.value()));
        }
        assertTrue(values.size() >= expected, "Kafka 恢复后缺少应有消息");
        return values;
    }
}
