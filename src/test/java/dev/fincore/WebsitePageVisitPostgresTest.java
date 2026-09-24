package dev.fincore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper;
import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper.Visit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 真实 PostgreSQL 验证 V9 迁移、事件去重和有界保留期清理。 */
class WebsitePageVisitPostgresTest extends AbstractLocalPostgresTestSupport {
    /** 重复事件保持第一份记录，数据库生成接收时间，清理只删除到期的限量记录。 */
    @Test
    void duplicateEventsAndRetentionUseDatabaseBoundaries() {
        WebsitePageVisitMapper mapper = sessions.getMapper(WebsitePageVisitMapper.class);
        UUID eventId = UUID.randomUUID();
        Instant before = Instant.now().minusSeconds(1);
        Visit visit = new Visit(eventId, "2001:db8::8", "/#intro", "https://example.com", "test-agent", "zh-CN");
        assertEquals(1, mapper.insertBatch(List.of(visit, visit)));
        assertEquals(0, mapper.insertBatch(List.of(new Visit(eventId, "192.0.2.9", "/", null, null, null))));
        assertEquals("/#intro", jdbc.queryForObject("SELECT page_path FROM website_page_visit WHERE event_id = ?",
            String.class, eventId));
        Instant received = jdbc.queryForObject("SELECT received_at FROM website_page_visit WHERE event_id = ?",
            java.sql.Timestamp.class, eventId).toInstant();
        assertTrue(received.isAfter(before));
        assertTrue(received.isBefore(Instant.now().plusSeconds(1)));
        jdbc.update("""
            INSERT INTO website_page_visit (event_id, received_at, page_path)
            VALUES (?, CURRENT_TIMESTAMP - INTERVAL '31 days', '/'),
                   (?, CURRENT_TIMESTAMP - INTERVAL '31 days', '/')
            """, UUID.randomUUID(), UUID.randomUUID());
        assertEquals(1, mapper.deleteExpired(30, 1));
        assertEquals(1, mapper.deleteExpired(30, 256));
        assertEquals(0, mapper.deleteExpired(30, 256));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM website_page_visit", Integer.class));
        assertEquals(1, sessions.getConfiguration().getMappedStatement(
            WebsitePageVisitMapper.class.getName() + ".insertBatch").getTimeout());
        assertEquals(1, sessions.getConfiguration().getMappedStatement(
            WebsitePageVisitMapper.class.getName() + ".deleteExpired").getTimeout());
    }
}
