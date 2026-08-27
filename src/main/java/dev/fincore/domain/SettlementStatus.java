package dev.fincore.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum SettlementStatus {
    INIT, PROCESSING, SUCCESS, FAILED, COMPENSATING, COMPENSATED;

    private static final Map<SettlementStatus, Set<SettlementStatus>> ALLOWED = Map.of(
        INIT, EnumSet.of(PROCESSING),
        PROCESSING, EnumSet.of(SUCCESS, FAILED),
        FAILED, EnumSet.of(COMPENSATING),
        COMPENSATING, EnumSet.of(COMPENSATED, FAILED),
        SUCCESS, EnumSet.noneOf(SettlementStatus.class),
        COMPENSATED, EnumSet.noneOf(SettlementStatus.class)
    );

    public boolean canTransitionTo(SettlementStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public void requireTransitionTo(SettlementStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("illegal status transition: " + this + " -> " + target);
        }
    }
}

