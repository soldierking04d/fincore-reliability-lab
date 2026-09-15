package dev.fincore.chain.readonly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * 真实链只读证据与无签名模拟的检查边界，不依赖 PAPER 账本，也不输出可执行授权。
 * 节点响应必须具备精确类型和足够新鲜的上下文；不知道不能变成零余额或成功。
 * 每次操作均为有界顺序调用，无自动重试；未知结果由调用方展示，不改持仓、不释放资金。
 */
public final class SolanaReadiness {
    public static final String CLASSIC_TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigInteger U64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private final Rpc rpc;
    private final Clock clock;

    /** 测试注入只读结果；正式入口只绑定固定网络的 ReadOnlyRpc，不接受网页提供的 URL。 */
    @FunctionalInterface public interface Rpc {
        JsonNode call(String method, JsonNode params) throws IOException, InterruptedException;
    }

    public SolanaReadiness(Rpc rpc, Clock clock) {
        this.rpc = Objects.requireNonNull(rpc);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 地址、精度来自独立资产配置，而不是读取节点结果后自动批准同一结果。 */
    public record MintPolicy(String address, int decimals, boolean requireFixedSupply) {
        public MintPolicy {
            Base58.address(address);
            if (decimals < 0 || decimals > 18) throw new IllegalArgumentException("当前资产合同仅支持 0..18 位精度");
        }
    }

    public record MintSnapshot(String address, long slot, BigInteger supply, int decimals,
                               String mintAuthority, String freezeAuthority, String dataSha256) { }

    /** blockhash 与最后有效高度成对保存；时间只是本地证据期限，不能替代区块高度语义。 */
    public record Blockhash(String hash, long lastValidBlockHeight, long contextSlot, Instant fetchedAt) {
        public Blockhash {
            Base58.address(hash);
            Objects.requireNonNull(fetchedAt);
            if (lastValidBlockHeight < 1 || contextSlot < 1) throw new IllegalArgumentException("无效区块上下文");
        }
    }

    /**
     * 上游必须独立给出完整指令及账户权限合同，不能从不可信候选字节自动生成批准合同。
     * 此处仅限制待模拟内容；即使相符也不代表程序安全、能卖出或已获资金执行授权。
     */
    public record SimulationPolicy(List<UnsignedTransactionGuard.Account> accounts,
                                   List<UnsignedTransactionGuard.Instruction> instructions,
                                   Blockhash blockhash, BigInteger maxFee, long maxUnits) {
        public SimulationPolicy {
            accounts = List.copyOf(accounts);
            instructions = List.copyOf(instructions);
            Objects.requireNonNull(blockhash);
            Objects.requireNonNull(maxFee);
            if (accounts.isEmpty() || instructions.isEmpty() || maxFee.signum() < 0
                    || maxFee.compareTo(BigInteger.valueOf(100_000)) > 0 || maxUnits < 1 || maxUnits > 1_400_000) {
                throw new IllegalArgumentException("模拟检查合同超出本实验的有限预算");
            }
        }
    }

    /** executionAllowed 永远为 false：模拟不是授权，更不是 finalized 收据。 */
    public record SimulationReport(String outcome, String wireSha256, String messageSha256,
                                   long slot, BigInteger estimatedFee, long unitsConsumed) {
        public boolean executionAllowed() { return false; }
    }

    /**
     * 原始二进制解析经典 SPL Mint，按 u64 精确读取供应量；默认拒绝所有未适配扩展。
     * Token-2022 不伪装成经典82字节Mint，冻结权限和不匹配精度均明确阻断。
     */
    public MintSnapshot inspectMint(MintPolicy policy, long minContextSlot) throws IOException, InterruptedException {
        Objects.requireNonNull(policy);
        require(minContextSlot >= 0, "无效最小 slot");
        ObjectNode config = config("finalized", minContextSlot).put("encoding", "base64");
        JsonNode result = rpc.call("getAccountInfo", params(policy.address(), config));
        long slot = contextSlot(result, minContextSlot);
        JsonNode value = object(result.get("value"), "Mint 不存在或账户响应缺失");
        require(value.path("executable").isBoolean() && !value.get("executable").booleanValue(), "Mint 不能是可执行程序");
        require(CLASSIC_TOKEN.equals(text(value.get("owner"))), "非经典 Token Program；Token-2022/未知 owner 尚未适配");
        integer(value.get("lamports"), U64_MAX, "账户 lamports 缺失或非 u64");
        JsonNode data = value.get("data");
        require(data != null && data.isArray() && data.size() == 2 && "base64".equals(text(data.get(1))), "只接受完整 base64 账户数据");
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(text(data.get(0))); }
        catch (IllegalArgumentException badEncoding) { throw new IOException("Mint base64 无效", badEncoding); }
        require(bytes.length == 82, "经典 Mint 长度必须为 82；拒绝 Token Account 和未知扩展");
        String mintAuthority = authority(bytes, 0);
        String freezeAuthority = authority(bytes, 46);
        require(bytes[45] == 1, "Mint 尚未初始化或初始化标记无效");
        int decimals = Byte.toUnsignedInt(bytes[44]);
        require(decimals == policy.decimals(), "Mint 精度与独立资产合同不符");
        require(freezeAuthority == null, "Mint 仍有冻结权限，本实验拒绝准入");
        require(!policy.requireFixedSupply() || mintAuthority == null, "Mint 仍有增发权限，本实验拒绝准入");
        byte[] bigEndianSupply = new byte[8];
        for (int i = 0; i < 8; i++) bigEndianSupply[i] = bytes[43 - i];
        return new MintSnapshot(policy.address(), slot, new BigInteger(1, bigEndianSupply), decimals,
            mintAuthority, freezeAuthority, sha256(bytes));
    }

