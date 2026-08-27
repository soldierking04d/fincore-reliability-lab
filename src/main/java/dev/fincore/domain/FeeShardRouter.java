package dev.fincore.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class FeeShardRouter {
    private final int shardCount;

    public FeeShardRouter(int shardCount) {
        if (shardCount <= 0 || (shardCount & (shardCount - 1)) != 0) {
            throw new IllegalArgumentException("shardCount must be a positive power of two");
        }
        this.shardCount = shardCount;
    }

    public int shardFor(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) throw new IllegalArgumentException("businessKey is required");
        int hash = businessKey.hashCode();
        hash ^= (hash >>> 16);
        return hash & (shardCount - 1);
    }

    public String accountOwner(int shard) {
        if (shard < 0 || shard >= shardCount) throw new IllegalArgumentException("invalid shard");
        return "SYSTEM_FEE_%02d".formatted(shard);
    }
}

