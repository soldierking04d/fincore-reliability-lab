package dev.fincore.application;

import dev.fincore.domain.BalancedJournal;
import dev.fincore.domain.FeeShardRouter;
import dev.fincore.domain.LedgerDirection;
import dev.fincore.domain.LedgerPosting;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeeAggregationService {
    private final JdbcTemplate jdbc;
    public FeeAggregationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public List<FeeAccount> ensureShards(String asset, int shardCount) {
        FeeShardRouter router = new FeeShardRouter(shardCount);
        for (int shard = 0; shard < shardCount; shard++) {
            String owner = router.accountOwner(shard);
            UUID id = UUID.nameUUIDFromBytes(("fee-shard:" + asset + ":" + shard).getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
                VALUES (?, ?, ?, 'SYSTEM_FEE_SHARD', 0, 0)
                ON CONFLICT DO NOTHING
                """, id, owner, asset);
        }
        return jdbc.query("""
            SELECT account_id, owner_id, asset, balance FROM account
            WHERE asset=? AND account_type='SYSTEM_FEE_SHARD' ORDER BY owner_id
            """, (rs, row) -> new FeeAccount(rs.getObject("account_id", UUID.class), rs.getString("owner_id"),
                rs.getString("asset"), rs.getBigDecimal("balance")), asset);
    }

    public FeeAccount route(String asset, int shardCount, String businessKey) {
        FeeShardRouter router = new FeeShardRouter(shardCount);
        String owner = router.accountOwner(router.shardFor(businessKey));
        return jdbc.queryForObject("""
            SELECT account_id, owner_id, asset, balance FROM account
            WHERE owner_id=? AND asset=? AND account_type='SYSTEM_FEE_SHARD'
            """, (rs, row) -> new FeeAccount(rs.getObject("account_id", UUID.class), rs.getString("owner_id"),
                rs.getString("asset"), rs.getBigDecimal("balance")), owner, asset);
    }

    @Transactional
    public FeeAccount ensureTreasury(String asset) {
        UUID id = UUID.nameUUIDFromBytes(("fee-treasury:" + asset).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
            INSERT INTO account(account_id, owner_id, asset, account_type, opening_balance, balance)
            VALUES (?, 'SYSTEM_FEE_TREASURY', ?, 'SYSTEM_FEE_TREASURY', 0, 0)
            ON CONFLICT DO NOTHING
            """, id, asset);
        return jdbc.queryForObject("""
            SELECT account_id, owner_id, asset, balance FROM account
            WHERE owner_id='SYSTEM_FEE_TREASURY' AND asset=? AND account_type='SYSTEM_FEE_TREASURY'
            """, (rs, row) -> new FeeAccount(rs.getObject("account_id", UUID.class), rs.getString("owner_id"),
                rs.getString("asset"), rs.getBigDecimal("balance")), asset);
    }

    @Transactional
    public AggregationOutcome aggregate(String aggregationKey, String asset, UUID treasuryAccountId) {
        if (aggregationKey == null || aggregationKey.isBlank()) throw new IllegalArgumentException("aggregationKey is required");
        int created = jdbc.update("""
            INSERT INTO fee_aggregation(aggregation_key, asset, treasury_account_id, status)
            VALUES (?, ?, ?, 'PROCESSING') ON CONFLICT (aggregation_key) DO NOTHING
            """, aggregationKey, asset, treasuryAccountId);
        if (created == 0) return current(aggregationKey, true);

        AccountRow treasury = jdbc.queryForObject("""
            SELECT account_id, asset, account_type, balance FROM account WHERE account_id=?
            """, this::mapAccount, treasuryAccountId);
        if (!asset.equals(treasury.asset()) || !"SYSTEM_FEE_TREASURY".equals(treasury.type())) {
            throw new IllegalArgumentException("treasury account type or asset mismatch");
        }

        List<UUID> ids = new ArrayList<>(jdbc.queryForList("""
            SELECT account_id FROM account WHERE asset=? AND account_type='SYSTEM_FEE_SHARD'
            """, UUID.class, asset));
        ids.add(treasuryAccountId);
        ids.sort(Comparator.comparing(UUID::toString));
        List<AccountRow> locked = new ArrayList<>();
        for (UUID id : ids) {
            locked.add(jdbc.queryForObject("""
                SELECT account_id, asset, account_type, balance FROM account WHERE account_id=? FOR UPDATE
                """, this::mapAccount, id));
        }
        List<AccountRow> shards = locked.stream().filter(a -> "SYSTEM_FEE_SHARD".equals(a.type()))
            .filter(a -> a.balance().signum() > 0).toList();
        BigDecimal total = shards.stream().map(AccountRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            jdbc.update("UPDATE fee_aggregation SET status='SUCCESS', total_amount=0, updated_at=now() WHERE aggregation_key=?",
                aggregationKey);
            return new AggregationOutcome(aggregationKey, "SUCCESS", BigDecimal.ZERO, false, 0);
        }

        List<LedgerPosting> postings = new ArrayList<>();
        for (AccountRow shard : shards) postings.add(new LedgerPosting(shard.id(), LedgerDirection.DEBIT, shard.balance()));
        postings.add(new LedgerPosting(treasuryAccountId, LedgerDirection.CREDIT, total));
        BalancedJournal.requireBalanced(postings);
        UUID transactionId = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_transaction(transaction_id, business_key, transaction_type, asset) VALUES (?, ?, 'FEE_AGGREGATION', ?)",
            transactionId, "FEE_AGG:" + aggregationKey, asset);
        for (LedgerPosting posting : postings) {
            jdbc.update("INSERT INTO ledger_entry(entry_id, transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), transactionId, posting.accountId(), posting.direction().name(), posting.amount());
        }
        for (AccountRow shard : shards) {
            if (jdbc.update("UPDATE account SET balance=balance-?, version=version+1, updated_at=now() WHERE account_id=? AND balance=?",
                shard.balance(), shard.id(), shard.balance()) != 1) throw new IllegalStateException("fee shard changed during aggregation");
        }
        jdbc.update("UPDATE account SET balance=balance+?, version=version+1, updated_at=now() WHERE account_id=?", total, treasuryAccountId);
        jdbc.update("""
            UPDATE fee_aggregation SET status='SUCCESS', total_amount=?, version=version+1, updated_at=now()
            WHERE aggregation_key=? AND status='PROCESSING'
            """, total, aggregationKey);
        return new AggregationOutcome(aggregationKey, "SUCCESS", total, false, shards.size());
    }

    private AccountRow mapAccount(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AccountRow(rs.getObject("account_id", UUID.class), rs.getString("asset"),
            rs.getString("account_type"), rs.getBigDecimal("balance"));
    }
    private AggregationOutcome current(String key, boolean duplicate) {
        return jdbc.queryForObject("""
            SELECT aggregation_key, status, COALESCE(total_amount, 0) total_amount
            FROM fee_aggregation WHERE aggregation_key=?
            """, (rs, row) -> new AggregationOutcome(rs.getString("aggregation_key"), rs.getString("status"),
                rs.getBigDecimal("total_amount"), duplicate, 0), key);
    }

    private record AccountRow(UUID id, String asset, String type, BigDecimal balance) {}
    public record FeeAccount(UUID accountId, String ownerId, String asset, BigDecimal balance) {}
    public record AggregationOutcome(String aggregationKey, String status, BigDecimal totalAmount,
                                     boolean duplicate, int aggregatedShardCount) {}
}
