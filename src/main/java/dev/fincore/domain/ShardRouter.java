package dev.fincore.domain;

/**
 * 将聚合键稳定映射到 Worker 分片。
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public final class ShardRouter {
    /** Worker 分片总数。 */
    private final int shardCount;

    /**
     * 创建分片路由器。
     *
     * @param shardCount 正的分片总数
     */
    public ShardRouter(int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        this.shardCount = shardCount;
    }

    /**
     * 根据聚合键计算目标分片。
     *
     * @param aggregateKey 付款账户或其他稳定聚合键
     * @return 从 0 开始的分片编号
     */
    public int shardFor(String aggregateKey) {
        if (aggregateKey == null || aggregateKey.isBlank()) {
            throw new IllegalArgumentException("aggregateKey is required");
        }
        int hash = aggregateKey.hashCode();
        hash ^= hash >>> 16;
        return Math.floorMod(hash, shardCount);
    }
}
