package dev.fincore.chain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

/**
 * 隔离执行实验的领域合同。这里的 SOL/FCLAB 只是合成资产标签，不含真实地址或密钥。
 * 所有金额均为最小单位整数；没有主网开关、RPC URL 或签名接口。
 */
public final class Models {
    public static final BigInteger INITIAL_SOL = BigInteger.valueOf(10_000_000);
    public static final BigInteger MAX_BUY = BigInteger.valueOf(100_000);
    public static final BigInteger MAX_FEE = BigInteger.valueOf(10_000);
    public static final BigInteger MAX_GROSS_SOL_DEBITS = BigInteger.valueOf(1_000_000);
    public static final BigInteger SOL_FLOOR = BigInteger.valueOf(5_000_000);
    public static final BigInteger MIN_POOL_SOL = BigInteger.valueOf(1_000_000);
    public static final int MAX_OPEN_ORDERS = 8;
    private Models() { }

    public enum Asset { SOL, FCLAB }
    public enum Side { BUY, SELL }
    public enum Status { RESERVED, DISPATCHING, SUBMITTED, UNKNOWN, CONFIRMING, FINALIZED, FAILED, REVIEW }
    public enum Confirmation { NOT_FOUND, CONFIRMED, FINALIZED_SUCCESS, FINALIZED_FAILURE }

    /** 同一 requestId 不允许改变 side/amount；报价变化不能覆盖已经落库的意图。 */
    public record Request(String requestId, Side side, BigInteger amount) { }

    /** 入口必须校验报价期限、最小到账、流动性、价格冲击以及费用，不能只信展示金额。 */
    public record Quote(String quoteId, Side side, BigInteger input, BigInteger expectedOutput,
                        BigInteger minOutput, BigInteger maxNetworkFee, int slippageBps,
                        int priceImpactBps, BigInteger liquiditySol, Instant expiresAt) { }

    /** 已持久化的纸面发送计划；digest 绑定完整报价，attemptId 绑定一次执行，绝非真实签名。 */
    public record Plan(String requestId, Side side, BigInteger input, BigInteger minOutput,
                       BigInteger maxNetworkFee, String digest, String attemptId) { }

    /** 只供合成网关使用；独立 readonly 包的观察结果不能接到此接口冒充纸面成交。 */
    @FunctionalInterface
    public interface PaperGateway {
        String submit(Plan plan) throws Exception;
    }

    /** 两个观察源对同一纸面交易的收据；NOT_FOUND 不能作为失败或释放资金的证据。 */
    public record Observation(String provider, String attemptId, String digest, String signature,
                              Confirmation confirmation, BigInteger input, BigInteger output,
                              BigInteger networkFee, long slot) { }

    public record OrderView(String requestId, Side side, BigInteger input, Status status,
                            String signature, String digest, String attemptId,
                            BigInteger reservedSol, BigInteger reservedTokens) { }

    /** sol/tokens 是本模块合成账本总额，可用额需要减去对应 reserved。 */
    public record Snapshot(BigInteger sol, BigInteger reservedSol, BigInteger tokens,
                           BigInteger reservedTokens, BigInteger grossSolDebits, boolean killSwitch,
                           long epoch, int orderCount, int journalCount, Map<Asset, BigInteger> journalSums) { }

    /** 账本只追加，冲正不得删除或覆盖历史记录；每种资产的所有分录之和必须为零。 */
    public record JournalEntry(long sequence, String orderId, String account, Asset asset, BigInteger delta) { }
}
