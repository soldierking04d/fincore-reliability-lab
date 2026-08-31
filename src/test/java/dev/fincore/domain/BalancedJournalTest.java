package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 借贷平衡不变量的单元测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
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
