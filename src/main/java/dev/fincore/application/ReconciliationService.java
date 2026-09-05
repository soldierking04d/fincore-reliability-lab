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
 * <p><strong>解决的问题：</strong>账户表可能因缺陷、运维误操作或部分故障偏离不可变账本，本服务按
 * “期初余额 + 贷方 - 借方”重算期望值并登记差异。</p>
 *
 * <p><strong>CPU 与运行方式：</strong>聚合与差异筛选在数据库完成，JVM 只接收不一致账户并写问题单，
 * 不加载全部账本分录。当前实验提供全量入口；生产必须按账户/日期分片、分页和限速，并监控查询
 * 计划、临时文件与数据库 CPU，避免盘中扫描影响交易热路径。</p>
 *
 * <p><strong>正确性边界：</strong>默认只发现、冻结和留证，不自动修改资金或账本，避免错误修复掩盖
 * 真实问题。问题登记与本次差异快照在同一事务内完成。</p>
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
    @Transactional(rollbackFor = Exception.class)
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
