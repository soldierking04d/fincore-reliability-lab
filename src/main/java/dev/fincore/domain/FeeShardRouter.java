package dev.fincore.domain;

/**
 * 手续费账户的确定性分片路由器。
 *
 * <p><strong>解决的问题：</strong>同一个业务键始终路由到同一手续费分片，把单一手续费账户的行锁
 * 热点分散到多个正式账本账户。</p>
 *
 * <p><strong>CPU 优化：</strong>分片数强制为 2 的幂，路由使用 {@code hash & (shardCount - 1)}，
 * 避免整数除法和临时对象；一次高低位混合改善低位分布。该路径为 O(1)，适合每笔成交调用。</p>
 *
 * <p><strong>正确性边界：</strong>这不是安全哈希；修改分片数会改变旧业务键的路由，必须通过版本化
 * 迁移处理。路由只决定写入哪个手续费账本账户，不降低分录、余额和幂等事务要求。</p>
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
        // 混合后用位掩码取分片；构造器的 2 次幂校验保证掩码与取模等价。
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
