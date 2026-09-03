package dev.fincore.application;

import dev.fincore.domain.FenceToken;
import dev.fincore.domain.ShardRouter;
import dev.fincore.domain.SpotDeliveryCommand;
import dev.fincore.domain.UuidOrder;
import dev.fincore.infrastructure.persistence.mapper.LedgerMapper;
import dev.fincore.infrastructure.persistence.mapper.OutboxMapper;
import dev.fincore.infrastructure.persistence.mapper.SpotFundsMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成交驱动的现货双资产原子交割。
 *
 * <p>所有入口必须有有效 Worker Fence；没有无围栏重载。消息只指定成交编号，金额和四个账户
 * 来自撮合事务保存的事实。两种资产分别有平衡账本事务，但同在一个 PostgreSQL 提交边界内，
 * 与 Inbox、预占消耗、资金分桶、SETTLED 终态及 Outbox 一起成功或一起回滚。</p>
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Service
public class SpotDeliveryService {
    /** 金额零值。 */
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    /** 交割与资金分桶持久化。 */
    private final SpotFundsMapper funds;
    /** 不可变金融账本。 */
    private final LedgerMapper ledger;
    /** 成功事件持久化。 */
    private final OutboxMapper outbox;
    /** 数据面围栏。 */
    private final ShardLeaseService leases;
    /** 根据买方计价资产付款账户确定 Worker 分片。 */
    private final ShardRouter router;
    /** 已完成交割计数。 */
    private final Counter completed;
    /** 业务重复计数。 */
    private final Counter duplicates;

    /** 创建交割服务，复用已有分片数量及围栏实现。 */
    public SpotDeliveryService(SpotFundsMapper funds, LedgerMapper ledger, OutboxMapper outbox,
                                ShardLeaseService leases, MeterRegistry registry,
                                @Value("${fincore.worker.shard-count:8}") int shardCount) {
        this.funds = funds;
        this.ledger = ledger;
        this.outbox = outbox;
        this.leases = leases;
        this.router = new ShardRouter(shardCount);
        this.completed = registry.counter("fincore.spot.delivery.completed");
        this.duplicates = registry.counter("fincore.spot.delivery.duplicate");
    }

    /** 由数据库事实计算路由，不能信任外部传入账户或分片。 */
    public int shardFor(UUID tradeId) {
        var row = Objects.requireNonNull(funds.delivery(tradeId), "spot delivery does not exist");
        return router.shardFor(row.buyerQuoteId().toString());
    }

    /** 只读查询；未完成交割绝不映射成成功。 */
    public SpotFundsMapper.DeliveryRow get(UUID tradeId) {
        return Objects.requireNonNull(funds.delivery(tradeId), "spot delivery does not exist");
    }

    /**
     * 在有效分片围栏内执行一次原子交割。晚到、重复、不同成交乱序均通过独立成交键处理；
     * 相同 messageId 偷换 tradeId 必须回滚，不确认该错误消息。
     */
    @Transactional
    public SpotFundsMapper.DeliveryRow settle(SpotDeliveryCommand command, FenceToken fence) {
        Objects.requireNonNull(fence, "Worker fence is required");
        if (fence.shardId() != shardFor(command.tradeId())) {
            throw new IllegalStateException("fence rejected: wrong delivery shard");
        }
        leases.requireValidFenceForUpdate(fence);
        var row = funds.lockDelivery(command.tradeId());
        int inserted = funds.inbox(command.messageId(), command.tradeId());
        if (inserted == 0 && !funds.inboxTrade(command.messageId()).accountId().equals(command.tradeId())) {
            throw new IllegalArgumentException("delivery messageId reused with a different trade");
        }
        if ("SETTLED".equals(row.status())) {
            duplicates.increment();
            return row;
        }
        for (UUID id : UuidOrder.uniqueSorted(row.buyerQuoteId(), row.buyerBaseId(),
            row.sellerBaseId(), row.sellerQuoteId())) {
            if (funds.lockFunds(id).financialHold()) {
                throw new IllegalStateException("financial account frozen for review");
            }
        }
        requireAsset(row.buyerQuoteId(), row.quoteAsset());
        requireAsset(row.sellerQuoteId(), row.quoteAsset());
        requireAsset(row.buyerBaseId(), row.baseAsset());
        requireAsset(row.sellerBaseId(), row.baseAsset());

        transfer(row.tradeId(), row.quoteAsset(), row.buyerQuoteId(), row.sellerQuoteId(), row.quoteAmount());
        transfer(row.tradeId(), row.baseAsset(), row.sellerBaseId(), row.buyerBaseId(), row.quantity());
        requireChanged(funds.changeReservation(row.buyOrderId(), ZERO, row.quoteAmount().negate(), row.quoteAmount(), ZERO));
        requireChanged(funds.changeReservation(row.sellOrderId(), ZERO, row.quantity().negate(), row.quantity(), ZERO));
        requireChanged(funds.complete(row.tradeId()));
        requireChanged(outbox.insert(UUID.randomUUID(), row.tradeId().toString(), "SPOT_DVP_SETTLED",
            "{\"tradeId\":\"" + row.tradeId() + "\",\"status\":\"SETTLED\"}"));
        // 事务等待账户锁期间 Lease 可能已经超时，提交前再次失败关闭。
        leases.requireValidFenceForUpdate(fence);
        completed.increment();
        return funds.delivery(row.tradeId());
    }

    /** 按资产写入借贷平衡的两腿账本，随后消耗付款在途并增加收款可用。 */
    private void transfer(UUID tradeId, String asset, UUID debit, UUID credit, BigDecimal amount) {
        if (debit.equals(credit) || amount.signum() <= 0) {
            throw new IllegalStateException("invalid spot delivery legs");
        }
        UUID transaction = UUID.randomUUID();
        requireChanged(ledger.insertTransaction(transaction, "spot:" + tradeId + ":" + asset, "SPOT_DELIVERY", asset));
        if (ledger.insertEntries(transaction, List.of(
            new LedgerMapper.LedgerEntryRow(UUID.randomUUID(), debit, "DEBIT", amount),
            new LedgerMapper.LedgerEntryRow(UUID.randomUUID(), credit, "CREDIT", amount))) != 2) {
            throw new IllegalStateException("incomplete spot ledger");
        }
        requireChanged(funds.changeFunds(debit, amount.negate(), ZERO, amount.negate()));
        requireChanged(funds.changeFunds(credit, amount, ZERO, ZERO));
        requireChanged(funds.journal("settle:" + tradeId, debit, ZERO, ZERO, amount.negate(), amount.negate()));
        requireChanged(funds.journal("settle:" + tradeId, credit, amount, ZERO, ZERO, amount));
    }

    /** 账户资产必须与权威交割资产一致。 */
    private void requireAsset(UUID account, String asset) {
        if (!funds.funds(account).asset().equals(asset)) {
            throw new IllegalStateException("spot delivery account asset mismatch");
        }
    }

    /** 状态或余额更新不完整时拒绝提交。 */
    private static void requireChanged(int changed) {
        if (changed != 1) {
            throw new IllegalStateException("spot delivery update rejected");
        }
    }
}
