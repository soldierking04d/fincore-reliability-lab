package dev.fincore.application;

import dev.fincore.domain.BalancedJournal;
import dev.fincore.domain.FeeShardRouter;
import dev.fincore.domain.LedgerDirection;
import dev.fincore.domain.LedgerPosting;
import dev.fincore.infrastructure.persistence.mapper.FeeAggregationMapper;
import dev.fincore.infrastructure.persistence.mapper.LedgerMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 手续费账户分片和归集服务。
 *
 * <p>日常结算把手续费写入确定性分片账户，降低单一系统账户的热点锁竞争；归集任务再
 * 按固定 UUID 顺序锁定全部分片和财资账户，通过平衡账本一次性转入财资账户。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class FeeAggregationService {
    /** 手续费账户和归集任务持久化接口。 */
    private final FeeAggregationMapper feeMapper;
    /** 账户余额和不可变账本持久化接口。 */
    private final LedgerMapper ledgerMapper;

    /** 创建手续费归集服务。 */
    public FeeAggregationService(FeeAggregationMapper feeMapper, LedgerMapper ledgerMapper) {
        this.feeMapper = feeMapper;
        this.ledgerMapper = ledgerMapper;
    }

    /**
     * 幂等创建指定资产的全部手续费分片账户。
     *
     * @param asset 资产代码
     * @param shardCount 分片数量，必须是正的 2 的幂
     * @return 按账户所有者排序的分片账户
     */
    @Transactional
    public List<FeeAccount> ensureShards(String asset, int shardCount) {
        FeeShardRouter router = new FeeShardRouter(shardCount);
        for (int shard = 0; shard < shardCount; shard++) {
            String owner = router.accountOwner(shard);
            UUID id = UUID.nameUUIDFromBytes(("fee-shard:" + asset + ":" + shard).getBytes(StandardCharsets.UTF_8));
            feeMapper.insertShardAccount(id, owner, asset);
        }
        return feeMapper.findShards(asset).stream().map(FeeAggregationService::toFeeAccount).toList();
    }

    /**
     * 根据业务键查询应使用的手续费分片账户。
     *
     * @param asset 资产代码
     * @param shardCount 分片数量
     * @param businessKey 稳定业务键
     * @return 目标手续费账户
     */
    public FeeAccount route(String asset, int shardCount, String businessKey) {
        FeeShardRouter router = new FeeShardRouter(shardCount);
        String owner = router.accountOwner(router.shardFor(businessKey));
        return toFeeAccount(feeMapper.findShard(owner, asset));
    }

    /**
     * 幂等创建指定资产的财资账户。
     *
     * @param asset 资产代码
     * @return 财资账户
     */
    @Transactional
    public FeeAccount ensureTreasury(String asset) {
        UUID id = UUID.nameUUIDFromBytes(("fee-treasury:" + asset).getBytes(StandardCharsets.UTF_8));
        feeMapper.insertTreasury(id, asset);
        return toFeeAccount(feeMapper.findTreasury(asset));
    }

    /**
     * 把全部非零手续费分片余额幂等归集到财资账户。
     *
     * @param aggregationKey 归集幂等键
     * @param asset 资产代码
     * @param treasuryAccountId 财资账户编号
     * @return 归集金额和参与分片数量
     */
    @Transactional
    public AggregationOutcome aggregate(String aggregationKey, String asset, UUID treasuryAccountId) {
        if (aggregationKey == null || aggregationKey.isBlank()) {
            throw new IllegalArgumentException("aggregationKey is required");
        }
        // 归集键唯一约束保证重复调度不会二次搬运手续费。
        int created = feeMapper.insertAggregation(aggregationKey, asset, treasuryAccountId);
        if (created == 0) {
            return current(aggregationKey, true);
        }

        AccountRow treasury = toAccountRow(ledgerMapper.findAccount(treasuryAccountId));
        if (!asset.equals(treasury.asset()) || !"SYSTEM_FEE_TREASURY".equals(treasury.type())) {
            throw new IllegalArgumentException("treasury account type or asset mismatch");
        }

        List<UUID> ids = ledgerMapper.findFeeShardIds(asset).stream()
            .map(LedgerMapper.AccountIdRow::accountId)
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        ids.add(treasuryAccountId);
        ids.sort(Comparator.comparing(UUID::toString));
        // 所有账户按固定 UUID 顺序加锁，避免多个归集任务形成锁顺序环。
        List<AccountRow> locked = new ArrayList<>();
        for (UUID id : ids) {
            locked.add(toAccountRow(ledgerMapper.lockAccount(id)));
        }
        List<AccountRow> shards = locked.stream().filter(a -> "SYSTEM_FEE_SHARD".equals(a.type()))
            .filter(a -> a.balance().signum() > 0).toList();
        BigDecimal total = shards.stream().map(AccountRow::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) {
            feeMapper.markEmptySuccess(aggregationKey);
            return new AggregationOutcome(aggregationKey, "SUCCESS", BigDecimal.ZERO, false, 0);
        }

        List<LedgerPosting> postings = new ArrayList<>();
        for (AccountRow shard : shards) {
            postings.add(new LedgerPosting(
                shard.id(),
                LedgerDirection.DEBIT,
                shard.balance()
            ));
        }
        postings.add(new LedgerPosting(treasuryAccountId, LedgerDirection.CREDIT, total));
        BalancedJournal.requireBalanced(postings);
        // 分录、余额和任务状态在同一事务提交，任一步失败都会整体回滚。
        UUID transactionId = UUID.randomUUID();
        ledgerMapper.insertTransaction(
            transactionId, "FEE_AGG:" + aggregationKey, "FEE_AGGREGATION", asset);
        for (LedgerPosting posting : postings) {
            ledgerMapper.insertEntry(UUID.randomUUID(), transactionId, posting.accountId(),
                posting.direction().name(), posting.amount());
        }
        for (AccountRow shard : shards) {
            if (ledgerMapper.debitExact(shard.id(), shard.balance(), shard.balance()) != 1) {
                throw new IllegalStateException("fee shard changed during aggregation");
            }
        }
        ledgerMapper.credit(treasuryAccountId, total);
        feeMapper.markSuccess(aggregationKey, total);
        return new AggregationOutcome(aggregationKey, "SUCCESS", total, false, shards.size());
    }

    /** 把通用账户锁快照转换为归集服务内部模型。 */
    private static AccountRow toAccountRow(LedgerMapper.LockedAccountRow row) {
        return new AccountRow(row.accountId(), row.asset(), row.accountType(), row.balance());
    }

    /** 查询已有归集结果，用于幂等重放。 */
    private AggregationOutcome current(String key, boolean duplicate) {
        FeeAggregationMapper.AggregationRow row = feeMapper.findAggregation(key);
        return new AggregationOutcome(row.aggregationKey(), row.status(), row.totalAmount(), duplicate, 0);
    }

    /** 把 Mapper 记录转换为对外手续费账户快照。 */
    private static FeeAccount toFeeAccount(FeeAggregationMapper.FeeAccountRow row) {
        return new FeeAccount(row.accountId(), row.ownerId(), row.asset(), row.balance());
    }

    /** 归集事务使用的账户内部快照。 */
    private record AccountRow(UUID id, String asset, String type, BigDecimal balance) {
    }

    /**
     * 手续费账户快照。
     *
     * @param accountId 账户编号
     * @param ownerId 所有者
     * @param asset 资产代码
     * @param balance 当前余额
     */
    public record FeeAccount(UUID accountId, String ownerId, String asset, BigDecimal balance) {
    }

    /**
     * 手续费归集结果。
     *
     * @param aggregationKey 归集幂等键
     * @param status 归集状态
     * @param totalAmount 归集总额
     * @param duplicate 是否为幂等重放
     * @param aggregatedShardCount 发生资金移动的分片数量
     */
    public record AggregationOutcome(String aggregationKey, String status, BigDecimal totalAmount,
                                     boolean duplicate, int aggregatedShardCount) {
    }
}
