package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.*;
import dev.fincore.simulation.ReliabilitySimulation;
import org.junit.jupiter.api.Test;

class ReliabilitySimulationTest {
    @Test void allSelfContainedReliabilityChecksPass() throws Exception {
        var report = ReliabilitySimulation.runAndAssert();
        assertTrue(report.checks().values().stream().allMatch("PASS"::equals));
        assertEquals(99, report.duplicateCount());
        assertEquals(1, report.reconciliationDifferences());
    }
}

