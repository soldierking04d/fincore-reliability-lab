package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BalancedJournalTest {
    @Test void acceptsBalancedPostings() {
        assertDoesNotThrow(() -> BalancedJournal.requireBalanced(List.of(
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.DEBIT, new BigDecimal("11")),
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.CREDIT, new BigDecimal("10")),
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.CREDIT, new BigDecimal("1")))));
    }

    @Test void rejectsUnbalancedPostings() {
        assertThrows(IllegalArgumentException.class, () -> BalancedJournal.requireBalanced(List.of(
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.DEBIT, new BigDecimal("10")),
            new LedgerPosting(UUID.randomUUID(), LedgerDirection.CREDIT, new BigDecimal("9")))));
    }
}

