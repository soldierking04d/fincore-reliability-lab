package dev.fincore.chain;

import static dev.fincore.chain.Models.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 可复现的离线验收入口：模拟买入回执丢失、关闭/重开数据库、确认持仓、卖出并对账。
 * 没有钱包发现、私钥读取、网络请求或真实链广播。数据明确标记 PAPER，不得作为收益证据。
 */
public final class PaperScenarioMain {
    private PaperScenarioMain() { }
    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("只接受一个新的本地输出目录；没有 live/mainnet 模式");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        // 必须新目录：重跑不能覆盖旧账本，更不能通过重置旧订单掩盖未决交易。
        try (var entries = Files.list(output)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalStateException("验收输出目录不是空目录；请换新目录，保留旧记录");
            }
        }
        List<Row> rows = new ArrayList<>();
        OrderView buy;
        try (var engine = new ExecutionEngine(output.resolve("paper-db"), CLOCK)) {
            long epoch = engine.takeover();
            capture(rows, "初始合成资金", "NONE", engine);
            engine.reserve(new Request("paper-buy-001", Side.BUY, n(100_000)), quote(Side.BUY, 100_000, 4_000_000_000L));
            capture(rows, "预占买入本金和费用", "RESERVED", engine);
            buy = engine.dispatch("paper-buy-001", epoch, plan -> {
                throw new IOException("PAPER：模拟节点接受后响应丢失；这里没有发送任何真实交易");
            });
            capture(rows, "模拟发送结果未知", buy.status().name(), engine);
        }
        try (var engine = new ExecutionEngine(output.resolve("paper-db"), CLOCK)) {
            long epoch = engine.takeover();
            capture(rows, "重开数据库与新Worker接管", engine.order("paper-buy-001").status().name(), engine);
            engine.reconcile("paper-buy-001", epoch, observations(buy, Confirmation.CONFIRMED, 4_000_000_000L));
            capture(rows, "两个纸面观察源均confirmed_不可卖", "CONFIRMING", engine);
            engine.reconcile("paper-buy-001", epoch, observations(buy, Confirmation.FINALIZED_SUCCESS, 4_000_000_000L));
            capture(rows, "最终确认后形成可卖持仓", "FINALIZED", engine);
            engine.reserve(new Request("paper-sell-001", Side.SELL, n(4_000_000_000L)), quote(Side.SELL, 4_000_000_000L, 98_000));
            capture(rows, "预占卖出持仓和SOL费用", "RESERVED", engine);
            var sell = engine.dispatch("paper-sell-001", epoch, plan -> "paper-tx-" + plan.attemptId());
            engine.reconcile("paper-sell-001", epoch, observations(sell, Confirmation.FINALIZED_SUCCESS, 98_000));
            capture(rows, "卖出最终确认并对账", "FINALIZED", engine);
            int journalCount = engine.snapshot().journalCount();
            engine.reconcile("paper-sell-001", epoch, observations(sell, Confirmation.FINALIZED_SUCCESS, 98_000));
            if (engine.snapshot().journalCount() != journalCount) {
                throw new IllegalStateException("重复收据生成了第二次账本效果");
            }
            Snapshot end = engine.snapshot();
            if (!end.sol().equals(n(9_978_000)) || end.tokens().signum() != 0
                    || end.reservedSol().signum() != 0 || end.reservedTokens().signum() != 0
                    || end.journalSums().values().stream().anyMatch(v -> v.signum() != 0)) {
                throw new IllegalStateException("闭环资金与分录没有通过独立字面预期");
            }
            StringBuilder journal = new StringBuilder("mode,sequence,order_id,account,asset,delta_atomic\n");
            for (JournalEntry entry : engine.journal()) {
                journal.append("PAPER,").append(entry.sequence()).append(',').append(entry.orderId()).append(',')
                    .append(entry.account()).append(',').append(entry.asset()).append(',').append(entry.delta()).append('\n');
            }
            Files.writeString(output.resolve("journal.csv"), journal, StandardOpenOption.CREATE_NEW);
        }
        writeReport(output, rows);
        System.out.println("PAPER_ONLY：离线买入—重启恢复—持仓—卖出闭环通过；未连接钱包或公链。");
        System.out.println(output.resolve("REPORT.md"));
    }

    private static void capture(List<Row> rows, String step, String status, ExecutionEngine engine) {
        rows.add(new Row(step, status, engine.snapshot()));
    }

    private static void writeReport(Path output, List<Row> rows) throws IOException {
        StringBuilder csv = new StringBuilder("mode,step,status,sol_atomic,reserved_sol_atomic,token_atomic,reserved_token_atomic,gross_sol_debits_atomic,journal_rows\n");
        StringBuilder md = new StringBuilder("# 链上执行模块 · 离线 PAPER 验收\n\n"
            + "本报告由 Java 程序真实执行后读取独立文件数据库生成。所有报价和链收据均为合成测试数据，**不是主网或测试网成交记录，不代表收益**。没有钱包、私钥、RPC 或网络广播。\n\n"
            + "| 步骤 | 状态 | SOL 总额 | 占用 SOL | FCLAB 总额 | 占用 FCLAB |\n|---|---|---:|---:|---:|---:|\n");
        for (Row row : rows) {
            Snapshot s = row.snapshot();
            csv.append("PAPER,").append(row.step()).append(',').append(row.status()).append(',').append(s.sol()).append(',')
                .append(s.reservedSol()).append(',').append(s.tokens()).append(',').append(s.reservedTokens()).append(',')
                .append(s.grossSolDebits()).append(',').append(s.journalCount()).append('\n');
            md.append('|').append(row.step()).append('|').append(row.status()).append('|').append(display(s.sol(), 9)).append('|')
                .append(display(s.reservedSol(), 9)).append('|').append(display(s.tokens(), 6)).append('|').append(display(s.reservedTokens(), 6)).append("|\n");
        }
        md.append("\n独立预期：初始 0.01 SOL；买入 0.0001 SOL＋费用 0.00001 SOL；卖出收到 0.000098 SOL－费用 0.00001 SOL；最终 **0.009978 SOL，FCLAB 持仓归零**。差额为 -0.000022 SOL，来自合成价差与两次费用，不隐去测试中的亏损。\n\n")
            .append("结果未知和 confirmed 阶段没有生成可卖持仓；重开数据库后保留占用；重复 finalized 收据不重复记账。`steps.csv` 是逐步快照，`journal.csv` 是追加式分录，每种资产全账本分录和为零。\n");
        Files.writeString(output.resolve("steps.csv"), csv, StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("REPORT.md"), md, StandardOpenOption.CREATE_NEW);
    }

    private static String display(BigInteger atomic, int decimals) {
        return new BigDecimal(atomic, decimals).toPlainString();
    }
    private static BigInteger n(long value) { return BigInteger.valueOf(value); }
    private static Quote quote(Side side, long input, long expected) {
        return new Quote("paper-quote-" + side, side, n(input), n(expected), n(expected).multiply(n(9950)).divide(n(10000)),
            n(10000), 50, 40, n(10000000), NOW.plusSeconds(30));
    }
    private static List<Observation> observations(OrderView order, Confirmation status, long output) {
        return List.of(new Observation("paper-rpc-a", order.attemptId(), order.digest(), "paper-tx-" + order.attemptId(), status, order.input(), n(output), n(10000), 42),
            new Observation("paper-rpc-b", order.attemptId(), order.digest(), "paper-tx-" + order.attemptId(), status, order.input(), n(output), n(10000), 42));
    }
    private record Row(String step, String status, Snapshot snapshot) { }
}
