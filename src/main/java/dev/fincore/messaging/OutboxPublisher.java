package dev.fincore.messaging;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisher {
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, Object> kafka;
    private final String settlementTopic;
    private final String matchingTopic;
    private final String publisherId;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, Object> kafka,
                           @Value("${fincore.kafka.outbox-topic}") String settlementTopic,
                           @Value("${fincore.kafka.matching-topic}") String matchingTopic,
                           @Value("${fincore.worker.id:${HOSTNAME:local-worker}}") String publisherId) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.settlementTopic = settlementTopic;
        this.matchingTopic = matchingTopic;
        this.publisherId = publisherId;
    }

    @Scheduled(fixedDelayString = "${fincore.outbox-delay-ms:1000}")
    public void publishBatch() {
        List<Map<String, Object>> events = jdbc.queryForList("""
            UPDATE outbox_event SET status='PROCESSING', claimed_at=now(), publisher_id=?
            WHERE event_id IN (
                SELECT event_id FROM outbox_event
                WHERE status='PENDING' AND next_attempt_at<=now()
                ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED
            )
            RETURNING event_id, aggregate_id, event_type, payload
            """, publisherId);
        for (Map<String, Object> event : events) {
            UUID id = (UUID) event.get("event_id");
            String eventType = event.get("event_type").toString();
            String topic = eventType.startsWith("MATCHING_") ? matchingTopic : settlementTopic;
            try {
                kafka.send(topic, event.get("aggregate_id").toString(), event.get("payload")).get();
                jdbc.update("""
                    UPDATE outbox_event SET status='PUBLISHED', published_at=now(), claimed_at=NULL, publisher_id=NULL
                    WHERE event_id=? AND status='PROCESSING' AND publisher_id=?
                    """, id, publisherId);
            } catch (Exception e) {
                jdbc.update("""
                    UPDATE outbox_event SET status='PENDING', attempts=attempts+1, claimed_at=NULL, publisher_id=NULL,
                    next_attempt_at=now() + interval '5 seconds'
                    WHERE event_id=? AND status='PROCESSING' AND publisher_id=?
                    """, id, publisherId);
            }
        }
    }

    @Scheduled(fixedDelayString = "${fincore.outbox-recovery-delay-ms:30000}")
    public void recoverAbandonedClaims() {
        jdbc.update("""
            UPDATE outbox_event SET status='PENDING', publisher_id=NULL, claimed_at=NULL,
                                    attempts=attempts+1, next_attempt_at=now()
            WHERE status='PROCESSING' AND (claimed_at IS NULL OR claimed_at < now() - interval '60 seconds')
            """);
    }
}
