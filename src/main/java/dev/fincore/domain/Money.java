package dev.fincore.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record Money(BigDecimal amount, String asset) {
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(asset, "asset");
        if (asset.isBlank()) throw new IllegalArgumentException("asset is blank");
        amount = amount.setScale(18, RoundingMode.UNNECESSARY);
    }

    public static Money of(String amount, String asset) {
        return new Money(new BigDecimal(amount), asset);
    }

    public Money add(Money other) {
        requireSameAsset(other);
        return new Money(amount.add(other.amount), asset);
    }

    public Money subtract(Money other) {
        requireSameAsset(other);
        return new Money(amount.subtract(other.amount), asset);
    }

    public boolean isNegative() { return amount.signum() < 0; }
    public boolean isPositive() { return amount.signum() > 0; }

    private void requireSameAsset(Money other) {
        if (!asset.equals(other.asset)) throw new IllegalArgumentException("asset mismatch");
    }
}

