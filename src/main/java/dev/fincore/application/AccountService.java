package dev.fincore.application;

import dev.fincore.infrastructure.persistence.mapper.AccountMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户生命周期与账本摘要应用服务。
 *
 * <p><strong>解决的问题：</strong>提供账户创建和“余额表—不可变账本”的独立重算视图，使展示余额
 * 不会成为唯一事实来源。</p>
 *
 * <p><strong>CPU 与 I/O 说明：</strong>汇总运算下推 PostgreSQL，避免把账户全部分录载入 JVM；这是
 * 查询/对账路径而非每笔撮合热路径。金额坚持 {@link BigDecimal}，不以浮点数换取表面计算速度。</p>
 *
 * <p><strong>正确性边界：</strong>本服务不直接调账；发现期望余额与当前余额不一致时只返回证据，
 * 修复必须走冻结、调查和可追溯反向分录。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class AccountService {
    /** 账户和账本摘要持久化接口。 */
    private final AccountMapper accountMapper;

    /** @param accountMapper 账户持久化接口 */
    public AccountService(AccountMapper accountMapper) {
        this.accountMapper = accountMapper;
    }

    /**
     * 创建账户并保存期初余额。
     *
     * @param ownerId 账户所有者
     * @param asset 资产代码
     * @param type 账户类型
     * @param openingBalance 非负期初余额
     * @return 新账户快照
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountView create(String ownerId, String asset, String type, BigDecimal openingBalance) {
        if (openingBalance == null || openingBalance.signum() < 0) {
            throw new IllegalArgumentException("opening balance must be non-negative");
        }
        UUID id = UUID.randomUUID();
        accountMapper.insert(id, ownerId, asset, type, openingBalance);
        return get(id);
    }

    /**
     * 查询账户快照。
     *
     * @param id 账户编号
     * @return 账户当前状态
     */
    public AccountView get(UUID id) {
        AccountMapper.AccountRow row = accountMapper.findById(id);
        return new AccountView(row.accountId(), row.ownerId(), row.asset(), row.accountType(),
            row.openingBalance(), row.balance(), row.version());
    }

    /**
     * 从期初余额和账本分录重新计算账户期望余额。
     *
     * @param id 账户编号
     * @return 当前余额、账本净额和期望余额
     */
    public Map<String, Object> ledgerSummary(UUID id) {
        AccountMapper.LedgerSummaryRow row = accountMapper.summarizeLedger(id);
        return Map.of(
            "account_id", row.accountId(),
            "opening_balance", row.openingBalance(),
            "balance", row.balance(),
            "ledger_delta", row.ledgerDelta(),
            "expected_balance", row.expectedBalance()
        );
    }

    /**
     * 账户只读快照。
     *
     * @param accountId 账户编号
     * @param ownerId 所有者
     * @param asset 资产代码
     * @param accountType 账户类型
     * @param openingBalance 期初余额
     * @param balance 当前余额
     * @param version 乐观锁版本
     */
    public record AccountView(UUID accountId, String ownerId, String asset, String accountType,
                              BigDecimal openingBalance, BigDecimal balance, long version) {
    }
}
