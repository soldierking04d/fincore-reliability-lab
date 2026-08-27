package dev.fincore.domain;

public record SettlementOutcome(String businessKey, SettlementStatus status, boolean duplicate, String detail) {
    public static SettlementOutcome duplicate(String key) {
        return new SettlementOutcome(key, SettlementStatus.SUCCESS, true, "message already processed");
    }
}
