package dev.fincore.domain;

public enum OrderStatus {
    OPEN,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED;

    public boolean isOpen() {
        return this == OPEN || this == PARTIALLY_FILLED;
    }
}
