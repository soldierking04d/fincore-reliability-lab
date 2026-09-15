package dev.fincore.chain.readonly;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 固定官方 HTTPS 端点的只读 RPC 传输，不包含钱包、签名器、空投或广播方法。
 * 每次读取前核对完整 genesis；所有实例总计最多四个在途调用，超额立即拒绝。
 * 单次调用的集群检查和实际请求共用十秒截止时间，包含完整响应体读取。
 * 模拟交易在此公开边界检查全零签名及 legacy 结构，上层另按独立业务合同逐字节批准内容。
 */
public final class ReadOnlyRpc implements AutoCloseable {
    /** 仅支持两个预先审阅的官方集群，不接受配置文件或调用参数提供任意 URL。 */
    public enum Cluster { DEVNET, MAINNET }

    private static final Set<String> METHODS = Set.of("getGenesisHash", "getAccountInfo", "getMultipleAccounts",
        "getLatestBlockhash", "getBlockHeight", "isBlockhashValid", "getFeeForMessage",
        "getSignatureStatuses", "getTransaction", "simulateTransaction");
    private static final int RESPONSE_LIMIT = 1_048_576;
    private static final int REQUEST_LIMIT = 65_536;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Semaphore IN_FLIGHT = new Semaphore(4);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxDocumentLength(RESPONSE_LIMIT)
            .maxStringLength(262_144).maxNumberLength(80).maxNameLength(128).maxTokenCount(100_000).build())
        .streamWriteConstraints(StreamWriteConstraints.builder().maxNestingDepth(32).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final Cluster cluster;
    private final URI endpoint;
    private final String expectedGenesis;
    private final Duration timeout;
    private final Transport transport;
    private final AtomicLong sequence = new AtomicLong();
    private final Set<Exchange> active = ConcurrentHashMap.newKeySet();
    private final Object lifecycle = new Object();
    private volatile boolean closed;

    /** 创建固定集群客户端；构造阶段不发网络请求，也不读取本地钱包或凭证。 */
    public ReadOnlyRpc(Cluster cluster) {
        this(cluster, productionTransport(cluster), CALL_TIMEOUT);
    }

    /** 包级注入仅用于离线故障测试，生产公开接口没有端点覆盖或放宽截止时间的选项。 */
    ReadOnlyRpc(Cluster cluster, Transport transport, Duration timeout) {
        this.cluster = Objects.requireNonNull(cluster, "cluster");
        this.transport = Objects.requireNonNull(transport, "transport");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.compareTo(CALL_TIMEOUT) > 0)
            throw new IllegalArgumentException("RPC_TIMEOUT_INVALID");
        this.timeout = timeout;
        endpoint = URI.create(cluster == Cluster.DEVNET ? "https://api.devnet.solana.com" : "https://api.mainnet.solana.com");
        // 完整 hash 来源：solana-labs/solana sdk/src/genesis_config.rs，不能使用截断的 CAIP 标识。
        expectedGenesis = cluster == Cluster.DEVNET ? "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG"
            : "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d";
    }

    /** 返回所选择的固定集群，供只读证据报告使用。 */
    public Cluster cluster() { return cluster; }

    /** 返回实际配置的固定官方端点，不包含密钥、查询凭证或可变路径。 */
    public URI endpoint() { return endpoint; }

    /**
     * 返回严格 JSON-RPC 信封中的 result 节点。缺失或顶层 null 报 RPC_RESULT_UNAVAILABLE，
     * 仅表示证据不可用，不能据此推断交易失败、释放资金或重发。嵌套 null 保留给上层解释。
     * 本层没有失败重试循环；HTTP 重定向禁止，未知方法在任何网络访问之前拒绝。
     */
    public JsonNode call(String method, JsonNode params) throws IOException, InterruptedException {
        if (method == null || !METHODS.contains(method)) throw new IllegalArgumentException("RPC_METHOD_FORBIDDEN");
        ArrayNode copied = validateParams(method, params);
        if (closed) throw new IOException("RPC_CLOSED");
        if (!IN_FLIGHT.tryAcquire()) throw new IOException("RPC_OVERLOADED");
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            JsonNode genesis = exchange("getGenesisHash", JSON.createArrayNode(), deadline);
            if (!genesis.isTextual() || !expectedGenesis.equals(genesis.textValue())) throw new IOException("RPC_CLUSTER_MISMATCH");
            if (method.equals("getGenesisHash")) return genesis;
            return exchange(method, copied, deadline);
        } finally { IN_FLIGHT.release(); }
    }

    private JsonNode exchange(String method, ArrayNode params, long deadline) throws IOException, InterruptedException {
        long id = sequence.incrementAndGet();
        if (id <= 0) throw new IOException("RPC_ID_EXHAUSTED");
        byte[] body;
        try {
            var request = JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
            request.set("params", params);
            body = JSON.writeValueAsBytes(request);
        } catch (IOException | RuntimeException invalid) { throw new IOException("RPC_REQUEST_INVALID"); }
        if (body.length > REQUEST_LIMIT) throw new IOException("RPC_REQUEST_TOO_LARGE");
        Exchange exchange;
        synchronized (lifecycle) {
            if (closed) throw new IOException("RPC_CLOSED");
            try { exchange = transport.send(endpoint, body, remaining(deadline)); }
            catch (RuntimeException failure) { throw new IOException("RPC_TRANSPORT_FAILED"); }
            active.add(exchange);
        }
        try {
            Reply reply = exchange.future().get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
            if (reply == null || reply.statusCode() != 200) throw new IOException("RPC_HTTP_REJECTED");
            if (reply.body() == null || reply.body().length > RESPONSE_LIMIT) throw new IOException("RPC_RESPONSE_TOO_LARGE");
            JsonNode envelope;
            try { envelope = JSON.readTree(reply.body()); }
            catch (IOException | RuntimeException invalid) { throw new IOException("RPC_JSON_INVALID"); }
            remaining(deadline);
            if (envelope == null || !envelope.isObject() || !envelope.has("jsonrpc")
                || !envelope.get("jsonrpc").isTextual() || !"2.0".equals(envelope.get("jsonrpc").textValue())
                || !envelope.has("id") || !envelope.get("id").isIntegralNumber()
                || !envelope.get("id").canConvertToLong() || envelope.get("id").longValue() != id)
                throw new IOException("RPC_ENVELOPE_INVALID");
            if (envelope.has("error")) {
                // 不携带原始远端 error/message/data，也不挂接可能包含原文的解析异常。
                if (envelope.has("result")) throw new IOException("RPC_ENVELOPE_INVALID");
                throw new IOException("RPC_REMOTE_ERROR");
            }
            if (!envelope.has("result")) throw new IOException("RPC_ENVELOPE_INVALID");
            JsonNode result = envelope.get("result");
            if (result.isNull()) throw new IOException("RPC_RESULT_UNAVAILABLE");
            return result;
        } catch (TimeoutException timeoutFailure) {
            throw new HttpTimeoutException("RPC_DEADLINE_EXCEEDED");
        } catch (ExecutionException transportFailure) {
            throw new IOException("RPC_TRANSPORT_FAILED");
        } catch (CancellationException cancelled) {
            throw new IOException(closed ? "RPC_CLOSED" : "RPC_CANCELLED");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedException("RPC_INTERRUPTED");
        } finally {
            // 包含 body 未完成的情形；不仅取消 thenApply 包装后的 future，也取消底层 HTTP 请求。
            exchange.cancel().run();
            active.remove(exchange);
        }
    }

    private static Duration remaining(long deadline) throws HttpTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new HttpTimeoutException("RPC_DEADLINE_EXCEEDED");
        return Duration.ofNanos(nanos);
    }

    private static ArrayNode validateParams(String method, JsonNode params) {
        if (params == null || !params.isArray()) throw new IllegalArgumentException("RPC_PARAMS_MUST_BE_ARRAY");
        var pending = new ArrayDeque<NodeDepth>();
        pending.add(new NodeDepth(params, 1));
        int nodes = 0;
        long characters = 0;
        while (!pending.isEmpty()) {
            NodeDepth item = pending.removeLast();
            JsonNode node = item.node();
            if (++nodes > 100_000 || item.depth() > 30) throw new IllegalArgumentException("RPC_PARAMS_TOO_COMPLEX");
            if (node.isContainerNode() && node.size() > REQUEST_LIMIT - nodes - pending.size())
                throw new IllegalArgumentException("RPC_PARAMS_TOO_LARGE");
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (field.getKey().length() > 128) throw new IllegalArgumentException("RPC_PARAM_NAME_TOO_LONG");
                    characters += field.getKey().length();
                    pending.add(new NodeDepth(field.getValue(), item.depth() + 1));
                }
            } else if (node.isArray()) {
                for (JsonNode child : node) pending.add(new NodeDepth(child, item.depth() + 1));
            } else if (node.isTextual()) {
                characters += node.textValue().length();
            } else if (node.isNumber()) {
                int length = node.asText().length();
                if (length > 80) throw new IllegalArgumentException("RPC_PARAM_NUMBER_TOO_LONG");
                characters += length;
            } else if (!node.isBoolean() && !node.isNull()) {
                throw new IllegalArgumentException("RPC_PARAM_TYPE_INVALID");
            }
            if (characters + nodes > REQUEST_LIMIT) throw new IllegalArgumentException("RPC_PARAMS_TOO_LARGE");
        }
        ArrayNode copied = ((ArrayNode) params).deepCopy();
        if (method.equals("getGenesisHash") && !copied.isEmpty()) throw new IllegalArgumentException("RPC_GENESIS_PARAMS_FORBIDDEN");
        if (method.equals("simulateTransaction")) {
            if (copied.size() != 2 || !copied.get(0).isTextual() || !copied.get(1).isObject())
                throw new IllegalArgumentException("RPC_SIMULATION_PARAMS_INVALID");
            JsonNode config = copied.get(1);
            if (!textEquals(config, "encoding", "base64") || !textEquals(config, "commitment", "confirmed")
                || !falseBoolean(config, "sigVerify") || !falseBoolean(config, "replaceRecentBlockhash"))
                throw new IllegalArgumentException("RPC_SIMULATION_CONFIG_FORBIDDEN");
            String encoded = copied.get(0).textValue();
            if (encoded.isEmpty() || encoded.length() > 1644) throw new IllegalArgumentException("RPC_SIMULATION_SIZE_INVALID");
            try {
                byte[] wire = Base64.getDecoder().decode(encoded);
                if (wire.length == 0 || wire.length > 1232 || !Base64.getEncoder().encodeToString(wire).equals(encoded))
                    throw new IllegalArgumentException("RPC_SIMULATION_BASE64_INVALID");
                UnsignedTransactionGuard.inspect(wire);
            } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("RPC_SIMULATION_WIRE_INVALID"); }
        }
        return copied;
    }

    private static boolean textEquals(JsonNode object, String key, String value) {
        return object.has(key) && object.get(key).isTextual() && value.equals(object.get(key).textValue());
    }

    private static boolean falseBoolean(JsonNode object, String key) {
        return object.has(key) && object.get(key).isBoolean() && !object.get(key).booleanValue();
    }

    /** 取消仍在等待响应体的请求并停止 HTTP 客户端；不等待无期限的远端 body。 */
    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            for (Exchange exchange : active) exchange.cancel().run();
            transport.close();
        }
    }

    private record NodeDepth(JsonNode node, int depth) { }
    record Reply(int statusCode, byte[] body) { }
    record Exchange(CompletableFuture<Reply> future, Runnable cancel) { }

    @FunctionalInterface
    interface Transport extends AutoCloseable {
        Exchange send(URI endpoint, byte[] request, Duration timeout);
        @Override default void close() { }
    }

    private static Transport productionTransport(Cluster cluster) {
        Objects.requireNonNull(cluster, "cluster");
        // JDK 不保证运行时修改这些全局属性生效，调用方必须在独立 JVM 启动时明确配置。
        if (!"true".equals(System.getProperty("jdk.httpclient.disableRetryConnect"))
            || !"false".equals(System.getProperty("jdk.httpclient.enableAllMethodRetry"))
            || !"1".equals(System.getProperty("jdk.httpclient.redirects.retrylimit"))) {
            throw new IllegalStateException("RPC_STARTUP_RETRY_GUARDS_REQUIRED");
        }
        if (!System.getProperty("jdk.httpclient.HttpClient.log", "").isBlank()) {
            throw new IllegalStateException("RPC_RAW_HTTP_LOGGING_FORBIDDEN");
        }
        return new JdkTransport();
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();

        @Override public Exchange send(URI endpoint, byte[] body, Duration timeout) {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Accept-Encoding", "identity").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            AtomicReference<LimitedBodySubscriber> receiving = new AtomicReference<>();
            CompletableFuture<HttpResponse<byte[]>> original = client.sendAsync(request, information -> {
                var subscriber = new LimitedBodySubscriber(RESPONSE_LIMIT);
                receiving.set(subscriber);
                return subscriber;
            });
            CompletableFuture<Reply> result = original.thenApply(response -> new Reply(response.statusCode(), response.body()));
            return new Exchange(result, () -> {
                LimitedBodySubscriber subscriber = receiving.get();
                if (subscriber != null && !subscriber.getBody().toCompletableFuture().isDone()) subscriber.cancel();
                original.cancel(true);
                result.cancel(true);
            });
        }

        @Override public void close() { client.shutdownNow(); }
    }

    /** 响应按块计数，超过一 MiB 立即取消订阅；只有完整 onComplete 才完成 body future。 */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        LimitedBodySubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }

        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null || body.isDone()) { subscription.cancel(); return; }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override public synchronized void onNext(java.util.List<ByteBuffer> chunks) {
            if (body.isDone()) return;
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > limit - bytes.size()) {
                    fail("RPC_RESPONSE_TOO_LARGE");
                    return;
                }
                byte[] copy = new byte[chunk.remaining()];
                chunk.get(copy);
                bytes.writeBytes(copy);
            }
            if (subscription != null) subscription.request(1);
        }

        @Override public synchronized void onError(Throwable error) { fail("RPC_BODY_INCOMPLETE"); }
        @Override public synchronized void onComplete() { body.complete(bytes.toByteArray()); }
        synchronized void cancel() { fail("RPC_CANCELLED"); }

        private void fail(String code) {
            body.completeExceptionally(new IOException(code));
            if (subscription != null) subscription.cancel();
        }
    }
}
