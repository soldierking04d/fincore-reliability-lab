package dev.fincore.domain;

public record FenceToken(int shardId, String ownerId, long epoch) {
    public FenceToken {
        if (shardId < 0) throw new IllegalArgumentException("shardId must be non-negative");
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("ownerId is required");
        if (epoch <= 0) throw new IllegalArgumentException("epoch must be positive");
    }
}