    public Blockhash fetchBlockhash() throws IOException, InterruptedException {
        JsonNode result = rpc.call("getLatestBlockhash", params(config("confirmed", 0)));
        long slot = contextSlot(result, 1);
        JsonNode value = object(result.get("value"), "缺少 blockhash");
        return new Blockhash(text(value.get("blockhash")), positiveLong(value.get("lastValidBlockHeight"), "无效有效高度"), slot, clock.instant());
    }

    /**
     * 对已按完整合同核对的零签名字节调用 simulateTransaction。确切字节的摘要进入报告；
     * 不替换blockhash、不验签、不发送交易。费用仅为同消息估算，不能作为成交费用入账。
     */
    public SimulationReport simulate(byte[] candidate, SimulationPolicy policy) throws IOException, InterruptedException {
        byte[] wire = Objects.requireNonNull(candidate).clone();
        Objects.requireNonNull(policy);
        fresh(policy.blockhash());
        String wireDigest = UnsignedTransactionGuard.verify(wire, policy.blockhash().hash(), policy.accounts(), policy.instructions());
        var parsed = UnsignedTransactionGuard.inspect(wire);
        long height = positiveLong(rpc.call("getBlockHeight", params(config("confirmed", policy.blockhash().contextSlot()))), "无效区块高度");
        require(height <= policy.blockhash().lastValidBlockHeight(), "blockhash 已过最后有效高度，停止模拟");
        JsonNode valid = rpc.call("isBlockhashValid", params(policy.blockhash().hash(), config("confirmed", policy.blockhash().contextSlot())));
        contextSlot(valid, policy.blockhash().contextSlot());
        require(valid.path("value").isBoolean() && valid.get("value").booleanValue(), "blockhash 不再有效");
        // Guard 已要求 canonical shortvec=1 和一个64字节全零签名，偏移65是已验证的线格式。
        String message = Base64.getEncoder().encodeToString(Arrays.copyOfRange(wire, 65, wire.length));
        JsonNode feeResult = rpc.call("getFeeForMessage", params(message, config("confirmed", policy.blockhash().contextSlot())));
        contextSlot(feeResult, policy.blockhash().contextSlot());
        BigInteger fee = integer(feeResult.get("value"), U64_MAX, "无法确定消息费用，停止模拟");
        require(fee.compareTo(policy.maxFee()) <= 0, "消息费用超出模拟合同");
        fresh(policy.blockhash());
        ObjectNode simulationConfig = config("confirmed", policy.blockhash().contextSlot())
            .put("encoding", "base64").put("sigVerify", false).put("replaceRecentBlockhash", false).put("innerInstructions", true);
        JsonNode simulated = rpc.call("simulateTransaction", params(Base64.getEncoder().encodeToString(wire), simulationConfig));
        long slot = contextSlot(simulated, policy.blockhash().contextSlot());
        JsonNode value = object(simulated.get("value"), "模拟响应 value 缺失");
        require(value.has("err"), "模拟响应缺少 err，不能判成功");
        require(!value.hasNonNull("replacementBlockhash"), "节点替换了 blockhash，结果不匹配核对字节");
        fresh(policy.blockhash());
        if (!value.get("err").isNull()) {
            return new SimulationReport("REJECTED", wireDigest, parsed.messageSha256(), slot, fee, -1);
        }
        long units = nonnegativeLong(value.get("unitsConsumed"), "缺少真实模拟计算量");
        require(units <= policy.maxUnits(), "模拟计算量超出合同");
        return new SimulationReport("SIMULATED", wireDigest, parsed.messageSha256(), slot, fee, units);
    }

