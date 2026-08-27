package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShardLeaseService {
    private final JdbcTemplate jdbc;
    public ShardLeaseService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public Lease claim(int shardId, String ownerId, Duration ttl) {
        return acquireOrRenew(shardId, ownerId, ttl);
    }

    @Transactional
    public Lease acquireOrRenew(int shardId, String ownerId, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        List<Lease> rows = jdbc.query("""
            SELECT shard_id, owner_id, epoch, state, lease_until FROM shard_lease
            WHERE shard_id=? FOR UPDATE
            """, (rs, row) -> new Lease(rs.getInt("shard_id"), rs.getString("owner_id"),
                rs.getLong("epoch"), rs.getString("state"), rs.getTimestamp("lease_until").toInstant()), shardId);
        Instant until = Instant.now().plus(ttl);
        if (rows.isEmpty()) {
            jdbc.update("INSERT INTO shard_lease(shard_id, owner_id, epoch, state, lease_until) VALUES (?, ?, 1, 'RUNNING', ?)",
                shardId, ownerId, Timestamp.from(until));
            return new Lease(shardId, ownerId, 1, "RUNNING", until);
        }
        Lease current = rows.get(0);
        boolean live = current.leaseUntil().isAfter(Instant.now());
        if (live && current.ownerId().equals(ownerId) && "RUNNING".equals(current.state())) {
            jdbc.update("UPDATE shard_lease SET lease_until=?, updated_at=now() WHERE shard_id=? AND owner_id=? AND epoch=?",
                Timestamp.from(until), shardId, ownerId, current.epoch());
            return new Lease(shardId, ownerId, current.epoch(), "RUNNING", until);
        }
        if (live) {
            throw new IllegalStateException("shard unavailable; owner=" + current.ownerId() + ", state=" + current.state());
        }
        long nextEpoch = current.epoch() + 1;
        jdbc.update("""
            UPDATE shard_lease SET owner_id=?, epoch=?, state='RUNNING', lease_until=?, updated_at=now()
            WHERE shard_id=?
            """, ownerId, nextEpoch, Timestamp.from(until), shardId);
        return new Lease(shardId, ownerId, nextEpoch, "RUNNING", until);
    }

    public boolean renew(int shardId, String ownerId, long epoch, Duration ttl) {
        return jdbc.update("""
            UPDATE shard_lease SET lease_until=?, updated_at=now()
            WHERE shard_id=? AND owner_id=? AND epoch=? AND state='RUNNING' AND lease_until>now()
            """, Timestamp.from(Instant.now().plus(ttl)), shardId, ownerId, epoch) == 1;
    }

    public boolean drain(int shardId, String ownerId, long epoch) {
        return jdbc.update("""
            UPDATE shard_lease SET state='DRAINING', updated_at=now()
            WHERE shard_id=? AND owner_id=? AND epoch=? AND state='RUNNING'
            """, shardId, ownerId, epoch) == 1;
    }

    public boolean validFence(int shardId, String ownerId, long epoch) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM shard_lease
            WHERE shard_id=? AND owner_id=? AND epoch=? AND state='RUNNING' AND lease_until>now()
            """, Integer.class, shardId, ownerId, epoch);
        return count != null && count == 1;
    }

    public void requireValidFenceForUpdate(FenceToken token) {
        // INTENTIONAL BENCHMARK DEFECT: stale workers are no longer fenced.
    }

    public record Lease(int shardId, String ownerId, long epoch, String state, Instant leaseUntil) {}
}
