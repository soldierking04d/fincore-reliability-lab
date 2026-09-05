package dev.fincore.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 公网演示应用层允许清单与管理令牌的契约测试。 */
class PublicDemoAccessFilterTest {
    /** 测试使用的固定高熵令牌，只存在于测试进程。 */
    private static final String ADMIN_TOKEN = "test-admin-token-with-at-least-32-characters";

    /** 公网 Profile 缺少管理令牌时必须失败关闭。 */
    @Test
    void missingAdminTokenRejectsApplicationStartup() {
        assertThrows(IllegalStateException.class, () -> new PublicDemoAccessFilter("short"));
    }

    /** 匿名用户可以查询业务数据，但不能调用普通资金写接口。 */
    @Test
    void anonymousAccessIsReadOnlyOutsideFixedScenarios() throws ServletException, IOException {
        PublicDemoAccessFilter filter = new PublicDemoAccessFilter(ADMIN_TOKEN);
        assertEquals(200, invoke(filter, "GET", "/api/matching/trades/BTC-USDT", null));
        assertEquals(403, invoke(filter, "POST", "/api/settlements", null));
        assertEquals(403, invoke(filter, "GET", "/actuator/metrics", null));
    }

    /** 固定场景允许匿名演示，任意故障注入仍被应用层拒绝。 */
    @Test
    void onlyDeterministicScenariosArePublic() throws ServletException, IOException {
        PublicDemoAccessFilter filter = new PublicDemoAccessFilter(ADMIN_TOKEN);
        assertEquals(200, invoke(filter, "POST", "/lab/scenarios/market-crash-day", null));
        assertEquals(403, invoke(filter, "POST", "/lab/faults/duplicate-message", null));
    }

    /** 内部压测和受控运维必须通过请求头携带正确管理令牌。 */
    @Test
    void validAdminTokenAllowsControlledWrites() throws ServletException, IOException {
        PublicDemoAccessFilter filter = new PublicDemoAccessFilter(ADMIN_TOKEN);
        assertEquals(200, invoke(filter, "POST", "/api/settlements", ADMIN_TOKEN));
        assertEquals(403, invoke(filter, "POST", "/api/settlements", ADMIN_TOKEN + "-wrong"));
    }

    /** 执行一次过滤并返回最终 HTTP 状态。 */
    private static int invoke(PublicDemoAccessFilter filter, String method, String path, String token)
        throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (token != null) {
            request.addHeader(PublicDemoAccessFilter.ADMIN_TOKEN_HEADER, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean continued = new AtomicBoolean();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));
        return continued.get() ? HttpServletResponseStatus.OK : response.getStatus();
    }

    /** 避免在测试中散落裸 HTTP 状态魔法值。 */
    private static final class HttpServletResponseStatus {
        /** 请求已进入后续处理链。 */
        private static final int OK = 200;

        /** 工具类不可实例化。 */
        private HttpServletResponseStatus() {
            throw new IllegalStateException("utility class");
        }
    }
}
