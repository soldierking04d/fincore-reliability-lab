package dev.fincore.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 结算状态机。
 *
 * <p>成功结算是终态，不能被旧线程覆盖。冲正通过独立补偿流程表达，不允许直接把
 * SUCCESS 改回处理中或失败状态。</p>
 *
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public enum SettlementStatus {
    /** 结算单已创建，尚未开始资金处理。 */
    INIT,

    /** 正在锁账户并执行资金事务。 */
    PROCESSING,

    /** 资金与账本均已成功提交的终态。 */
    SUCCESS,

    /** 结算失败，可根据业务规则进入补偿。 */
    FAILED,

    /** 正在执行独立反向账本补偿。 */
    COMPENSATING,

    /** 反向账本补偿已经完成的终态。 */
    COMPENSATED;

    /** 每个状态允许到达的后继状态集合。 */
    private static final Map<SettlementStatus, Set<SettlementStatus>> ALLOWED = Map.of(
        INIT, EnumSet.of(PROCESSING),
        PROCESSING, EnumSet.of(SUCCESS, FAILED),
        FAILED, EnumSet.of(COMPENSATING),
        COMPENSATING, EnumSet.of(COMPENSATED, FAILED),
        SUCCESS, EnumSet.noneOf(SettlementStatus.class),
        COMPENSATED, EnumSet.noneOf(SettlementStatus.class)
    );

    /**
     * 判断当前状态是否允许转换到目标状态。
     *
     * @param target 目标状态
     * @return 是否为合法状态转换
     */
    public boolean canTransitionTo(SettlementStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    /**
     * 强制校验状态转换是否合法。
     *
     * @param target 目标状态
     * @throws IllegalStateException 目标状态不在允许集合中时抛出
     */
    public void requireTransitionTo(SettlementStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("illegal status transition: " + this + " -> " + target);
        }
    }
}
