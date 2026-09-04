package dev.fincore.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 线性合约的全仓/逐仓、阶梯保证金、资金费和强平损失瀑布纯计算模型。
 *
 * <p>该模型用于复算风险规则，不直接创建仓位或改变钱包。强平执行仍需要撤销挂单、释放占用、分级减仓、
 * 成交回报、账本、保险基金和ADL事件的持久状态机；这里不会把计算结果包装成生产强平系统。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class DerivativeRiskEngine {
    /** 保证金模式。 */
    public enum MarginMode {
        /** 账户内合约共享权益。 */
        CROSS,
        /** 每个仓位只使用自己的隔离保证金。 */
        ISOLATED
    }

    /** 风险状态。 */
    public enum RiskStatus {
        /** 权益高于维持保证金。 */
        SAFE,
        /** 权益已经触及维持保证金。 */
        LIQUIDATION_REQUIRED
    }

    /** 保证金金额精度。 */
    private static final int SCALE = 8;
    /** 按名义金额上限升序排列的风险阶梯。 */
    private final List<MarginTier> tiers;

    /**
     * 创建阶梯风险引擎。
     *
     * @param tiers 至少一个保证金阶梯
     */
    public DerivativeRiskEngine(List<MarginTier> tiers) {
        Objects.requireNonNull(tiers, "tiers");
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个保证金阶梯");
        }
        this.tiers = tiers.stream()
            .sorted(Comparator.comparing(MarginTier::notionalUpperBound))
            .toList();
    }

    /**
     * 评估账户或隔离仓位的权益与维持保证金。
     *
     * @param wallet 账户已实现现金
     * @param positions 当前仓位
     * @param mode 保证金模式
     * @param isolatedCollateral 逐仓模式下每个交易对的隔离保证金
     * @return 每个风险域的可复算结果
     */
    public PortfolioAssessment assess(BigDecimal wallet, List<Position> positions,
                                      MarginMode mode,
                                      Map<String, BigDecimal> isolatedCollateral) {
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(positions, "positions");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(isolatedCollateral, "isolatedCollateral");
        Map<String, RiskBucket> buckets = new LinkedHashMap<>();
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal totalMaintenance = BigDecimal.ZERO;
        for (Position position : positions) {
            BigDecimal pnl = unrealizedPnl(position);
            BigDecimal notional = position.quantity().abs().multiply(position.markPrice());
            MarginTier tier = tierFor(notional);
            BigDecimal maintenance = notional.multiply(tier.maintenanceRate())
                .setScale(SCALE, RoundingMode.HALF_EVEN);
            totalPnl = totalPnl.add(pnl);
            totalMaintenance = totalMaintenance.add(maintenance);
            if (mode == MarginMode.ISOLATED) {
                BigDecimal collateral = isolatedCollateral.get(position.symbol());
                if (collateral == null) {
                    throw new IllegalArgumentException("逐仓仓位缺少隔离保证金：" + position.symbol());
                }
                BigDecimal equity = collateral.add(pnl);
                buckets.put(position.symbol(), bucket(equity, maintenance));
            }
        }
        if (mode == MarginMode.CROSS) {
            BigDecimal equity = wallet.add(totalPnl);
            buckets.put("CROSS", bucket(equity, totalMaintenance));
        }
        return new PortfolioAssessment(mode, wallet, totalPnl, totalMaintenance,
            Map.copyOf(buckets));
    }

    /**
     * 计算新开仓需要的初始保证金。
     *
     * @param quantity 仓位数量
     * @param price 委托保护价或风险价格
     * @return 按适用阶梯计算的初始保证金
     */
    public BigDecimal initialMargin(BigDecimal quantity, BigDecimal price) {
        requirePositive(quantity.abs(), "quantity");
        requirePositive(price, "price");
        BigDecimal notional = quantity.abs().multiply(price);
        return notional.multiply(tierFor(notional).initialRate())
            .setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * 计算资金费对仓位账户的现金变化。
     *
     * <p>正费率时多头支付、空头收取；负费率方向相反。</p>
     *
     * @param position 周期边界仓位快照
     * @param fundingRate 周期资金费率
     * @return 仓位账户现金变化
     */
    public BigDecimal fundingCashDelta(Position position, BigDecimal fundingRate) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(fundingRate, "fundingRate");
        return position.quantity().multiply(position.markPrice()).multiply(fundingRate)
            .negate().setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /**
     * 按固定减仓步长给出本轮最大减仓数量。
     *
     * @param currentQuantity 当前有符号仓位数量
     * @param requestedReduction 本轮风险引擎希望减少的绝对数量
     * @param stepSize 合约最小减仓步长
     * @return 不会穿越零仓位的有符号减仓数量
     */
    public BigDecimal partialLiquidation(BigDecimal currentQuantity,
                                         BigDecimal requestedReduction,
                                         BigDecimal stepSize) {
        if (currentQuantity == null || currentQuantity.signum() == 0) {
            return BigDecimal.ZERO;
        }
        requirePositive(requestedReduction, "requestedReduction");
        requirePositive(stepSize, "stepSize");
        BigDecimal capped = currentQuantity.abs().min(requestedReduction);
        BigDecimal steps = capped.divideToIntegralValue(stepSize);
        BigDecimal executable = steps.multiply(stepSize);
        return currentQuantity.signum() > 0 ? executable.negate() : executable;
    }

    /**
     * 按“保险基金先承担、剩余损失再进入ADL”的顺序计算损失分配。
     *
     * @param bankruptcyLoss 破产账户未覆盖损失
     * @param insuranceBalance 可用保险基金
     * @param candidates 可被自动减仓的盈利对手方
     * @return 保险基金使用额、ADL分配和最终未覆盖额
     */
    public LiquidationWaterfall coverLoss(BigDecimal bankruptcyLoss,
                                          BigDecimal insuranceBalance,
                                          List<AdlCandidate> candidates) {
        requireNonNegative(bankruptcyLoss, "bankruptcyLoss");
        requireNonNegative(insuranceBalance, "insuranceBalance");
        Objects.requireNonNull(candidates, "candidates");
        BigDecimal insuranceUsed = bankruptcyLoss.min(insuranceBalance);
        BigDecimal remaining = bankruptcyLoss.subtract(insuranceUsed);
        Map<String, BigDecimal> allocations = new LinkedHashMap<>();
        List<AdlCandidate> ranked = candidates.stream()
            .sorted(Comparator.comparing(AdlCandidate::rankingScore).reversed()
                .thenComparing(AdlCandidate::accountId))
            .toList();
        for (AdlCandidate candidate : ranked) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal allocated = remaining.min(candidate.maximumContribution());
            if (allocated.signum() > 0) {
                allocations.put(candidate.accountId(), allocated);
                remaining = remaining.subtract(allocated);
            }
        }
        return new LiquidationWaterfall(bankruptcyLoss, insuranceUsed,
            Map.copyOf(allocations), remaining);
    }

    /** 计算单仓位未实现盈亏。 */
    private BigDecimal unrealizedPnl(Position position) {
        return position.markPrice().subtract(position.entryPrice())
            .multiply(position.quantity()).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    /** 根据权益和维持保证金创建风险桶。 */
    private RiskBucket bucket(BigDecimal equity, BigDecimal maintenance) {
        RiskStatus status = equity.compareTo(maintenance) < 0
            ? RiskStatus.LIQUIDATION_REQUIRED : RiskStatus.SAFE;
        BigDecimal deficit = maintenance.subtract(equity).max(BigDecimal.ZERO)
            .setScale(SCALE, RoundingMode.HALF_EVEN);
        return new RiskBucket(equity.setScale(SCALE, RoundingMode.HALF_EVEN),
            maintenance.setScale(SCALE, RoundingMode.HALF_EVEN), deficit, status);
    }

    /** 找到覆盖当前名义金额的第一个风险阶梯。 */
    private MarginTier tierFor(BigDecimal notional) {
        return tiers.stream()
            .filter(tier -> notional.compareTo(tier.notionalUpperBound()) <= 0)
            .findFirst()
            .orElse(tiers.get(tiers.size() - 1));
    }

    /** 校验正数。 */
    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + "必须大于零");
        }
    }

    /** 校验非负数。 */
    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }

    /** 保证金风险阶梯。 */
    public record MarginTier(BigDecimal notionalUpperBound, BigDecimal initialRate,
                             BigDecimal maintenanceRate) {
        /** 检查阶梯上限和费率。 */
        public MarginTier {
            requirePositive(notionalUpperBound, "notionalUpperBound");
            requirePositive(initialRate, "initialRate");
            requirePositive(maintenanceRate, "maintenanceRate");
            if (maintenanceRate.compareTo(initialRate) >= 0) {
                throw new IllegalArgumentException("维持保证金率必须低于初始保证金率");
            }
        }
    }

    /** 线性合约有符号仓位；正数为多仓，负数为空仓。 */
    public record Position(String symbol, BigDecimal quantity,
                           BigDecimal entryPrice, BigDecimal markPrice) {
        /** 检查仓位与价格。 */
        public Position {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(quantity, "quantity");
            if (quantity.signum() == 0) {
                throw new IllegalArgumentException("仓位数量不能为零");
            }
            requirePositive(entryPrice, "entryPrice");
            requirePositive(markPrice, "markPrice");
        }
    }

    /** 单个风险域的权益、维持保证金和缺口。 */
    public record RiskBucket(BigDecimal equity, BigDecimal maintenanceMargin,
                             BigDecimal deficit, RiskStatus status) {
    }

    /** 全账户风险复算结果。 */
    public record PortfolioAssessment(MarginMode mode, BigDecimal wallet,
                                      BigDecimal totalUnrealizedPnl,
                                      BigDecimal totalMaintenanceMargin,
                                      Map<String, RiskBucket> buckets) {
        /** 防止外部修改风险域结果。 */
        public PortfolioAssessment {
            buckets = Map.copyOf(buckets);
        }
    }

    /** ADL候选及其最大可承担额。 */
    public record AdlCandidate(String accountId, BigDecimal rankingScore,
                               BigDecimal maximumContribution) {
        /** 检查候选字段。 */
        public AdlCandidate {
            Objects.requireNonNull(accountId, "accountId");
            requireNonNegative(rankingScore, "rankingScore");
            requireNonNegative(maximumContribution, "maximumContribution");
        }
    }

    /** 强平损失瀑布计算结果。 */
    public record LiquidationWaterfall(BigDecimal bankruptcyLoss,
                                       BigDecimal insuranceUsed,
                                       Map<String, BigDecimal> adlAllocations,
                                       BigDecimal uncoveredLoss) {
        /** 防止外部修改ADL分配。 */
        public LiquidationWaterfall {
            adlAllocations = Map.copyOf(adlAllocations);
        }
    }
}
