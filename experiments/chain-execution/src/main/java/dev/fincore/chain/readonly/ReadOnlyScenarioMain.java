package dev.fincore.chain.readonly;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * 独立无监听端口的只读验收入口。mainnet-readonly 只检查公开Mint；devnet-probe 只模拟Memo。
 * 不接受私钥/助记词、RPC URL、钱包连接、任意交易文件或广播选项。报告失败也保留原始观察。
 */
public final class ReadOnlyScenarioMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String WSOL = "So11111111111111111111111111111111111111112";
    // 仅公开发行账户，来源是之前发行记录中的mint字段；不是用户资金钱包或密钥。
    public static final String FCLAB = "AakW2qYEFRK5DunP7yNaXAr9eknBtLiEd9iMXAAC6Mck";
    private static final String MEMO_PROGRAM = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr";
    private ReadOnlyScenarioMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("mainnet-readonly") || args[0].equals("devnet-probe"))) {
            throw new IllegalArgumentException("用法：mainnet-readonly|devnet-probe 全新空输出目录；没有签名/广播模式");
        }
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        try (var entries = Files.list(output)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("输出目录不为空，拒绝覆盖旧证据");
        }
        boolean mainnet = args[0].equals("mainnet-readonly");
        ObjectNode report = JSON.createObjectNode().put("mode", args[0]).put("startedAt", Instant.now().toString())
            .put("executionAllowed", false).put("walletConnected", false).put("signed", false).put("broadcast", false);
        ArrayNode observations = JSON.createArrayNode();
        StringBuilder narrative = new StringBuilder("# Solana 只读接入与无签名模拟验收\n\n")
            .append("本报告来自真实 RPC 查询，不是纸面报价。未连接钱包、未读取私钥、未签名或广播；结果不写入 PAPER 持仓账本，也不代表买卖交易已经通过。\n\n");
        Exception failure = null;
        try (var rpc = new ReadOnlyRpc(mainnet ? ReadOnlyRpc.Cluster.MAINNET : ReadOnlyRpc.Cluster.DEVNET)) {
            report.put("endpoint", rpc.endpoint().toString());
            SolanaReadiness.Rpc recorded = (method, params) -> {
                ObjectNode observation = observations.addObject().put("method", method).put("startedAt", Instant.now().toString());
                observation.set("params", params.deepCopy());
                long started = System.nanoTime();
                try {
                    JsonNode result = rpc.call(method, params);
                    observation.set("result", result.deepCopy());
                    return result;
                } catch (IOException | InterruptedException problem) {
                    observation.put("outcome", "UNKNOWN_OR_REJECTED").put("errorType", problem.getClass().getSimpleName());
                    throw problem;
                } finally {
                    observation.put("durationMillis", (System.nanoTime() - started) / 1_000_000);
                }
            };
            String genesis = recorded.call("getGenesisHash", JSON.createArrayNode()).textValue();
            report.put("genesis", genesis);
            narrative.append("网络：").append(rpc.cluster()).append("；完整 genesis：`").append(genesis).append("`。\n\n");
            SolanaReadiness service = new SolanaReadiness(recorded, Clock.systemUTC());
            ArrayNode mints = report.putArray("mints");
            List<SolanaReadiness.MintPolicy> policies = mainnet
                ? List.of(new SolanaReadiness.MintPolicy(FCLAB, 6, true), new SolanaReadiness.MintPolicy(WSOL, 9, true))
                : List.of(new SolanaReadiness.MintPolicy(WSOL, 9, true));
            narrative.append("| 资产 | Mint | finalized slot | 精度 | 供应量 | 增发权 / 冻结权 |\n|---|---|---:|---:|---:|---|\n");
            for (var policy : policies) {
                var mint = service.inspectMint(policy, 0);
                ObjectNode row = mints.addObject().put("mint", mint.address()).put("slot", mint.slot())
                    .put("decimals", mint.decimals()).put("supplyAtomic", mint.supply().toString())
                    .put("dataSha256", mint.dataSha256()).putNull("mintAuthority").putNull("freezeAuthority");
                row.put("policyResult", "CLASSIC_MINT_CHECK_PASSED_NOT_TRADE_APPROVAL");
                String label = mint.address().equals(FCLAB) ? "FCLAB" : "WSOL";
                narrative.append('|').append(label).append('|').append(mint.address()).append('|').append(mint.slot())
                    .append('|').append(mint.decimals()).append('|').append(new BigDecimal(mint.supply(), mint.decimals()).toPlainString())
                    .append("|均为空|\n");
            }
            narrative.append("\nWSOL 原生 Mint 的 supply 字段不等于市场上所有包装 SOL 的总量，此表不用于估值或流动性判断。\n");
            if (!mainnet) {
                var blockhash = service.fetchBlockhash();
                String memo = "FinCore readonly simulation probe";
                // 故意使用公开的Mint数据账户而非付款钱包：该账户不能充当正常费用付款者。
                // 预期节点拒绝，用于证明真实模拟错误不会被包装成可执行交易。没有转账指令。
                byte[] wire = UnsignedTransactionGuard.memoProbe(WSOL, blockhash.hash(), memo);
                var contract = new SolanaReadiness.SimulationPolicy(
                    List.of(new UnsignedTransactionGuard.Account(WSOL, true, true),
                        new UnsignedTransactionGuard.Account(MEMO_PROGRAM, false, false)),
                    List.of(new UnsignedTransactionGuard.Instruction(MEMO_PROGRAM, List.of(),
                        Base64.getEncoder().encodeToString(memo.getBytes(StandardCharsets.US_ASCII)))),
                    blockhash, BigInteger.valueOf(10_000), 10_000);
                var simulated = service.simulate(wire, contract);
                report.set("simulation", JSON.valueToTree(simulated));
                report.put("simulationExecutionAllowed", simulated.executionAllowed());
                Files.writeString(output.resolve("unsigned-probe.base64.txt"), Base64.getEncoder().encodeToString(wire), StandardOpenOption.CREATE_NEW);
                narrative.append("\n## 真实节点负向模拟\n\n")
                    .append("内容：一条固定文本 Memo，签名槽全零，无转账指令。付款账户故意选择 WSOL Mint（公开的数据账户，不是资金钱包），预期不能付费。\n\n")
                    .append("结果：**").append(simulated.outcome()).append("**；`executionAllowed=false`；上下文 slot：")
                    .append(simulated.slot()).append("；估算费用：").append(simulated.estimatedFee()).append(" lamports（没有实际扣费）。\n\n")
                    .append("精确字节 SHA-256：`").append(simulated.wireSha256()).append("`。\n\n")
                    .append("这只是读通模拟接口并验证失败分支，不是买入/卖出成功模拟。真实 swap 路由及经济差额核验仍待实现。\n");
            }
            report.put("status", "READONLY_CHECKS_COMPLETED");
        } catch (Exception problem) {
            failure = problem;
            report.put("status", "BLOCKED_NO_EXECUTION").put("errorType", problem.getClass().getSimpleName());
            narrative.append("\n检查停止：").append(problem.getClass().getSimpleName())
                .append("。未将异常转换成通过，未重试交易。保留观察文件以定位停止步骤。\n");
        } finally {
            report.put("finishedAt", Instant.now().toString());
            Files.writeString(output.resolve("readiness.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("rpc-observations.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(observations), StandardOpenOption.CREATE_NEW);
            Files.writeString(output.resolve("REPORT.md"), narrative.toString(), StandardOpenOption.CREATE_NEW);
        }
        System.out.println(output.resolve("REPORT.md"));
        if (failure != null) throw failure;
    }
}
