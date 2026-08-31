package dev.fincore.application;

import dev.fincore.infrastructure.persistence.mapper.ReconciliationMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户余额与不可变账本的全量对账服务。
 *
 * <p>服务根据“期初余额 + 贷方 - 借方”计算期望余额，并把不一致项登记为高风险问题。
 * 默认只发现和冻结差异，不自动修改资金，避免错误修复掩盖真实账务问题。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
@Service
public class ReconciliationService {
    /** 账户余额与账本差异持久化接口。 */
    private final ReconciliationMapper reconciliationMapper;

    /** @param reconciliationMapper 账户对账持久化接口 */
    public ReconciliationService(ReconciliationMapper reconciliationMapper) {
        this.reconciliationMapper = reconciliationMapper;
    }

    /**
     * 对全部账户执行余额—账本对账。
     *
     * @return 差异数量和明细；无差异时列表为空
     */
    @Transactional
    public ReconciliationReport reconcileAll() {
        List<AccountDifference> differences = reconciliationMapper.findBalanceDifferences().stream()
            .map(row -> new AccountDifference(row.accountId(), row.expected(), row.actual()))
            .toList();
        for (AccountDifference diff : differences) {
            reconciliationMapper.insertIssue(UUID.randomUUID(), diff.accountId(), diff.expected(),
                diff.actual(), "禁止自动修复；必须由人工复核");
        }
        return new ReconciliationReport(differences.size(), differences);
    }

    /**
     * 单账户对账差异。
     *
     * @param accountId 账户编号
     * @param expected 账本重算余额
     * @param actual 账户表实际余额
     */
    public record AccountDifference(UUID accountId, BigDecimal expected, BigDecimal actual) {
    }

    /**
     * 余额—账本对账报告。
     *
     * @param differenceCount 差异账户数
     * @param differences 差异明细
     */
    public record ReconciliationReport(int differenceCount, List<AccountDifference> differences) {
    }
}
