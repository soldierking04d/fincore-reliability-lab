package dev.fincore.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper;
import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper.Visit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 访问日志的有界积压、失败丢弃与保留期测试。 */
class WebsitePageVisitServiceTest {
    /** 入口队列约定的固定上限。 */
    private static final int QUEUE_CAPACITY = 256;
    /** 入口最多接纳 256 条，不同步访问数据库；每次排空上限 32 条。 */
    @Test
    void admissionAndDrainAreBounded() {
        WebsitePageVisitMapper mapper = mock(WebsitePageVisitMapper.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        WebsitePageVisitService service = new WebsitePageVisitService(mapper, metrics, 30);
        when(mapper.insertBatch(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
        for (int index = 0; index < QUEUE_CAPACITY; index++) {
            assertTrue(service.accept(visit()));
        }
        assertFalse(service.accept(visit()));
        verifyNoInteractions(mapper);
        service.drain();
        ArgumentCaptor<List<Visit>> captor = ArgumentCaptor.captor();
        verify(mapper).insertBatch(captor.capture());
        assertEquals(32, captor.getValue().size());
        assertTrue(service.accept(visit()));
        assertEquals(1, metrics.get("fincore.analytics.dropped").tag("reason", "queue_full").counter().count());
        assertEquals(32, metrics.get("fincore.analytics.persisted").counter().count());
    }

    /** 数据库故障不重试失败批次，后续请求仍可入队，错误计数不带敏感标签。 */
    @Test
    void failedBatchIsDroppedAndLaterBatchesContinue() {
        WebsitePageVisitMapper mapper = mock(WebsitePageVisitMapper.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        WebsitePageVisitService service = new WebsitePageVisitService(mapper, metrics, 30);
        when(mapper.insertBatch(anyList())).thenThrow(new IllegalStateException("synthetic database failure"))
            .thenReturn(1);
        service.accept(visit());
        service.drain();
        service.drain();
        verify(mapper).insertBatch(anyList());
        assertTrue(service.accept(visit()));
        service.drain();
        verify(mapper, times(2)).insertBatch(anyList());
        assertEquals(1, metrics.get("fincore.analytics.dropped").tag("reason", "database").counter().count());
        assertEquals(1, metrics.get("fincore.analytics.persisted").counter().count());
    }

    /** 默认保留期按 30 天且每轮只删 1024 条，清理失败计数并等待下一轮。 */
    @Test
    void retentionIsBoundedAndFailureIsContained() {
        WebsitePageVisitMapper mapper = mock(WebsitePageVisitMapper.class);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        WebsitePageVisitService service = new WebsitePageVisitService(mapper, metrics, 30);
        doThrow(new IllegalStateException("synthetic cleanup failure")).when(mapper).deleteExpired(30, 1024);
        service.deleteExpired();
        verify(mapper).deleteExpired(30, 1024);
        assertEquals(1, metrics.get("fincore.analytics.cleanup.failures").counter().count());
        assertThrows(IllegalArgumentException.class, () -> new WebsitePageVisitService(mapper, metrics, 0));
        assertThrows(IllegalArgumentException.class, () -> new WebsitePageVisitService(mapper, metrics, 366));
    }

    /** 创建单个无身份信息的合成访问记录。 */
    private static Visit visit() {
        return new Visit(UUID.randomUUID(), "192.0.2.8", "/", null, "test-agent", "zh-CN");
    }
}
