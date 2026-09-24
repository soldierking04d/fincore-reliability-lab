package dev.fincore.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.fincore.application.WebsitePageVisitService;
import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper.Visit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 页面日志边界测试，所有地址和浏览器信息均为合成数据。 */
class WebsitePageVisitControllerTest {
    /** 任意转发头、Cookie 和授权信息均不能覆盖直连客户端地址或进入存储字段。 */
    @Test
    void stripsReferrerSecretsAndControlsWithoutTrustingSpoofedHeaders() {
        WebsitePageVisitService service = acceptingService();
        WebsitePageVisitController controller = new WebsitePageVisitController(service, "");
        MockHttpServletRequest request = request("192.0.2.5");
        request.addHeader("User-Agent", "test\r\n\u0000" + "x".repeat(600));
        request.addHeader("Accept-Language", "zh-CN\t" + "x".repeat(100));
        request.addHeader("Cookie", "secret=never-store");
        request.addHeader("Authorization", "Bearer never-store");
        request.addHeader("X-Forwarded-For", "198.51.100.2");
        request.addHeader("X-FinCore-Visitor-IP", "198.51.100.2");
        UUID eventId = UUID.randomUUID();
        controller.record(new WebsitePageVisitController.PageViewRequest(eventId, "/#intro",
            "https://user:password@EXAMPLE.com:8443/private?token=secret#private"), request);
        Visit visit = captured(service);
        assertEquals(eventId, visit.eventId());
        assertEquals("192.0.2.5", visit.clientIp());
        assertEquals("/#intro", visit.pagePath());
        assertEquals("https://example.com:8443", visit.referrerOrigin());
        assertEquals("test" + "x".repeat(508), visit.userAgent());
        assertEquals("zh-CN" + "x".repeat(59), visit.language());
    }

    /** 只有精确受信代理能提供单个数字地址；列表、主机名和无效地址退回 peer。 */
    @Test
    void trustsOnlyExactProxyAndNumericSingleAddress() {
        for (String forwarded : new String[] {"198.51.100.8", "2001:db8::8", "example.com",
            "198.51.100.8, 192.0.2.7", "999.1.1.1", "127.1"}) {
            WebsitePageVisitService service = acceptingService();
            WebsitePageVisitController controller = new WebsitePageVisitController(service, "192.0.2.7");
            MockHttpServletRequest request = request("192.0.2.7");
            request.addHeader("X-FinCore-Visitor-IP", forwarded);
            controller.record(new WebsitePageVisitController.PageViewRequest(UUID.randomUUID(), "/", ""), request);
            String expected = switch (forwarded) {
                case "198.51.100.8" -> forwarded;
                case "2001:db8::8" -> "2001:db8:0:0:0:0:0:8";
                default -> "192.0.2.7";
            };
            assertEquals(expected, captured(service).clientIp());
        }
        assertThrows(IllegalArgumentException.class,
            () -> new WebsitePageVisitController(acceptingService(), "192.0.2.0/24"));
        assertThrows(IllegalArgumentException.class,
            () -> new WebsitePageVisitController(acceptingService(), "localhost"));
    }

    /** 非 HTTP 来源和无效来源均舍弃；拒绝路径查询、身份路径、控制字符和超长锚点。 */
    @Test
    void limitsPagePathsAndDiscardsInvalidOrigins() {
        for (String referrer : new String[] {"javascript:alert(1)", "https://example.com\r\nx", ""}) {
            WebsitePageVisitService service = acceptingService();
            WebsitePageVisitController controller = new WebsitePageVisitController(service, "");
            controller.record(new WebsitePageVisitController.PageViewRequest(UUID.randomUUID(), "/", referrer),
                request("192.0.2.5"));
            assertNull(captured(service).referrerOrigin());
        }
        WebsitePageVisitController controller = new WebsitePageVisitController(acceptingService(), "");
        for (String path : new String[] {"/?token=secret", "/#intro?secret", "/users/123", "//example.com",
            "/#a b", "/#a\r\n", "/#" + "a".repeat(81)}) {
            assertThrows(IllegalArgumentException.class, () -> controller.record(
                new WebsitePageVisitController.PageViewRequest(UUID.randomUUID(), path, null), request("192.0.2.5")));
        }
    }

    /** 只有成功入队返回 202，队满返回 429，缺失或非法事件编号返回 400。 */
    @Test
    void responseMeansQueueAdmissionOnly() throws Exception {
        WebsitePageVisitService service = acceptingService();
        var mvc = MockMvcBuilders.standaloneSetup(new WebsitePageVisitController(service, ""))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"pagePath\":\"/\",\"referrerOrigin\":\"\"}";
        mvc.perform(post("/api/analytics/page-view").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted());
        when(service.accept(any())).thenReturn(false);
        mvc.perform(post("/api/analytics/page-view").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/analytics/page-view").contentType(MediaType.APPLICATION_JSON)
            .content("{\"pagePath\":\"/\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/analytics/page-view").contentType(MediaType.APPLICATION_JSON)
            .content("{\"eventId\":\"invalid\",\"pagePath\":\"/\"}")).andExpect(status().isBadRequest());
    }

    /** 创建不访问数据库的收集服务替身。 */
    private static WebsitePageVisitService acceptingService() {
        WebsitePageVisitService service = mock(WebsitePageVisitService.class);
        when(service.accept(any())).thenReturn(true);
        return service;
    }

    /** 创建指定直连地址的合成请求。 */
    private static MockHttpServletRequest request(String peer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        return request;
    }

    /** 读取经过净化后唯一可以进入队列的数据。 */
    private static Visit captured(WebsitePageVisitService service) {
        ArgumentCaptor<Visit> captor = ArgumentCaptor.forClass(Visit.class);
        verify(service).accept(captor.capture());
        return captor.getValue();
    }
}
