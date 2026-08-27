package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SettlementStatusTest {
    @Test void successIsTerminal() {
        assertFalse(SettlementStatus.SUCCESS.canTransitionTo(SettlementStatus.FAILED));
        assertThrows(IllegalStateException.class,
            () -> SettlementStatus.SUCCESS.requireTransitionTo(SettlementStatus.FAILED));
    }

    @Test void failureCanOnlyEnterCompensation() {
        assertTrue(SettlementStatus.FAILED.canTransitionTo(SettlementStatus.COMPENSATING));
        assertFalse(SettlementStatus.FAILED.canTransitionTo(SettlementStatus.SUCCESS));
    }
}

