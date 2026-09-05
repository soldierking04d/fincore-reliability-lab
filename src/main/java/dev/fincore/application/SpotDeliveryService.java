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
 * <p><strong>解决的问题：</strong>把一条成交事实转换成买卖双方两种资产的原子 DvP 交割，重复消息、
 * Worker 接管和账户竞争都不能产生单边到账或重复到账。</p>
 *
 * <p><strong>执行链路：</strong>消息只携带成交编号；金额和四个账户从撮合事务保存的权威事实读取。
 * 事务内验证 Fence、锁交割记录、占用 Inbox、按 UUID 全序锁四个账户、写两组平衡账本、消耗在途、
 * 更新 SETTLED 并写 Outbox，提交前再次验证 Fence。</p>
 *
 * <p><strong>CPU 与锁：</strong>一次交割只处理固定四个账户和两种资产，UUID 直接比较而非转字符串；
 * 不创建并行任务，避免同一事务跨线程和额外上下文切换。主要瓶颈是账户锁与数据库提交，因此
 * Consumer 数按 CPU 配额封顶，不能靠增加线程绕过热点账户。</p>
 *
 * <p><strong>正确性边界：</strong>所有入口必须有有效 Worker Fence，没有无围栏重载。两种资产的
 * 分录、余额、Inbox、预占消耗、资金分桶、终态和 Outbox 同在一个 PostgreSQL 提交边界。</p>
 * @author FinCore Reliability Lab
 * @since 1.3.0
 */
@Service
public class SpotDeliveryService {
    /** 交割事实已经完整记账后的终态。 */
    private static final String DELIVERY_SETTLED = "SETTLED";
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
    @Transactional(rollbackFor = Exception.class)
    public SpotFundsMapper.DeliveryRow settle(SpotDeliveryCommand command, FenceToken fence) {
        Objects.requireNonNull(fence, "Worker fence is required");
        if (fence.shardId() != shardFor(command.tradeId())) {
            throw new FenceRejectedException("fence rejected: wrong delivery shard");
        }
        leases.requireValidFenceForUpdate(fence);
        var row = funds.lockDelivery(command.tradeId());
        int inserted = funds.inbox(command.messageId(), command.tradeId());
        if (inserted == 0 && !funds.inboxTrade(command.messageId()).accountId().equals(command.tradeId())) {
            throw new IllegalArgumentException("delivery messageId reused with a different trade");
        }
        if (DELIVERY_SETTLED.equals(row.status())) {
            duplicates.increment();
            return row;
        }
        // 四个账户先去重排序；同一用户同时充当多腿账户时不会重复加锁。
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
