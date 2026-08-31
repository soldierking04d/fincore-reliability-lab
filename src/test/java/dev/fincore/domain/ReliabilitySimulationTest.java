package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fincore.simulation.ReliabilitySimulation;
import org.junit.jupiter.api.Test;

/**
 * 无中间件可靠性模拟器的回归测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.0.0
 */
class ReliabilitySimulationTest {
    @Test void allSelfContainedReliabilityChecksPass() throws Exception {
        var report = ReliabilitySimulation.runAndAssert();
        assertTrue(report.checks().values().stream().allMatch("PASS"::equals));
        assertEquals(99, report.duplicateCount());
        assertEquals(1, report.reconciliationDifferences());
    }
}
