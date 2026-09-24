package dev.fincore.web;

import dev.fincore.application.WebsitePageVisitService;
import dev.fincore.infrastructure.persistence.mapper.WebsitePageVisitMapper.Visit;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 仅收集最小页面访问数据；不提供公开查询，不读取 Cookie、身份、授权或完整来源 URL。
 *
 * @author FinCore Reliability Lab
 * @since 2026-09-24
 */
@RestController
public class WebsitePageVisitController {
    /** 来源 origin 存储上限。 */
    private static final int ORIGIN_LIMIT = 255;
    /** 浏览器标识存储上限。 */
    private static final int USER_AGENT_LIMIT = 512;
    /** 语言存储上限。 */
    private static final int LANGUAGE_LIMIT = 64;
    /** 避免为任意长来源字符串构建 URI。 */
    private static final int REFERRER_INPUT_LIMIT = 4096;
    /** 页面只使用首页和公开章节锚点，禁止路径中携带用户信息或查询。 */
    private static final Pattern PAGE_PATH = Pattern.compile("/(#[a-z][a-z0-9-]{0,79})?");
    /** IP 只允许数字字面量，不允许主机名、网段、范围或地址列表。 */
    private static final Pattern NUMERIC_IP = Pattern.compile("[0-9a-fA-F:.]+");
    /** IPv6 带 IPv4 尾部时的最长数字地址。 */
    private static final int IP_LIMIT = 45;
    /** IPv4 必须提供完整四段。 */
    private static final int IPV4_PARTS = 4;
    /** IPv6 数字地址分隔符。 */
    private static final char IPV6_SEPARATOR = ':';
    /** 来源仅允许公开网页协议。 */
    private static final Set<String> ORIGIN_SCHEMES = Set.of("http", "https");
    /** 异步收集服务。 */
    private final WebsitePageVisitService service;
    /** 仅允许精确数字地址；不支持通配符、网段或主机名。 */
    private final Set<String> trustedProxies;

    /** 默认不信任任何代理请求头，配置只允许单个数字 IP 的逗号分隔列表。 */
    public WebsitePageVisitController(WebsitePageVisitService service,
                                     @Value("${fincore.analytics.trusted-proxies:}") String proxies) {
        this.service = service;
        this.trustedProxies = Arrays.stream(proxies.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).map(value -> {
                String address = numericAddress(value);
                if (address == null) {
                    throw new IllegalArgumentException("analytics trusted proxies must be exact numeric IPs");
                }
                return address;
            }).collect(Collectors.toUnmodifiableSet());
    }

    /** 202 仅表示已入队，数据库故障和进程关闭可能丢失事件，队满立即返回 429。 */
    @PostMapping("/api/analytics/page-view")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void record(@RequestBody PageViewRequest payload, HttpServletRequest request) {
        if (payload.eventId() == null) {
            throw new IllegalArgumentException("eventId is required");
        }
        String path = payload.pagePath();
        if (path == null || !PAGE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("pagePath must be / or a public section anchor");
        }
        String peer = numericAddress(request.getRemoteAddr());
        String forwarded = peer != null && trustedProxies.contains(peer)
            ? numericAddress(request.getHeader("X-FinCore-Visitor-IP")) : null;
        boolean accepted = service.accept(new Visit(payload.eventId(), forwarded == null ? peer : forwarded, path,
            origin(payload.referrerOrigin()), clean(request.getHeader("User-Agent"), USER_AGENT_LIMIT),
            clean(request.getHeader("Accept-Language"), LANGUAGE_LIMIT)));
        if (!accepted) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "analytics queue is full");
        }
    }

    /** 坏 JSON 不进入默认异常日志，避免把客户端提交的任意字段值打印到日志。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Void> unreadableRequest() {
        return ResponseEntity.badRequest().build();
    }

    /** 去掉控制字符并按 Unicode 字符截断，避免不可打印内容或半个代理字符入库。 */
    private static String clean(String value, int limit) {
        if (value == null) {
            return null;
        }
        StringBuilder result = new StringBuilder(limit);
        value.codePoints().filter(point -> !Character.isISOControl(point)).limit(limit)
            .forEach(result::appendCodePoint);
        return result.toString();
    }

    /** 只保留 HTTP 来源的 scheme、host、port，删除凭证、路径、查询和锚点。 */
    private static String origin(String value) {
        if (value == null || value.length() > REFERRER_INPUT_LIMIT) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                return null;
            }
            if (!ORIGIN_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
                return null;
            }
            String result = scheme.toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
            return result.length() <= ORIGIN_LIMIT ? result : null;
        } catch (IllegalArgumentException exception) {
            // 无效来源直接舍弃，不把攻击输入写入响应或日志。
            return null;
        }
    }

    /** 先限制为数字字面量再解析，永远不通过 DNS 解析客户端提供的主机名。 */
    private static String numericAddress(String value) {
        if (value == null || value.length() > IP_LIMIT || !NUMERIC_IP.matcher(value).matches()) {
            return null;
        }
        if (value.indexOf(IPV6_SEPARATOR) < 0) {
            String[] parts = value.split("\\.", -1);
            if (parts.length != IPV4_PARTS) {
                return null;
            }
            for (String part : parts) {
                if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) {
                    return null;
                }
            }
        }
        try {
            return InetAddress.getByName(value).getHostAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    /** 客户端仅能声明单次事件编号、无查询路径和来源 origin，时间与 IP 由服务端决定。 */
    public record PageViewRequest(UUID eventId, String pagePath, String referrerOrigin) {
    }
}
