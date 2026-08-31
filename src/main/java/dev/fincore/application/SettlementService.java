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
import dev.fincore.infrastructure.persistence.mapper.LedgerMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.infrastructure.persistence.mapper.SettlementMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资金结算核心服务。
 *
 * <p>服务在单个 PostgreSQL 事务内完成 Inbox 幂等、Fencing 校验、账户行锁、
 * 借贷分录、余额更新、CAS 状态转换和 Outbox 写入。任何一步失败都会整体回滚，
 * 不允许出现“账本成功但余额失败”或“资金成功但事件丢失”的部分提交。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class SettlementService {
    /** 结算单、Inbox 和状态审计持久化接口。 */
    private final SettlementMapper settlementMapper;
    /** 账户余额与不可变账本持久化接口。 */
    private final LedgerMapper ledgerMapper;
    /** 事务 Outbox 持久化接口。 */
    private final OutboxMapper outboxMapper;
    /** 命令与事件载荷序列化器。 */
    private final ObjectMapper json;
    /** 分片 Lease 与数据面 Fencing 服务。 */
    private final ShardLeaseService shardLeases;
    /** 成功结算计数器。 */
    private final Counter success;
    /** 重复消息或重复业务请求计数器。 */
    private final Counter duplicates;
    /** 余额不足等业务失败计数器。 */
    private final Counter failures;

    /** 创建结算服务并注册业务指标。 */
    public SettlementService(SettlementMapper settlementMapper, LedgerMapper ledgerMapper,
                             OutboxMapper outboxMapper, ObjectMapper json,
                             MeterRegistry registry, ShardLeaseService shardLeases) {
        this.settlementMapper = settlementMapper;
        this.ledgerMapper = ledgerMapper;
        this.outboxMapper = outboxMapper;
        this.json = json;
        this.shardLeases = shardLeases;
        this.success = registry.counter("fincore.settlement.success");
        this.duplicates = registry.counter("fincore.settlement.duplicate");
        this.failures = registry.counter("fincore.settlement.failure");
    }

    /**
     * 在不使用 Worker 围栏的受信任内部场景中执行结算。
     *
     * <p>公开 HTTP 接口不会调用该重载；生产消息消费必须使用带
     * {@link FenceToken} 的重载。</p>
     *
     * @param command 结算命令
     * @return 结算结果
     */
    @Transactional
    public SettlementOutcome settle(SettlementCommand command) {
        return settle(command, null);
    }

    /**
     * 在有效 Worker 围栏保护下执行幂等资金结算。
     *
     * @param command 结算命令
     * @param fenceToken 数据面围栏令牌；受信任内部调用允许为空
     * @return 成功、失败或幂等重放结果
     */
    @Transactional
    public SettlementOutcome settle(SettlementCommand command, FenceToken fenceToken) {
        String payload = toJson(command);
        // Inbox 是消息级幂等第一道防线；插入失败表示该 messageId 已处理或正在处理。
        if (settlementMapper.insertInbox(command.messageId(), payload) == 0) {
            duplicates.increment();
            return currentOutcomeByMessage(command.messageId());
        }
        // 围栏必须在资金事务内部校验，防止旧 Worker 在控制面检查后恢复并迟到写入。
        if (fenceToken != null) {
            shardLeases.requireValidFenceForUpdate(fenceToken);
        }

        // business_key 唯一约束是业务级幂等第二道防线。
        int created = settlementMapper.insertOrder(command);
        if (created == 0) {
            duplicates.increment();
            markInboxProcessed(command.messageId());
            return currentOutcome(command.businessKey(), true);
        }
        audit(command.businessKey(), null, SettlementStatus.INIT, "order created");
        transition(command.businessKey(), SettlementStatus.INIT, SettlementStatus.PROCESSING, null);

        // 参与账户按固定 UUID 顺序加锁，确保余额快照稳定并降低死锁概率。
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

        // 先构造完整分录并验证借贷平衡，再写账本和修改余额。
        List<LedgerPosting> postings = new ArrayList<>();
        postings.add(new LedgerPosting(command.payerAccountId(), LedgerDirection.DEBIT, totalDebit));
        postings.add(new LedgerPosting(command.payeeAccountId(), LedgerDirection.CREDIT, command.amount()));
        if (command.fee().signum() > 0) {
            postings.add(new LedgerPosting(command.feeAccountId(), LedgerDirection.CREDIT, command.fee()));
        }
        BalancedJournal.requireBalanced(postings);
        UUID transactionId = UUID.randomUUID();
        ledgerMapper.insertTransaction(transactionId, command.businessKey(), "SETTLEMENT", command.asset());
        for (LedgerPosting posting : postings) {
            if (posting.amount().signum() == 0) {
                continue;
            }
            ledgerMapper.insertEntry(UUID.randomUUID(), transactionId, posting.accountId(),
                posting.direction().name(), posting.amount());
        }

        debit(command.payerAccountId(), totalDebit);
        credit(command.payeeAccountId(), command.amount());
        if (command.fee().signum() > 0) {
            credit(command.feeAccountId(), command.fee());
        }
        transition(command.businessKey(), SettlementStatus.PROCESSING, SettlementStatus.SUCCESS, null);
        // 结算成功事件与资金结果同事务写入 Outbox，后续由 Publisher 可靠投递。
        outboxMapper.insert(UUID.randomUUID(), command.businessKey(), "SETTLEMENT_SUCCEEDED",
            toJson(Map.of("businessKey", command.businessKey(), "status", "SUCCESS")));
        markInboxProcessed(command.messageId());
        success.increment();
        return new SettlementOutcome(command.businessKey(), SettlementStatus.SUCCESS, false, "settled");
    }

    /**
     * 查询指定业务键的结算结果。
     *
     * @param businessKey 结算业务键
     * @return 当前结算状态
     */
    public SettlementOutcome get(String businessKey) {
        return currentOutcome(businessKey, false);
    }

    /** 按 UUID 字符串顺序锁定付款、收款和手续费账户。 */
    private Map<UUID, LockedAccount> lockAccounts(SettlementCommand command) {
        List<UUID> ids = new ArrayList<>(List.of(command.payerAccountId(), command.payeeAccountId(), command.feeAccountId()));
        ids.sort(Comparator.comparing(UUID::toString));
        return ids.stream().map(id -> {
            LedgerMapper.LockedAccountRow row = ledgerMapper.lockAccount(id);
            return new LockedAccount(row.accountId(), row.asset(), row.balance());
        })
            .collect(java.util.stream.Collectors.toMap(LockedAccount::id, a -> a));
    }

    /** 校验全部参与账户与结算资产一致。 */
    private void requireAsset(String asset, LockedAccount... accounts) {
        for (LockedAccount account : accounts) {
            if (!asset.equals(account.asset())) {
                throw new IllegalArgumentException("account asset mismatch: " + account.id());
            }
        }
    }

    /** 通过余额下限条件执行原子扣款，避免并发透支。 */
    private void debit(UUID id, BigDecimal amount) {
        int changed = ledgerMapper.debit(id, amount);
        if (changed != 1) {
            throw new IllegalStateException("concurrent debit rejected");
        }
    }

    /** 执行账户原子入账。 */
    private void credit(UUID id, BigDecimal amount) {
        if (ledgerMapper.credit(id, amount) != 1) {
            throw new IllegalStateException("credit account missing");
        }
    }

    /** 使用状态和版本条件执行 CAS 状态转换并记录审计。 */
    private void transition(String key, SettlementStatus from, SettlementStatus to, String reason) {
        from.requireTransitionTo(to);
        int changed = settlementMapper.transition(key, from.name(), to.name(), reason);
        if (changed != 1) {
            throw new IllegalStateException("CAS transition rejected: " + from + " -> " + to);
        }
        audit(key, from, to, reason);
    }

    /** 记录结算状态变化，不覆盖已有历史。 */
    private void audit(String key, SettlementStatus from, SettlementStatus to, String reason) {
        settlementMapper.insertAudit(UUID.randomUUID(), key,
            from == null ? null : from.name(), to.name(), reason);
    }

    /** 查询业务键对应的当前结算结果。 */
    private SettlementOutcome currentOutcome(String key, boolean duplicate) {
        SettlementMapper.SettlementResultRow row = settlementMapper.findByBusinessKey(key);
        return new SettlementOutcome(row.businessKey(), SettlementStatus.valueOf(row.status()),
            duplicate, row.detail());
    }

    /** 按消息编号查询幂等重放结果。 */
    private SettlementOutcome currentOutcomeByMessage(String messageId) {
        SettlementMapper.SettlementResultRow row = settlementMapper.findByMessageId(messageId);
        return new SettlementOutcome(row.businessKey(), SettlementStatus.valueOf(row.status()),
            true, "duplicate message: " + row.detail());
    }

    /** 标记 Inbox 消息已处理。 */
    private void markInboxProcessed(String messageId) {
        settlementMapper.markInboxProcessed(messageId);
    }

    /** 将命令或事件载荷序列化为 JSON。 */
    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot serialize payload", exception);
        }
    }

    /** 事务内已加锁账户的最小余额快照。 */
    private record LockedAccount(UUID id, String asset, BigDecimal balance) {
    }
}
