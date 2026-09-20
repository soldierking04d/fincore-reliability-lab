package dev.fincore.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Metrics never invent a committed transaction or change one that already committed. */
class TransactionMetricsTest {
    /** Release per-thread transaction state between tests. */
    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    /** A direct unproxied call offers no evidence of any physical commit. */
    @Test
    void noTransactionDoesNotCountCommittedOutcome() {
        Counter counter = new SimpleMeterRegistry().counter("test.committed");
        TransactionMetrics.incrementAfterCommit(counter);
        assertEquals(0, counter.count());
    }

    /** Synchronization alone is not an actual database transaction. */
    @Test
    void synchronizationWithoutActualTransactionDoesNotCount() {
        Counter counter = new SimpleMeterRegistry().counter("test.committed");
        TransactionSynchronizationManager.initSynchronization();

        TransactionMetrics.incrementAfterCommit(counter);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        assertEquals(0, counter.count());
    }

    /** An observability exception after commit cannot make a completed transfer appear to fail. */
    @Test
    void counterFailureDoesNotEscapeAfterCommit() {
        Counter counter = mock(Counter.class);
        doThrow(new IllegalStateException("test metrics failure")).when(counter).increment();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        TransactionMetrics.incrementAfterCommit(counter);

        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
            .forEach(TransactionSynchronization::afterCommit));
    }
}
