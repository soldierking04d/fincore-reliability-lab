package dev.fincore.domain;

/**
 * 将聚合键稳定映射到 Worker 分片。
 *
 * <p><strong>解决的问题：</strong>相同账户或业务聚合需要进入同一执行分片，才能在提高并行度的同时
 * 保留分片内顺序，避免所有消息争用一个全局队列。</p>
 *
 * <p><strong>CPU 优化：</strong>使用 JDK 字符串哈希、一次高低位混合和整数取模完成 O(1) 路由，
 * 不创建临时集合，也不使用成本更高的加密哈希。{@link Math#floorMod(int, int)} 同时处理负哈希，
 * 允许分片数不是 2 的幂。</p>
 *
 * <p><strong>正确性边界：</strong>该哈希只用于负载路由，不用于安全签名。修改分片数会重新映射业务键，
 * 生产变更必须配套路由版本、停写迁移或双读切换，不能在运行中直接替换。</p>
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
        // 混入高 16 位，降低常见字符串哈希只在低位碰撞时造成的分片偏斜。
        int hash = aggregateKey.hashCode();
        hash ^= hash >>> 16;
        return Math.floorMod(hash, shardCount);
    }
}
