package dev.fincore.domain;

/**
 * Worker 写入数据面时携带的围栏令牌。
 *
 * <p>令牌同时包含分片、所有者与 Epoch。服务端必须在资金事务内部对照当前 Lease
 * 再次校验，不能只依赖控制面曾经成功获取 Lease 的结果。</p>
 *
 * @param shardId 分片编号
 * @param ownerId Worker 唯一标识
 * @param epoch 单调递增的所有权世代
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record FenceToken(int shardId, String ownerId, long epoch) {
    /** 校验围栏令牌的基本格式，数据库中的实时所有权由 ShardLeaseService 负责校验。 */
    public FenceToken {
        if (shardId < 0) {
            throw new IllegalArgumentException("shardId must be non-negative");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (epoch <= 0) {
            throw new IllegalArgumentException("epoch must be positive");
        }
    }
}
