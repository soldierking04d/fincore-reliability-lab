package dev.fincore.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 公网演示 Profile 的应用层最小权限边界。
 *
 * <p><strong>解决的问题：</strong>Nginx 是第一道公网边界，但反向代理配置错误、容器网络内调用或
 * 端口误暴露都可能绕过它。本过滤器在应用进程内再次执行明确允许清单：匿名用户只能读取演示数据、
 * 健康与指标，并启动四个有全局限流的确定性场景；其他写操作必须携带管理令牌。</p>
 *
 * <p><strong>正确性与性能边界：</strong>过滤只发生在 HTTP 入口，不改变 Kafka Worker、资金事务或
 * Fencing。路径集合为进程启动时创建的不可变常量，每个请求只执行方法、路径和固定长度令牌比较，
 * 不访问数据库。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.4.0
 */
@Component
@Profile("public-demo")
public class PublicDemoAccessFilter extends OncePerRequestFilter {
    /** 管理调用使用的请求头，不把令牌放入 URL 或日志。 */
    static final String ADMIN_TOKEN_HEADER = "X-FinCore-Admin-Token";
    /** 管理令牌的最低长度，至少提供 256 bit 的随机十六进制值。 */
    private static final int MIN_ADMIN_TOKEN_LENGTH = 32;
    /** 权限拒绝响应保持固定，不回显路径、令牌或内部异常。 */
    private static final String ACCESS_DENIED_BODY =
        "{\"code\":\"PUBLIC_DEMO_ACCESS_DENIED\",\"error\":\"request is not allowed\"}";
    /** 允许匿名启动的固定场景。 */
    private static final Set<String> PUBLIC_SCENARIOS = Set.of(
        "/lab/scenarios/full",
        "/lab/scenarios/market-crash-day",
        "/lab/scenarios/trading-lifecycle",
        "/lab/scenarios/exchange-coverage"
    );
    /** 只允许匿名读取的 Actuator 端点。 */
    private static final Set<String> PUBLIC_ACTUATOR_PATHS = Set.of(
        "/actuator/health",
        "/actuator/prometheus"
    );
    /** 仅保存在内存中的管理令牌字节。 */
    private final byte[] adminToken;

    /**
     * 创建公网访问过滤器；公网 Profile 没有令牌时拒绝启动，防止误配后默认放行。
     *
     * @param token 由环境变量注入的高熵管理令牌
     */
    public PublicDemoAccessFilter(@Value("${fincore.public-demo.admin-token:}") String token) {
        if (token == null || token.length() < MIN_ADMIN_TOKEN_LENGTH) {
            throw new IllegalStateException("public-demo requires an admin token of at least 32 characters");
        }
        this.adminToken = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 对每个公网请求执行匿名允许清单或管理令牌校验。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @param filterChain 后续过滤链
     * @throws ServletException 后续过滤器处理失败
     * @throws IOException 写响应或继续处理失败
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (hasValidAdminToken(request) || isAnonymousRequestAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(ACCESS_DENIED_BODY);
    }

    /** 管理令牌采用恒定时间字节比较，避免普通字符串提前返回暴露匹配前缀。 */
    private boolean hasValidAdminToken(HttpServletRequest request) {
        String candidate = request.getHeader(ADMIN_TOKEN_HEADER);
        return candidate != null && MessageDigest.isEqual(
            adminToken,
            candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    /** 匿名请求只允许只读业务查询、最小观测端点和四个确定性场景。 */
    private static boolean isAnonymousRequestAllowed(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method)) {
            return path.startsWith("/api/") || PUBLIC_ACTUATOR_PATHS.contains(path);
        }
        if (HttpMethod.OPTIONS.matches(method)) {
            return PUBLIC_SCENARIOS.contains(path);
        }
        return HttpMethod.POST.matches(method) && PUBLIC_SCENARIOS.contains(path);
    }
}
