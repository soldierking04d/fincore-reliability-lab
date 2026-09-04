package dev.fincore.exchange;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 交易API签名、时间窗口、权限、IP白名单、防重放和有界限频模型。
 *
 * <p>密钥仅在内存实验中使用，验证结果不会返回密钥或原始签名。生产系统应把密钥材料放入专用密钥管理
 * 服务，并使用分布式限频和可审计的密钥生命周期；本模型不替代WAF、DDoS防护或账户接管检测。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class TradingApiSecurity {
    /** 已登记的API凭据。 */
    private final Map<String, Credential> credentials = new HashMap<>();
    /** 时间窗口内已经接受的请求随机数。 */
    private final Map<String, Map<String, Instant>> nonces = new HashMap<>();
    /** 固定窗口限频计数。 */
    private final Map<String, Counter> counters = new HashMap<>();
    /** 接受的最大客户端时钟偏差。 */
    private final Duration receiveWindow;
    /** 每个固定窗口允许的请求数。 */
    private final int requestLimit;
    /** 固定限频窗口长度。 */
    private final Duration rateWindow;

    /** API权限。 */
    public enum Scope {
        /** 查询账户和订单。 */
        READ,
        /** 下单和撤单。 */
        TRADE,
        /** 提现等高风险资金动作。 */
        WITHDRAW
    }

    /** 安全校验结果。 */
    public enum Decision {
        /** 全部校验通过。 */
        ALLOWED,
        /** API Key不存在。 */
        UNKNOWN_KEY,
        /** 来源IP不在白名单。 */
        IP_DENIED,
        /** Key没有请求所需权限。 */
        SCOPE_DENIED,
        /** 客户端时间超出接收窗口。 */
        TIMESTAMP_EXPIRED,
        /** 请求随机数已经使用。 */
        REPLAYED_NONCE,
        /** HMAC签名不匹配。 */
        INVALID_SIGNATURE,
        /** 固定窗口请求额度已经用完。 */
        RATE_LIMITED
    }

    /**
     * 创建API安全模型。
     *
     * @param receiveWindow 请求最大时钟偏差
     * @param requestLimit 每个窗口允许的最大请求数
     * @param rateWindow 限频窗口长度
     */
    public TradingApiSecurity(Duration receiveWindow, int requestLimit,
                              Duration rateWindow) {
        if (receiveWindow == null || receiveWindow.isNegative() || receiveWindow.isZero()) {
            throw new IllegalArgumentException("接收窗口必须大于零");
        }
        if (requestLimit < 1 || rateWindow == null || rateWindow.isNegative()
            || rateWindow.isZero() || rateWindow.toSeconds() < 1L) {
            throw new IllegalArgumentException("限频参数不合法");
        }
        this.receiveWindow = receiveWindow;
        this.requestLimit = requestLimit;
        this.rateWindow = rateWindow;
    }

    /**
     * 注册一把实验API Key。
     *
     * @param apiKey API Key编号
     * @param secret HMAC密钥
     * @param scopes 允许权限
     * @param allowedIps 允许来源IP；空集合表示全部拒绝
     */
    public void register(String apiKey, byte[] secret, Set<Scope> scopes,
                         Set<String> allowedIps) {
        Objects.requireNonNull(apiKey, "apiKey");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(scopes, "scopes");
        Objects.requireNonNull(allowedIps, "allowedIps");
        if (apiKey.isBlank() || secret.length < 16) {
            throw new IllegalArgumentException("API Key或密钥不符合实验最低要求");
        }
        credentials.put(apiKey, new Credential(secret.clone(), Set.copyOf(scopes),
            Set.copyOf(allowedIps)));
    }

    /**
     * 按固定顺序验证请求；只有签名通过后才消费随机数和限频额度。
     *
     * @param request 已签名请求
     * @param now 服务端当前时间
     * @return 明确的安全决定
     */
    public synchronized Decision verify(SignedRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        Credential credential = credentials.get(request.apiKey());
        if (credential == null) {
            return Decision.UNKNOWN_KEY;
        }
        if (!credential.allowedIps().contains(request.sourceIp())) {
            return Decision.IP_DENIED;
        }
        if (!credential.scopes().contains(request.requiredScope())) {
            return Decision.SCOPE_DENIED;
        }
        Duration skew = Duration.between(request.timestamp(), now).abs();
        if (skew.compareTo(receiveWindow) > 0) {
            return Decision.TIMESTAMP_EXPIRED;
        }
        String expected = sign(credential.secret(), request.canonicalPayload());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            request.signature().getBytes(StandardCharsets.US_ASCII))) {
            return Decision.INVALID_SIGNATURE;
        }
        Map<String, Instant> keyNonces = nonces.computeIfAbsent(request.apiKey(),
            ignored -> new HashMap<>());
        keyNonces.entrySet().removeIf(entry -> entry.getValue().plus(receiveWindow).isBefore(now));
        if (keyNonces.containsKey(request.nonce())) {
            return Decision.REPLAYED_NONCE;
        }
        long window = now.getEpochSecond() / rateWindow.toSeconds();
        Counter counter = counters.get(request.apiKey());
        if (counter == null || counter.window() != window) {
            counter = new Counter(window, 0);
        }
        if (counter.count() >= requestLimit) {
            counters.put(request.apiKey(), counter);
            return Decision.RATE_LIMITED;
        }
        counters.put(request.apiKey(), new Counter(window, counter.count() + 1));
        keyNonces.put(request.nonce(), now);
        return Decision.ALLOWED;
    }

    /**
     * 为测试客户端生成与服务端一致的HMAC-SHA256签名。
     *
     * @param secret HMAC密钥
     * @param canonicalPayload 规范化请求串
     * @return 小写十六进制签名
     */
    public static String sign(byte[] secret, String canonicalPayload) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                canonicalPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("当前JVM不支持HmacSHA256", exception);
        }
    }

    /** API凭据内部快照。 */
    private record Credential(byte[] secret, Set<Scope> scopes, Set<String> allowedIps) {
        /** 防止调用方在构造后修改内部集合。 */
        private Credential {
            secret = secret.clone();
            scopes = new HashSet<>(scopes);
            allowedIps = new HashSet<>(allowedIps);
        }

        /** 返回只用于本类验签的密钥副本。 */
        @Override
        public byte[] secret() {
            return secret.clone();
        }
    }

    /** 固定限频窗口计数。 */
    private record Counter(long window, int count) {
    }

    /**
     * 已签名请求。
     *
     * <p>规范串包含方法、路径、正文摘要、时间戳和随机数，避免中间层对字段顺序的不同解释。</p>
     */
    public record SignedRequest(String apiKey, String sourceIp, Scope requiredScope,
                                String method, String path, String bodyHash,
                                Instant timestamp, String nonce, String signature) {
        /** 检查签名请求必要字段。 */
        public SignedRequest {
            Objects.requireNonNull(apiKey, "apiKey");
            Objects.requireNonNull(sourceIp, "sourceIp");
            Objects.requireNonNull(requiredScope, "requiredScope");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(bodyHash, "bodyHash");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(nonce, "nonce");
            Objects.requireNonNull(signature, "signature");
        }

        /** @return 签名双方必须完全一致的规范化请求串 */
        public String canonicalPayload() {
            return method.toUpperCase() + '\n' + path + '\n' + bodyHash + '\n'
                + timestamp.toEpochMilli() + '\n' + nonce;
        }
    }
}
