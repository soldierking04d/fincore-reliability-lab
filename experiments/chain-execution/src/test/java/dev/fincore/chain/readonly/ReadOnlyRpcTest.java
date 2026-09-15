package dev.fincore.chain.readonly;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 全部测试注入内存响应；不连接真实 RPC，不构造签名器或广播交易。 */
class ReadOnlyRpcTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEVNET = "EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG";
    private static final String MAINNET = "5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test void fixedClusterEndpointAndFullGenesisAreCheckedBeforeEveryRead() throws Exception {
        List<String> methods = new ArrayList<>();
        var transport = scripted(request -> {
            methods.add(request.get("method").textValue());
            return ok(request, methods.getLast().equals("getGenesisHash") ? '"' + DEVNET + '"' : "123");
        });
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, transport, TIMEOUT)) {
            assertEquals(ReadOnlyRpc.Cluster.DEVNET, rpc.cluster());
            assertEquals(URI.create("https://api.devnet.solana.com"), rpc.endpoint());
            assertEquals(123, rpc.call("getBlockHeight", array()).intValue());
            assertEquals(123, rpc.call("getBlockHeight", array()).intValue());
            assertEquals(List.of("getGenesisHash", "getBlockHeight", "getGenesisHash", "getBlockHeight"), methods);
        }
    }

    @Test void wrongGenesisStopsBeforeTargetMethod() {
        AtomicInteger calls = new AtomicInteger();
        var transport = scripted(request -> { calls.incrementAndGet(); return ok(request, '"' + MAINNET + '"'); });
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, transport, TIMEOUT)) {
            assertThrows(IOException.class, () -> rpc.call("getAccountInfo", array()));
            assertEquals(1, calls.get());
        }
    }

    @Test void fullMainnetHashAcceptedAndTruncatedChainIdentifierRejected() throws Exception {
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.MAINNET, scripted(r -> ok(r, '"' + MAINNET + '"')), TIMEOUT)) {
            assertEquals(URI.create("https://api.mainnet.solana.com"), rpc.endpoint());
            assertEquals(MAINNET, rpc.call("getGenesisHash", array()).textValue());
        }
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.MAINNET,
            scripted(r -> ok(r, "\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp\"")), TIMEOUT)) {
            assertThrows(IOException.class, () -> rpc.call("getGenesisHash", array()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sendTransaction", "sendRawTransaction", "requestAirdrop", "signTransaction", "getBalance", "getgenesisHash"})
    void forbiddenMethodsNeverReachTransport(String method) {
        AtomicInteger calls = new AtomicInteger();
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET,
            scripted(r -> { calls.incrementAndGet(); return ok(r, "0"); }), TIMEOUT)) {
            assertThrows(IllegalArgumentException.class, () -> rpc.call(method, array()));
            assertEquals(0, calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":1}",
        "{\"jsonrpc\":\"2.0\",\"id\":999,\"result\":1}",
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":1}",
        "{\"jsonrpc\":\"2.0\",\"id\":1}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1,\"result\":2}",
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1} {}",
        "[{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":1}]"
    })
    void invalidEnvelopesCannotBecomeSuccess(String body) {
        // 除受测的信封错误外，result 本身必须能通过 genesis 检查，避免靠其他拒绝分支误过测试。
        String invalidEnvelope = body.replace("\"result\":1", "\"result\":\"" + DEVNET + "\"")
            .replace("\"result\":2", "\"result\":\"" + DEVNET + "\"");
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, scripted(r -> raw(200, invalidEnvelope)), TIMEOUT)) {
            assertThrows(IOException.class, () -> rpc.call("getGenesisHash", array()));
        }
    }

    @Test void remoteErrorsDoNotExposeOriginalPayloadOrRetry() {
        AtomicInteger calls = new AtomicInteger();
        var transport = scripted(r -> {
            calls.incrementAndGet();
            return raw(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-1,\"message\":\"private-rpc-data\"}}");
        });
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, transport, TIMEOUT)) {
            var error = assertThrows(IOException.class, () -> rpc.call("getGenesisHash", array()));
            assertFalse(error.toString().contains("private-rpc-data"));
            assertNull(error.getCause());
            assertEquals(1, calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 403, 429, 500})
    void nonSuccessHttpDoesNotRedirectOrRetry(int status) {
        AtomicInteger calls = new AtomicInteger();
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET,
            scripted(r -> { calls.incrementAndGet(); return raw(status, "remote body must not appear"); }), TIMEOUT)) {
            var error = assertThrows(IOException.class, () -> rpc.call("getGenesisHash", array()));
            assertFalse(error.toString().contains("remote body"));
            assertEquals(1, calls.get());
        }
    }

    @Test void oversizedResponseRejectedEvenForInjectedTransport() {
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET,
            scripted(r -> new ReadOnlyRpc.Reply(200, new byte[1_048_577])), TIMEOUT)) {
            assertThrows(IOException.class, () -> rpc.call("getGenesisHash", array()));
        }
    }

    @Test void bodySubscriberCancelsBeforeAccumulatingBeyondOneMiB() {
        var subscriber = new ReadOnlyRpc.LimitedBodySubscriber(1_048_576);
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long count) { }
            @Override public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1_048_576])));
        assertFalse(subscriber.getBody().toCompletableFuture().isDone(), "Body is incomplete until onComplete");
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertTrue(cancelled.get());
        assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }

    @Test void incompleteBodyTimesOutAndCancelsUnderlyingExchange() {
        AtomicBoolean cancelled = new AtomicBoolean();
        var waitingBody = new CompletableFuture<ReadOnlyRpc.Reply>();
        ReadOnlyRpc.Transport transport = (endpoint, request, timeout) ->
            new ReadOnlyRpc.Exchange(waitingBody, () -> { cancelled.set(true); waitingBody.cancel(true); });
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, transport, Duration.ofMillis(80))) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThrows(IOException.class, () -> rpc.call("getGenesisHash", array())));
            assertTrue(cancelled.get());
        }
    }

    @Test void fifthConcurrentCallIsRejectedImmediatelyAndDoesNotSend() throws Exception {
        var entered = new CountDownLatch(4);
        List<CompletableFuture<ReadOnlyRpc.Reply>> exchanges = java.util.Collections.synchronizedList(new ArrayList<>());
        ReadOnlyRpc.Transport transport = (endpoint, request, timeout) -> {
            var future = new CompletableFuture<ReadOnlyRpc.Reply>(); exchanges.add(future); entered.countDown();
            return new ReadOnlyRpc.Exchange(future, () -> future.cancel(true));
        };
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, transport, Duration.ofSeconds(5));
             var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                try { rpc.call("getGenesisHash", array()); } catch (IOException | InterruptedException expected) { }
            })).toList();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThrows(IOException.class, () -> rpc.call("getGenesisHash", array())));
            assertEquals(4, exchanges.size());
            rpc.close();
            for (var task : tasks) task.get(2, TimeUnit.SECONDS);
        }
    }

    @Test void simulateRequiresPinnedConfigurationBeforeGenesisOrSimulation() {
        AtomicInteger calls = new AtomicInteger();
        var parameters = array().add("AAAA");
        parameters.addObject().put("encoding", "base64").put("sigVerify", false)
            .put("replaceRecentBlockhash", true).put("commitment", "confirmed");
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET,
            scripted(r -> { calls.incrementAndGet(); return ok(r, "0"); }), TIMEOUT)) {
            assertThrows(IllegalArgumentException.class, () -> rpc.call("simulateTransaction", parameters));
            assertEquals(0, calls.get());
        }
    }

    @Test void validSimulationConfigurationIsPreservedWithoutMutatingCaller() throws Exception {
        var parameters = array().add(unsignedMemo());
        parameters.addObject().put("encoding", "base64").put("sigVerify", false)
            .put("replaceRecentBlockhash", false).put("commitment", "confirmed");
        var before = parameters.deepCopy();
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, scripted(r -> {
            if (r.get("method").textValue().equals("getGenesisHash")) return ok(r, '"' + DEVNET + '"');
            assertEquals(before, r.get("params"));
            return ok(r, "{\"context\":{\"slot\":1},\"value\":{\"err\":null}}");
        }), TIMEOUT)) {
            assertTrue(rpc.call("simulateTransaction", parameters).get("value").has("err"));
            assertEquals(before, parameters);
        }
    }

    @Test void excessiveJsonDepthNumericAndStringLengthsAreRejectedAfterValidGenesis() {
        for (String value : List.of("[".repeat(40) + "0" + "]".repeat(40), "9".repeat(100), '"' + "x".repeat(300_000) + '"')) {
            try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, scripted(r -> ok(r,
                r.get("method").textValue().equals("getGenesisHash") ? '"' + DEVNET + '"' : value)), TIMEOUT)) {
                assertThrows(IOException.class, () -> rpc.call("getAccountInfo", array()));
            }
        }
    }

    @Test void excessiveOutboundContainerWidthIsRejectedBeforeTransport() {
        var huge = array();
        for (int index = 0; index < 65_537; index++) huge.addNull();
        AtomicInteger calls = new AtomicInteger();
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET,
            scripted(r -> { calls.incrementAndGet(); return ok(r, "0"); }), TIMEOUT)) {
            assertThrows(IllegalArgumentException.class, () -> rpc.call("getMultipleAccounts", huge));
            assertEquals(0, calls.get());
        }
    }

    @Test void nonzeroSignatureCannotBypassGuardThroughPublicCall() {
        byte[] wire = Base64.getDecoder().decode(unsignedMemo());
        wire[1] = 1;
        var parameters = array().add(Base64.getEncoder().encodeToString(wire));
        parameters.addObject().put("encoding", "base64").put("sigVerify", false)
            .put("replaceRecentBlockhash", false).put("commitment", "confirmed");
        AtomicInteger calls = new AtomicInteger();
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET,
            scripted(r -> { calls.incrementAndGet(); return ok(r, "0"); }), TIMEOUT)) {
            assertThrows(IllegalArgumentException.class, () -> rpc.call("simulateTransaction", parameters));
            assertEquals(0, calls.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdk.httpclient.disableRetryConnect", "jdk.httpclient.enableAllMethodRetry", "jdk.httpclient.redirects.retrylimit"})
    void publicConstructorRequiresExplicitStartupRetryGuards(String missingProperty) {
        java.util.Map<String, String> saved = saveNetworkProperties();
        try {
            setSafeNetworkProperties();
            System.clearProperty(missingProperty);
            assertThrows(IllegalStateException.class, () -> new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET));
            System.setProperty(missingProperty, "unsafe");
            assertThrows(IllegalStateException.class, () -> new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET));
        } finally { restoreNetworkProperties(saved); }
    }

    @Test void publicConstructorRejectsRawHttpLoggingWithoutNetwork() {
        java.util.Map<String, String> saved = saveNetworkProperties();
        try {
            setSafeNetworkProperties();
            System.setProperty("jdk.httpclient.HttpClient.log", "content");
            assertThrows(IllegalStateException.class, () -> new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET));
        } finally { restoreNetworkProperties(saved); }
    }

    @Test void unavailableTransactionDoesNotBecomeAnExecutionFailureOrZero() {
        try (var rpc = new ReadOnlyRpc(ReadOnlyRpc.Cluster.DEVNET, scripted(r ->
            ok(r, r.get("method").textValue().equals("getGenesisHash") ? '"' + DEVNET + '"' : "null")), TIMEOUT)) {
            var error = assertThrows(IOException.class, () -> rpc.call("getTransaction", array()));
            assertEquals("RPC_RESULT_UNAVAILABLE", error.getMessage());
        }
    }

    private static ArrayNode array() { return JSON.createArrayNode(); }
    private static String unsignedMemo() {
        byte[] payer = new byte[32]; payer[0] = 3;
        byte[] blockhash = new byte[32]; blockhash[0] = 4;
        return Base64.getEncoder().encodeToString(UnsignedTransactionGuard.memoProbe(
            Base58.encode(payer), Base58.encode(blockhash), "RPC transport fixture"));
    }
    private static java.util.Map<String, String> saveNetworkProperties() {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (String key : List.of("jdk.httpclient.disableRetryConnect", "jdk.httpclient.enableAllMethodRetry",
            "jdk.httpclient.redirects.retrylimit", "jdk.httpclient.HttpClient.log")) values.put(key, System.getProperty(key));
        return values;
    }
    private static void setSafeNetworkProperties() {
        System.setProperty("jdk.httpclient.disableRetryConnect", "true");
        System.setProperty("jdk.httpclient.enableAllMethodRetry", "false");
        System.setProperty("jdk.httpclient.redirects.retrylimit", "1");
        System.clearProperty("jdk.httpclient.HttpClient.log");
    }
    private static void restoreNetworkProperties(java.util.Map<String, String> saved) {
        saved.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
    }
    private static ReadOnlyRpc.Reply ok(JsonNode request, String result) {
        return raw(200, "{\"jsonrpc\":\"2.0\",\"id\":" + request.get("id").longValue() + ",\"result\":" + result + "}");
    }
    private static ReadOnlyRpc.Reply raw(int status, String body) {
        return new ReadOnlyRpc.Reply(status, body.getBytes(StandardCharsets.UTF_8));
    }
    private static ReadOnlyRpc.Transport scripted(Function<JsonNode, ReadOnlyRpc.Reply> function) {
        return (endpoint, request, timeout) -> {
            CompletableFuture<ReadOnlyRpc.Reply> future = new CompletableFuture<>();
            try { future.complete(function.apply(JSON.readTree(request))); }
            catch (IOException failure) { future.completeExceptionally(failure); }
            return new ReadOnlyRpc.Exchange(future, () -> future.cancel(true));
        };
    }
}
