package dev.fincore.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.BalancedJournal;
import dev.fincore.domain.FenceToken;
import dev.fincore.domain.LedgerDirection;
import dev.fincore.domain.LedgerPosting;
import dev.fincore.domain.SettlementCommand;
import dev.fincore.domain.SettlementOutcome;
import dev.fincore.domain.SettlementStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ShardLeaseService shardLeases;
    private final Counter success;
    private final Counter duplicates;
    private final Counter failures;

    public SettlementService(JdbcTemplate jdbc, ObjectMapper json, MeterRegistry registry,
                             ShardLeaseService shardLeases) {
        this.jdbc = jdbc;
        this.json = json;
        this.shardLeases = shardLeases;
        this.success = registry.counter("fincore.settlement.success");
        this.duplicates = registry.counter("fincore.settlement.duplicate");
        this.failures = registry.counter("fincore.settlement.failure");
    }

    @Transactional
    public SettlementOutcome settle(SettlementCommand command) {
        return settle(command, null);
    }

    @Transactional
    public SettlementOutcome settle(SettlementCommand command, FenceToken fenceToken) {
        String payload = toJson(command);
        jdbc.update("""
                INSERT INTO inbox_message(message_id, message_type, payload)
                VALUES (?, 'SETTLEMENT_COMMAND', ?)
                """, command.messageId(), payload);
        if (fenceToken != null) shardLeases.requireValidFenceForUpdate(fenceToken);

        jdbc.update("""
            INSERT INTO settlement_order(business_key, message_id, payer_account_id, payee_account_id,
                                         fee_account_id, asset, amount, fee, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'INIT')
            """, command.businessKey(), command.messageId(), command.payerAccountId(), command.payeeAccountId(),
            command.feeAccountId(), command.asset(), command.amount(), command.fee());
        audit(command.businessKey(), null, SettlementStatus.INIT, "order created");
        transition(command.businessKey(), SettlementStatus.INIT, SettlementStatus.PROCESSING, null);

        Map<UUID, LockedAccount> accounts = lockAccounts(command);
        LockedAccount payer = accounts.get(command.payerAccountId());
        LockedAccount payee = accounts.get(command.payeeAccountId());
        LockedAccount feeAccount = accounts.get(command.feeAccountId());
        requireAsset(command.asset(), payer, payee, feeAccount);

        BigDecimal totalDebit = command.amount().add(command.fee());
        if (payer.balance().compareTo(totalDebit) < 0) {
            transition(command.businessKey(), SettlementStatus.PROCESSING, SettlementStatus.FAILED,
                "insufficient balance");
            markInboxProcessed(command.messageId());
            failures.increment();
            return new SettlementOutcome(command.businessKey(), SettlementStatus.FAILED, false, "insufficient balance");
        }

        List<LedgerPosting> postings = new ArrayList<>();
        postings.add(new LedgerPosting(command.payerAccountId(), LedgerDirection.DEBIT, totalDebit));
        postings.add(new LedgerPosting(command.payeeAccountId(), LedgerDirection.CREDIT, command.amount()));
        if (command.fee().signum() > 0) {
            postings.add(new LedgerPosting(command.feeAccountId(), LedgerDirection.CREDIT, command.fee()));
        }
        BalancedJournal.requireBalanced(postings);
        UUID transactionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO ledger_transaction(transaction_id, business_key, transaction_type, asset)
            VALUES (?, ?, 'SETTLEMENT', ?)
            """, transactionId, command.businessKey(), command.asset());
        for (LedgerPosting posting : postings) {
            if (posting.amount().signum() == 0) continue;
            jdbc.update("""
                INSERT INTO ledger_entry(entry_id, transaction_id, account_id, direction, amount)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), transactionId, posting.accountId(), posting.direction().name(), posting.amount());
        }

        debit(command.payerAccountId(), totalDebit);
        credit(command.payeeAccountId(), command.amount());
        if (command.fee().signum() > 0) credit(command.feeAccountId(), command.fee());
        transition(command.businessKey(), SettlementStatus.PROCESSING, SettlementStatus.SUCCESS, null);
        jdbc.update("""
            INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload)
            VALUES (?, ?, 'SETTLEMENT_SUCCEEDED', ?)
            """, UUID.randomUUID(), command.businessKey(), toJson(Map.of(
                "businessKey", command.businessKey(), "status", "SUCCESS")));
        markInboxProcessed(command.messageId());
        success.increment();
        return new SettlementOutcome(command.businessKey(), SettlementStatus.SUCCESS, false, "settled");
    }

    public SettlementOutcome get(String businessKey) {
        return currentOutcome(businessKey, false);
    }

    private Map<UUID, LockedAccount> lockAccounts(SettlementCommand command) {
        List<UUID> ids = new ArrayList<>(List.of(command.payerAccountId(), command.payeeAccountId(), command.feeAccountId()));
        ids.sort(Comparator.comparing(UUID::toString));
        return ids.stream().map(id -> jdbc.queryForObject("""
                SELECT account_id, asset, balance FROM account WHERE account_id=? FOR UPDATE
                """, (rs, row) -> new LockedAccount(rs.getObject("account_id", UUID.class),
                    rs.getString("asset"), rs.getBigDecimal("balance")), id))
            .collect(java.util.stream.Collectors.toMap(LockedAccount::id, a -> a));
    }

    private void requireAsset(String asset, LockedAccount... accounts) {
        for (LockedAccount account : accounts) {
            if (!asset.equals(account.asset())) throw new IllegalArgumentException("account asset mismatch: " + account.id());
        }
    }

    private void debit(UUID id, BigDecimal amount) {
        int changed = jdbc.update("""
            UPDATE account SET balance=balance-?, version=version+1, updated_at=now()
            WHERE account_id=? AND balance>=?
            """, amount, id, amount);
        if (changed != 1) throw new IllegalStateException("concurrent debit rejected");
    }

    private void credit(UUID id, BigDecimal amount) {
        if (jdbc.update("""
            UPDATE account SET balance=balance+?, version=version+1, updated_at=now() WHERE account_id=?
            """, amount, id) != 1) throw new IllegalStateException("credit account missing");
    }

    private void transition(String key, SettlementStatus from, SettlementStatus to, String reason) {
        from.requireTransitionTo(to);
        int changed = jdbc.update("""
            UPDATE settlement_order SET status=?, failure_reason=?, version=version+1, updated_at=now()
            WHERE business_key=? AND status=?
            """, to.name(), reason, key, from.name());
        if (changed != 1) throw new IllegalStateException("CAS transition rejected: " + from + " -> " + to);
        audit(key, from, to, reason);
    }

    private void audit(String key, SettlementStatus from, SettlementStatus to, String reason) {
        jdbc.update("""
            INSERT INTO state_audit(audit_id, business_key, from_status, to_status, reason, changed_by)
            VALUES (?, ?, ?, ?, ?, 'settlement-service')
            """, UUID.randomUUID(), key, from == null ? null : from.name(), to.name(), reason);
    }

    private SettlementOutcome currentOutcome(String key, boolean duplicate) {
        return jdbc.queryForObject("""
            SELECT business_key, status, COALESCE(failure_reason, '') detail
            FROM settlement_order WHERE business_key=?
            """, (rs, row) -> new SettlementOutcome(rs.getString("business_key"),
                SettlementStatus.valueOf(rs.getString("status")), duplicate, rs.getString("detail")), key);
    }

    private SettlementOutcome currentOutcomeByMessage(String messageId) {
        return jdbc.queryForObject("""
            SELECT business_key, status, COALESCE(failure_reason, '') detail
            FROM settlement_order WHERE message_id=?
            """, (rs, row) -> new SettlementOutcome(rs.getString("business_key"),
                SettlementStatus.valueOf(rs.getString("status")), true,
                "duplicate message: " + rs.getString("detail")), messageId);
    }

    private void markInboxProcessed(String messageId) {
        jdbc.update("UPDATE inbox_message SET processed_at=now() WHERE message_id=?", messageId);
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("cannot serialize payload", e); }
    }

    private record LockedAccount(UUID id, String asset, BigDecimal balance) {}
}
