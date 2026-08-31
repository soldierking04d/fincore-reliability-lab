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

/**
 * 成功结算的反向账本补偿服务。
 *
 * <p>补偿不会更新或删除原始成功流水，而是创建独立补偿单、反向账本事务和 Outbox
 * 事件。原业务键存在唯一约束，因此重复补偿请求只会返回第一次处理结果。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class CompensationService {
    /** 补偿、账户和账本数据库访问模板。 */
    private final JdbcTemplate jdbc;
    /** Outbox 载荷序列化器。 */
    private final ObjectMapper json;

    /** 创建补偿服务。 */
    public CompensationService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * 对成功结算创建并执行一笔幂等反向补偿。
     *
     * @param originalBusinessKey 原始成功结算业务键
     * @param reason 补偿原因，写入审计记录
     * @return 补偿结果；重复请求返回已有结果并标记 duplicate
     */
    @Transactional
    public CompensationOutcome compensate(String originalBusinessKey, String reason) {
        // 补偿键与原始业务键一一对应，数据库唯一约束是最终幂等防线。
        String compensationKey = "COMP:" + originalBusinessKey;
        UUID compensationId = UUID.randomUUID();
        int created = jdbc.update("""
            INSERT INTO compensation_order(compensation_id, original_business_key, compensation_business_key, status, reason)
            VALUES (?, ?, ?, 'PROCESSING', ?) ON CONFLICT (original_business_key) DO NOTHING
            """, compensationId, originalBusinessKey, compensationKey, reason);
        if (created == 0) {
            return current(originalBusinessKey, true);
        }

        // 锁定原始结算单，确保补偿基于稳定的 SUCCESS 快照。
        OriginalSettlement original = jdbc.queryForObject("""
            SELECT business_key, payer_account_id, payee_account_id, fee_account_id, asset, amount, fee, status
            FROM settlement_order WHERE business_key=? FOR UPDATE
            """, (rs, row) -> new OriginalSettlement(rs.getString("business_key"),
                rs.getObject("payer_account_id", UUID.class), rs.getObject("payee_account_id", UUID.class),
                rs.getObject("fee_account_id", UUID.class), rs.getString("asset"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("fee"), rs.getString("status")), originalBusinessKey);
        if (!"SUCCESS".equals(original.status())) {
            throw new IllegalStateException("only successful settlement can be reversed");
        }

        // 所有参与账户按 UUID 固定顺序加锁，降低并发补偿时的死锁概率。
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
        if (original.fee().signum() > 0) {
            postings.add(new LedgerPosting(
                original.feeAccount(),
                LedgerDirection.DEBIT,
                original.fee()
            ));
        }
        postings.add(new LedgerPosting(original.payer(), LedgerDirection.CREDIT, total));
        BalancedJournal.requireBalanced(postings);

        // 反向分录、余额、补偿状态和 Outbox 位于同一事务，任一步失败都会整体回滚。
        UUID tx = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_transaction(transaction_id, business_key, transaction_type, asset) VALUES (?, ?, 'COMPENSATION', ?)",
            tx, compensationKey, original.asset());
        for (LedgerPosting posting : postings) {
            jdbc.update("INSERT INTO ledger_entry(entry_id, transaction_id, account_id, direction, amount) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), tx, posting.accountId(), posting.direction().name(), posting.amount());
        }
        debit(original.payee(), original.amount());
        if (original.fee().signum() > 0) {
            debit(original.feeAccount(), original.fee());
        }
        credit(original.payer(), total);
        jdbc.update("UPDATE compensation_order SET status='SUCCESS', updated_at=now() WHERE compensation_id=? AND status='PROCESSING'", compensationId);
        jdbc.update("INSERT INTO outbox_event(event_id, aggregate_id, event_type, payload) VALUES (?, ?, 'SETTLEMENT_COMPENSATED', ?)",
            UUID.randomUUID(), originalBusinessKey, toJson(Map.of("originalBusinessKey", originalBusinessKey, "status", "COMPENSATED")));
        return new CompensationOutcome(compensationId, originalBusinessKey, "SUCCESS", false, "reverse journal posted");
    }

    /** 按 UUID 字符串顺序锁定补偿涉及的全部账户余额。 */
    private Map<UUID, BigDecimal> lockBalances(OriginalSettlement original) {
        List<UUID> ids = new ArrayList<>(List.of(original.payer(), original.payee(), original.feeAccount()));
        ids.sort(Comparator.comparing(UUID::toString));
        return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id,
            id -> jdbc.queryForObject("SELECT balance FROM account WHERE account_id=? FOR UPDATE", BigDecimal.class, id)));
    }

    /** 使用余额下限条件执行原子扣款。 */
    private void debit(UUID id, BigDecimal amount) {
        if (jdbc.update("UPDATE account SET balance=balance-?, version=version+1, updated_at=now() WHERE account_id=? AND balance>=?",
            amount, id, amount) != 1) {
            throw new IllegalStateException("compensation debit rejected");
        }
    }

    /** 执行账户原子入账。 */
    private void credit(UUID id, BigDecimal amount) {
        if (jdbc.update("UPDATE account SET balance=balance+?, version=version+1, updated_at=now() WHERE account_id=?", amount, id) != 1) {
            throw new IllegalStateException("compensation credit rejected");
        }
    }

    /** 查询已存在的补偿结果，用于幂等重放。 */
    private CompensationOutcome current(String key, boolean duplicate) {
        return jdbc.queryForObject("SELECT compensation_id, original_business_key, status FROM compensation_order WHERE original_business_key=?",
            (rs, row) -> new CompensationOutcome(rs.getObject("compensation_id", UUID.class), rs.getString("original_business_key"),
                rs.getString("status"), duplicate, "existing compensation returned"), key);
    }

    /** 将 Outbox 事件载荷序列化为 JSON。 */
    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot serialize payload", exception);
        }
    }

    /** 原始成功结算的内部只读快照。 */
    private record OriginalSettlement(String key, UUID payer, UUID payee, UUID feeAccount, String asset,
                                      BigDecimal amount, BigDecimal fee, String status) {
    }

    /**
     * 补偿处理结果。
     *
     * @param compensationId 补偿单编号
     * @param originalBusinessKey 原始业务键
     * @param status 补偿状态
     * @param duplicate 是否为幂等重放
     * @param detail 处理说明
     */
    public record CompensationOutcome(UUID compensationId, String originalBusinessKey, String status,
                                      boolean duplicate, String detail) {
    }
}
