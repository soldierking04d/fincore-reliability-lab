package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.application.FeeAggregationService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
    "spring.kafka.listener.auto-startup=false",
    "spring.task.scheduling.enabled=false",
    "fincore.outbox-delay-ms=3600000",
    "fincore.outbox-recovery-delay-ms=3600000"
})
class FeeAggregationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired FeeAggregationService fees;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentShardProvisioningIsIdempotentAcrossTransactions() throws Exception {
        String asset = "RACE" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<FeeAggregationService.FeeAccount>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return fees.ensureShards(asset, 16);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<List<FeeAggregationService.FeeAccount>> future : futures) {
                assertEquals(16, future.get(30, TimeUnit.SECONDS).size());
            }
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        Long rows = jdbc.queryForObject("""
            SELECT count(*) FROM account
            WHERE asset=? AND account_type='SYSTEM_FEE_SHARD'
            """, Long.class, asset);
        assertEquals(16L, rows);
    }
}
