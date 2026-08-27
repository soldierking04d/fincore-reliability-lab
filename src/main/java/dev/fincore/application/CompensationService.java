package dev.fincore.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fincore.domain.BalancedJournal;
import dev.fincore.domain.LedgerDirection;
import dev.fincore.domain.LedgerPosting;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CompensationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CompensationService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional
    public CompensationOutcome compensate(String originalBusinessKey, String reason) {
        UUID compensationId = UUID.randomUUID();
        String compensationKey = "COMP:" + originalBusinessKey + ":" + compensationId;
        jdbc.update("""
            INSERT INTO compensation_order(compensation_id, original_business_key, compensation_business_key, status, reason)
            VALUES (?, ?, ?, 'PROCESSING', ?)
            """, compensationId, originalBusinessKey, compensationKey, reason);

        OriginalSettlement original = jdbc.queryForObject("""
            SELECT business_key, payer_account_id, payee_account_id, fee_account_id, asset, amount, fee, status
            FROM settlement_order WHERE business_key=? FOR UPDATE
            """, (rs, row) -> new OriginalSettlement(rs.getString("business_key"),
                rs.getObject("payer_account_id", UUID.class), rs.getObject("payee_account_id", UUID.class),
                rs.getObject("fee_account_id", UUID.class), rs.getString("asset"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("fee"), rs.getString("status")), originalBusinessKey);
        if (!"SUCCESS".equals(original.status())) throw new IllegalStateException("only successful settlement can be reversed");

        Map<UUID, BigDecimal> balances = lockBalances(original);
        if (balances.get(original.payee()).compareTo(original.amount()) < 0 ||
            balances.get(original.feeAccount()).compareTo(original.fee()) < 0) {
            jdbc.update("UPDATE compensation_order SET status='FAILED', updated_at=now() WHERE compensation_id=?", compensationId);
            return new CompensationOutcome(compensationId, originalBusinessKey, "FAILED", false,
                "recipient or fee account has insufficient balance; manual review required");
        }

        BigDecimal total = original.amount().add(original.fee());
        List<LedgerPosting> postings = new ArrayList<>();
        postings.add(new LedgerPosting(original.payee(), LedgerDirection.DEBIT, original.amount()));
        if (original.fee().signum() > 0) postings.add(new LedgerPosting(original.feeAccount(), LedgerDirection.DEBIT, original.fee()));
        postings.add(new LedgerPosting(original.payer(), LedgerDirection.CREDIT, total));
        BalancedJournal.requireBalanced(postings);

        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_transaction(transaction_id, business_key, transaction_type, asset) VALUES (?, ?, 'COMPENSATION', ?)",
            tx, compensationKey, original.asset());
        for (LedgerPosting posting : postings) {
            jdbc.update("INSERT INTO ledger_entry(entry_id, transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tx, posting.accountId(), posting.direction().name(), posting.amount());
        }
        debit(original.payee(), original.amount());
        if (original.fee().signum() > 0) debit(original.feeAccount(), original.fee());
        credit(original.payer(), total);
        jdbc.update("UPDATE compensation_order SET status='SUCCESS', updated_at=now() WHERE compensation_id=? AND status='PROCESSING'", compensationId);
        jdbc.update("INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload) VALUES (?, ?, 'SETTLEMENT_COMPENSATED', ?)",
            UUID.randomUUID(), originalBusinessKey, toJson(Map.of("originalBusinessKey", originalBusinessKey, "status", "COMPENSATED")));
        return new CompensationOutcome(compensationId, originalBusinessKey, "SUCCESS", false, "reverse journal posted");
    }

    private Map<UUID, BigDecimal> lockBalances(OriginalSettlement original) {
        List<UUID> ids = new ArrayList<>(List.of(original.payer(), original.payee(), original.feeAccount()));
        ids.sort(Comparator.comparing(UUID::toString));
        return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id,
            id -> jdbc.queryForObject("SELECT balance FROM account WHERE account_id=? FOR UPDATE", BigDecimal.class, id)));
    }

    private void debit(UUID id, BigDecimal amount) {
        if (jdbc.update("UPDATE account SET balance=balance-?, version=version+1, updated_at=now() WHERE account_id=? AND balance>=?",
            amount, id, amount) != 1) throw new IllegalStateException("compensation debit rejected");
    }
    private void credit(UUID id, BigDecimal amount) {
        if (jdbc.update("UPDATE account SET balance=balance+?, version=version+1, updated_at=now() WHERE account_id=?", amount, id) != 1)
            throw new IllegalStateException("compensation credit rejected");
    }
    private CompensationOutcome current(String key, boolean duplicate) {
        return jdbc.queryForObject("SELECT compensation_id, original_business_key, status FROM compensation_order WHERE original_business_key=?",
            (rs, row) -> new CompensationOutcome(rs.getObject("compensation_id", UUID.class), rs.getString("original_business_key"),
                rs.getString("status"), duplicate, "existing compensation returned"), key);
    }
    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("cannot serialize payload", e); }
    }

    private record OriginalSettlement(String key, UUID payer, UUID payee, UUID feeAccount, String asset,
                                      BigDecimal amount, BigDecimal fee, String status) {}
    public record CompensationOutcome(UUID compensationId, String originalBusinessKey, String status,
                                      boolean duplicate, String detail) {}
}

