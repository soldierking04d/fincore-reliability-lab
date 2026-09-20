package dev.fincore.messaging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Production YAML must export histogram buckets suitable for aggregate P95/P99. */
class SettlementMetricsConfigurationTest {
    /** Use the actual application configuration and Prometheus registry, with no database or Kafka. */
    @Test
    void configuredConsumerAndStageTimersExportHistogramBuckets() {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                PrometheusMetricsExportAutoConfiguration.class))
            .run(context -> {
                PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
                registry.timer("fincore.settlement.consumer.processing", "type", "spot", "outcome", "success")
                    .record(Duration.ofMillis(25));
                registry.timer("fincore.settlement.consumer.stage", "type", "spot", "outcome", "success",
                    "stage", "lease").record(Duration.ofMillis(5));

                String scrape = registry.scrape();
                assertTrue(scrape.contains("fincore_settlement_consumer_processing_seconds_bucket{"));
                assertTrue(scrape.contains("fincore_settlement_consumer_stage_seconds_bucket{"));
                assertTrue(scrape.contains("le=\"+Inf\""));
            });
    }
}