    private void fresh(Blockhash blockhash) throws IOException {
        Instant now = clock.instant();
        require(!blockhash.fetchedAt().isAfter(now) && now.isBefore(blockhash.fetchedAt().plusSeconds(30)), "检查合同超过30秒或时间在未来，必须重新获取并核对");
    }

    private static String authority(byte[] bytes, int offset) throws IOException {
        require(bytes[offset + 1] == 0 && bytes[offset + 2] == 0 && bytes[offset + 3] == 0
            && (bytes[offset] == 0 || bytes[offset] == 1), "Mint COption 标记无效");
        return bytes[offset] == 0 ? null : Base58.encode(Arrays.copyOfRange(bytes, offset + 4, offset + 36));
    }

    private static long contextSlot(JsonNode result, long minimum) throws IOException {
        object(result, "RPC result 缺失");
        long slot = positiveLong(result.path("context").get("slot"), "RPC context.slot 缺失或类型错误");
        require(slot >= minimum, "节点上下文落后于要求，不使用旧快照");
        return slot;
    }

    private static BigInteger integer(JsonNode node, BigInteger maximum, String error) throws IOException {
        require(node != null && node.isIntegralNumber(), error);
        BigInteger value = node.bigIntegerValue();
        require(value.signum() >= 0 && value.compareTo(maximum) <= 0, error);
        return value;
    }
    private static long nonnegativeLong(JsonNode node, String error) throws IOException {
        return integer(node, BigInteger.valueOf(Long.MAX_VALUE), error).longValueExact();
    }
    private static long positiveLong(JsonNode node, String error) throws IOException {
        long value = nonnegativeLong(node, error); require(value > 0, error); return value;
    }
    private static JsonNode object(JsonNode node, String error) throws IOException {
        require(node != null && node.isObject(), error); return node;
    }
    private static String text(JsonNode node) throws IOException {
        require(node != null && node.isTextual() && !node.textValue().isEmpty(), "预期非空文本字段"); return node.textValue();
    }
    private static ObjectNode config(String commitment, long minContextSlot) {
        return JSON.createObjectNode().put("commitment", commitment).put("minContextSlot", minContextSlot);
    }
    private static ArrayNode params(Object... values) {
        ArrayNode params = JSON.createArrayNode();
        for (Object value : values) params.add(value instanceof JsonNode node ? node : JSON.valueToTree(value));
        return params;
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
