package dev.fincore.domain;

/**
 * 手续费账户的确定性分片路由器。
 *
 * <p>同一个业务键始终路由到同一手续费分片，从而分散热点账户写竞争。分片数限定为
 * 2 的幂，便于使用位运算完成稳定且低开销的路由。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public final class FeeShardRouter {
    /** 手续费账户分片总数。 */
    private final int shardCount;

    /**
     * 创建手续费分片路由器。
     *
     * @param shardCount 分片总数，必须是正的 2 的幂
     */
    public FeeShardRouter(int shardCount) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("shardCount must be a positive power of two");
        }
        this.shardCount = shardCount;
    }

    /**
     * 根据业务键计算目标手续费分片。
     *
     * @param businessKey 结算、归集等业务使用的稳定唯一键
     * @return 从 0 开始的分片编号
     */
    public int shardFor(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            throw new IllegalArgumentException("businessKey is required");
        }
        int hash = businessKey.hashCode();
        hash ^= (hash >>> 16);
        return hash & (shardCount - 1);
    }

    /**
     * 生成指定分片对应的系统手续费账户所有者标识。
     *
     * @param shard 分片编号
     * @return 形如 {@code SYSTEM_FEE_03} 的稳定账户所有者标识
     */
    public String accountOwner(int shard) {
        if (shard < 0 || shard >= shardCount) {
            throw new IllegalArgumentException("invalid shard");
        }
        return "SYSTEM_FEE_%02d".formatted(shard);
    }
}
