package dev.fincore.domain;

import java.math.BigDecimal;
import java.util.List;

public final class BalancedJournal {
    private BalancedJournal() {}

    public static void requireBalanced(List<LedgerPosting> postings) {
        BigDecimal debits = postings.stream()
            .filter(p -> p.direction() == LedgerDirection.DEBIT)
            .map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = postings.stream()
            .filter(p -> p.direction() == LedgerDirection.CREDIT)
            .map(LedgerPosting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("unbalanced journal: debit=" + debits + ", credit=" + credits);
        }
    }
}

