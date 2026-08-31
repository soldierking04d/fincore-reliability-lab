package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 结算状态机合法迁移与成功终态的单元测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
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
