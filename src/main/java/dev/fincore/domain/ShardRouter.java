package dev.fincore.domain;

public final class ShardRouter {
    private final int shardCount;
    public ShardRouter(int shardCount) {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount must be positive");
        this.shardCount = shardCount;
    }
    public int shardFor(String aggregateKey) {
        if (aggregateKey == null || aggregateKey.isBlank()) throw new IllegalArgumentException("aggregateKey is required");
        int hash = aggregateKey.hashCode();
        hash ^= hash >>> 16;
        return Math.floorMod(hash, shardCount);
    }
}

