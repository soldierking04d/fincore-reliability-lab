package dev.fincore.chain;

import static dev.fincore.chain.Models.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.h2.api.Trigger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 只对JUnit临时数据库注入故障：验证资金事务真的回滚，历史分录在数据库层不能改写。 */
public class JournalGuardTest {
    @TempDir Path dir;
    private Connection database() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:file:" + dir.resolve("paper-ledger").toAbsolutePath()
            + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", "sa", "");
    }
    @Test void databaseRejectsHistoricalJournalUpdateAndDelete() throws Exception {
        try (var e = new ExecutionEngine(dir, ExecutionEngineTest.CLOCK); var db = database(); var statement = db.createStatement()) {
            var before = e.journal();
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE ce_journal SET delta=1"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("DELETE FROM ce_journal"));
            assertEquals(before, e.journal());
        }
    }
    @Test void journalWriteFailureRollsBackIntentAndReservationTogether() throws Exception {
        try (var e = new ExecutionEngine(dir, ExecutionEngineTest.CLOCK); var db = database(); var statement = db.createStatement()) {
            var before = e.snapshot();
            statement.execute("CREATE TRIGGER reject_test_posting BEFORE INSERT ON ce_journal FOR EACH ROW CALL 'dev.fincore.chain.JournalGuardTest$RejectInsert'");
            assertThrows(IllegalStateException.class, () -> e.reserve(new Request("rollback", Side.BUY, BigInteger.valueOf(100000)),
                ExecutionEngineTest.quote(Side.BUY, 100000, 4000000000L)));
            assertEquals(before, e.snapshot()); assertThrows(IllegalStateException.class, () -> e.order("rollback"));
        }
    }
    @Test void journalProjectionsMatchAvailableAndReservedBalancesDuringClosedLoop() {
        try (var e = new ExecutionEngine(dir, ExecutionEngineTest.CLOCK)) {
            long epoch = e.takeover(); var buy = ExecutionEngineTest.buy(e, epoch, "project-buy");
            assertProjection(e);
            assertThrows(IllegalStateException.class, () -> e.reserve(new Request("early-sell", Side.SELL, BigInteger.ONE), ExecutionEngineTest.quote(Side.SELL, 1, 100)));
            e.reconcile("project-buy", epoch, ExecutionEngineTest.observations(buy, Confirmation.FINALIZED_SUCCESS, 4000000000L, 10000));
            assertProjection(e);
            e.reserve(new Request("project-sell", Side.SELL, BigInteger.valueOf(4000000000L)), ExecutionEngineTest.quote(Side.SELL, 4000000000L, 98000));
            assertProjection(e);
            var sell = e.dispatch("project-sell", epoch, p -> "paper-tx-" + p.attemptId());
            e.reconcile("project-sell", epoch, ExecutionEngineTest.observations(sell, Confirmation.FINALIZED_FAILURE, 0, 5000));
            assertProjection(e); assertEquals(BigInteger.valueOf(4000000000L), e.snapshot().tokens());
        }
    }
    private static void assertProjection(ExecutionEngine e) {
        var s = e.snapshot(); var entries = e.journal();
        assertEquals(s.sol().subtract(s.reservedSol()), account(entries, 1));
        assertEquals(s.reservedSol(), account(entries, 2));
        assertEquals(s.tokens().subtract(s.reservedTokens()), account(entries, 5));
        assertEquals(s.reservedTokens(), account(entries, 6));
        s.journalSums().values().forEach(sum -> assertEquals(BigInteger.ZERO, sum));
    }
    private static BigInteger account(List<JournalEntry> entries, int suffix) {
        String id = "00000000-0000-0000-0000-00000000000" + suffix;
        return entries.stream().filter(e -> e.account().equals(id)).map(JournalEntry::delta).reduce(BigInteger.ZERO, BigInteger::add);
    }
    @Test void fileDirectoryCannotInjectH2Settings() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionEngine(dir.resolve("unsafe;AUTO_SERVER=TRUE"), ExecutionEngineTest.CLOCK));
    }
    @Test void storedQuoteMutationBeforeDispatchCannotChangeApprovedIntent() throws Exception {
        try (var e = new ExecutionEngine(dir, ExecutionEngineTest.CLOCK); var db = database(); var statement = db.createStatement()) {
            long epoch = e.takeover();
            e.reserve(new Request("tamper", Side.BUY, BigInteger.valueOf(100000)), ExecutionEngineTest.quote(Side.BUY, 100000, 4000000000L));
            statement.executeUpdate("UPDATE ce_orders SET expected_output=2000000000,min_output=1990000000 WHERE request_id='tamper'");
            assertThrows(IllegalStateException.class, () -> e.dispatch("tamper", epoch, p -> { fail("被篡改意图不得送到网关"); return "bad"; }));
            assertEquals(BigInteger.valueOf(110000), e.snapshot().reservedSol());
        }
    }
    @Test void storedQuoteMutationAfterDispatchFreezesInsteadOfLoweringMinimum() throws Exception {
        try (var e = new ExecutionEngine(dir, ExecutionEngineTest.CLOCK); var db = database(); var statement = db.createStatement()) {
            long epoch = e.takeover(); var order = ExecutionEngineTest.buy(e, epoch, "tamper-after");
            statement.executeUpdate("UPDATE ce_orders SET expected_output=2000000000,min_output=1990000000 WHERE request_id='tamper-after'");
            e.reconcile("tamper-after", epoch, ExecutionEngineTest.observations(order, Confirmation.FINALIZED_SUCCESS, 2000000000L, 10000));
            assertEquals(Status.REVIEW, e.order("tamper-after").status());
            assertEquals(BigInteger.ZERO, e.snapshot().tokens()); assertEquals(BigInteger.valueOf(110000), e.snapshot().reservedSol());
        }
    }
    /** 仅测试依赖中可见，不打进运行时产物。 */
    public static final class RejectInsert implements Trigger {
        @Override public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            throw new SQLException("LAB_ONLY deliberate journal failure", "45000");
        }
    }
}
