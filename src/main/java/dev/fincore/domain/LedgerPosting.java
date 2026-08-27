package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerPosting(UUID accountId, LedgerDirection direction, BigDecimal amount) {
    public LedgerPosting {
        if (accountId == null) throw new IllegalArgumentException("accountId is required");
        if (direction == null) throw new IllegalArgumentException("direction is required");
        if (amount == null || amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
    }
}

