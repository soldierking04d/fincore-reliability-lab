package dev.fincore.chain.readonly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 只读证据不能越级成为资金账本事实；缺失和未知数据必须显式失败。 */
class SolanaReadinessTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final String MINT = "So11111111111111111111111111111111111111112";
    static final String HASH = MINT; // 合成哈希仅用于本地合同测试，不声称是实时区块。
    static final String PAYER = "11111111111111111111111111111111";
    static final String TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";

    @Test void parsesClassicMintWithoutLosingUnsignedSupply() throws Exception {
        ObjectNode account = mint();
        byte[] bytes = Base64.getDecoder().decode(account.at("/value/data/0").textValue());
        for (int i = 36; i < 44; i++) bytes[i] = (byte) 255;
        data(account, bytes);
        var result = reader((m, p) -> account).inspectMint(new SolanaReadiness.MintPolicy(MINT, 9, true), 90);
        assertEquals(new BigInteger("18446744073709551615"), result.supply());
        assertNull(result.mintAuthority());
        assertNull(result.freezeAuthority());
        assertEquals(100, result.slot());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "token2022", "owner", "length", "frozen-authority", "mint-authority", "initialized", "decimals", "old-slot", "executable", "coption", "fractional-slot", "fractional-lamports"})
    void rejectsUnknownOrUnsafeMintEvidence(String fault) {
        ObjectNode account = mint();
        byte[] bytes = Base64.getDecoder().decode(account.at("/value/data/0").textValue());
        switch (fault) {
            case "missing" -> account.putNull("value");
            case "token2022" -> ((ObjectNode) account.get("value")).put("owner", "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb");
            case "owner" -> ((ObjectNode) account.get("value")).put("owner", PAYER);
            case "length" -> data(account, new byte[165]);
            case "frozen-authority" -> { bytes[46] = 1; data(account, bytes); }
            case "mint-authority" -> { bytes[0] = 1; data(account, bytes); }
            case "initialized" -> { bytes[45] = 0; data(account, bytes); }
            case "decimals" -> { bytes[44] = 6; data(account, bytes); }
            case "old-slot" -> ((ObjectNode) account.get("context")).put("slot", 89);
            case "executable" -> ((ObjectNode) account.get("value")).put("executable", true);
            case "coption" -> { bytes[0] = 2; data(account, bytes); }
            case "fractional-slot" -> ((ObjectNode) account.get("context")).put("slot", 100.5);
            case "fractional-lamports" -> ((ObjectNode) account.get("value")).put("lamports", 100.5);
            default -> throw new AssertionError(fault);
        }
        assertThrows(Exception.class, () -> reader((m, p) -> account)
            .inspectMint(new SolanaReadiness.MintPolicy(MINT, 9, true), 90));
    }

    @Test void callerCannotChangeMintAddressBehindPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new SolanaReadiness.MintPolicy("0x1234", 9, true));
    }

    @Test void simulatorUsesExactUnsignedBytesAndNeverAuthorizesExecution() throws Exception {
        Stub rpc = new Stub();
        byte[] wire = UnsignedTransactionGuard.memoProbe(PAYER, HASH, "fincore-simulation-only");
        var engine = reader(rpc);
        var report = engine.simulate(wire, policy(wire));
        assertEquals("SIMULATED", report.outcome());
        assertFalse(report.executionAllowed());
        assertEquals(BigInteger.valueOf(5000), report.estimatedFee());
        assertEquals(200, report.unitsConsumed());
        assertEquals(List.of("getBlockHeight", "isBlockhashValid", "getFeeForMessage", "simulateTransaction"), rpc.methods);
        JsonNode config = rpc.simulationParams.get(1);
        assertFalse(config.get("sigVerify").booleanValue());
        assertFalse(config.get("replaceRecentBlockhash").booleanValue());
        assertArrayEquals(wire, Base64.getDecoder().decode(rpc.simulationParams.get(0).textValue()));
    }

    @ParameterizedTest @ValueSource(strings = {"expired-height", "invalid-hash", "excess-fee", "null-fee", "excess-units", "missing-err", "missing-units", "old-context", "replacement"})
    void failsClosedAtEverySimulationBoundary(String fault) {
        Stub rpc = new Stub(); rpc.fault = fault;
        byte[] wire = UnsignedTransactionGuard.memoProbe(PAYER, HASH, "bounded-probe");
        assertThrows(Exception.class, () -> reader(rpc).simulate(wire, policy(wire)));
        assertFalse(rpc.methods.contains("sendTransaction"));
    }

    @Test void nodeFailureIsRejectedNotSuccessfulTrade() throws Exception {
        Stub rpc = new Stub(); rpc.fault = "node-error";
        byte[] wire = UnsignedTransactionGuard.memoProbe(PAYER, HASH, "probe");
        var report = reader(rpc).simulate(wire, policy(wire));
        assertEquals("REJECTED", report.outcome());
        assertFalse(report.executionAllowed());
        assertEquals(-1, report.unitsConsumed());
    }

    @Test void staleApprovalStopsBeforeAnyRpcRequest() {
        Stub rpc = new Stub();
        byte[] wire = UnsignedTransactionGuard.memoProbe(PAYER, HASH, "probe");
        var service = new SolanaReadiness(rpc, Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC));
        assertThrows(Exception.class, () -> service.simulate(wire, policy(wire)));
        assertTrue(rpc.methods.isEmpty());
    }

    @Test void changedWireStopsBeforeAnyRpcRequest() {
        Stub rpc = new Stub();
        byte[] wire = UnsignedTransactionGuard.memoProbe(PAYER, HASH, "probe");
        var policy = policy(wire); wire[wire.length - 1] ^= 1;
        assertThrows(Exception.class, () -> reader(rpc).simulate(wire, policy));
        assertTrue(rpc.methods.isEmpty());
    }

    static SolanaReadiness reader(SolanaReadiness.Rpc rpc) {
        return new SolanaReadiness(rpc, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    static SolanaReadiness.SimulationPolicy policy(byte[] wire) {
        var inspected = UnsignedTransactionGuard.inspect(wire);
        return new SolanaReadiness.SimulationPolicy(inspected.accounts(), inspected.instructions(),
            new SolanaReadiness.Blockhash(HASH, 150, 90, NOW), BigInteger.valueOf(10_000), 1_000);
    }
    static ObjectNode mint() {
        byte[] bytes = new byte[82];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(36, 100_000_000);
        bytes[44] = 9; bytes[45] = 1;
        ObjectNode result = JSON.createObjectNode(); result.putObject("context").put("slot", 100);
        result.putObject("value").put("owner", TOKEN).put("executable", false).put("lamports", 1_461_600);
        data(result, bytes); return result;
    }
    static void data(ObjectNode result, byte[] bytes) {
        ((ObjectNode) result.get("value")).putArray("data").add(Base64.getEncoder().encodeToString(bytes)).add("base64");
    }
    static class Stub implements SolanaReadiness.Rpc {
        String fault = "";
        List<String> methods = new ArrayList<>();
        JsonNode simulationParams;
        @Override public JsonNode call(String method, JsonNode params) throws java.io.IOException {
            methods.add(method);
            if (method.equals("getBlockHeight")) return JSON.getNodeFactory().numberNode(fault.equals("expired-height") ? 151 : 100);
            ObjectNode result = JSON.createObjectNode(); result.putObject("context").put("slot", fault.equals("old-context") ? 89 : 100);
            if (method.equals("isBlockhashValid")) return result.put("value", !fault.equals("invalid-hash"));
            if (method.equals("getFeeForMessage")) {
                if (fault.equals("null-fee")) return result.putNull("value");
                return result.put("value", fault.equals("excess-fee") ? 10_001 : 5000);
            }
            if (!method.equals("simulateTransaction")) throw new AssertionError("Unexpected method: " + method);
            simulationParams = params.deepCopy();
            ObjectNode value = result.putObject("value").putNull("err").put("unitsConsumed", 200);
            switch (fault) {
                case "node-error" -> { value.put("err", "AccountNotFound"); value.remove("unitsConsumed"); }
                case "excess-units" -> value.put("unitsConsumed", 1001);
                case "missing-err" -> value.remove("err");
                case "missing-units" -> value.remove("unitsConsumed");
                case "replacement" -> value.putObject("replacementBlockhash").put("blockhash", HASH);
                default -> { }
            }
            return result;
        }
    }
}
