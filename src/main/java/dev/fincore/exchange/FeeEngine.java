package dev.fincore.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Maker/Taker、VIP阶梯、返佣、精度和成交冲正的手续费纯计算引擎。
 *
 * <p>手续费先舍入一次，再以完全相反的双腿分录入账；冲正必须引用原手续费结果并取反，不能用当前费率
 * 重新计算历史成交。负费率表示平台向用户支付Maker返佣。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.1.0
 */
public final class FeeEngine {
    /** 流动性角色。 */
    public enum LiquidityRole {
        /** 挂单提供流动性。 */
        MAKER,
        /** 主动成交消耗流动性。 */
        TAKER
    }

    /** 手续费金额精度。 */
    private final int scale;
    /** VIP费率阶梯，按最低30日交易量升序。 */
    private final List<FeeTier> tiers;

    /**
     * 创建费率引擎。
     *
     * @param scale 手续费金额小数位数
     * @param tiers VIP费率阶梯
     */
    public FeeEngine(int scale, List<FeeTier> tiers) {
        if (scale < 0 || scale > 18) {
            throw new IllegalArgumentException("手续费精度范围为0—18位");
        }
        Objects.requireNonNull(tiers, "tiers");
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个费率阶梯");
        }
        this.scale = scale;
        this.tiers = tiers.stream()
            .sorted(Comparator.comparing(FeeTier::minimumThirtyDayVolume))
            .toList();
    }

    /**
     * 按成交额、30日交易量和流动性角色计算手续费。
     *
     * @param tradeId 成交编号
     * @param notional 成交名义金额
     * @param thirtyDayVolume 用户30日成交量快照
     * @param role 流动性角色
     * @return 可直接生成双腿账本的确定性手续费结果
     */
    public FeeCharge calculate(String tradeId, BigDecimal notional,
                               BigDecimal thirtyDayVolume, LiquidityRole role) {
        Objects.requireNonNull(tradeId, "tradeId");
        requireNonNegative(notional, "notional");
        requireNonNegative(thirtyDayVolume, "thirtyDayVolume");
        Objects.requireNonNull(role, "role");
        FeeTier tier = tiers.stream()
            .filter(candidate -> thirtyDayVolume.compareTo(
                candidate.minimumThirtyDayVolume()) >= 0)
            .reduce((left, right) -> right)
            .orElseThrow(() -> new IllegalStateException("没有适用的费率阶梯"));
        BigDecimal rateBps = role == LiquidityRole.MAKER
            ? tier.makerBasisPoints() : tier.takerBasisPoints();
        BigDecimal fee = notional.multiply(rateBps)
            .divide(new BigDecimal("10000"), scale, RoundingMode.HALF_EVEN);
        return new FeeCharge(tradeId, tier.name(), role, notional, rateBps, fee,
            fee.negate(), fee);
    }

    /**
     * 对原手续费生成精确反向分录。
     *
     * @param original 原手续费结果
     * @param correctionId 成交撤销或更正编号
     * @return 不受当前VIP等级影响的原额冲正
     */
    public FeeReversal reverse(FeeCharge original, String correctionId) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(correctionId, "correctionId");
        return new FeeReversal(correctionId, original.tradeId(),
            original.userDelta().negate(), original.feeAccountDelta().negate());
    }

    /** 检查非负金额。 */
    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + "不能为负数");
        }
    }

    /** VIP费率阶梯。 */
    public record FeeTier(String name, BigDecimal minimumThirtyDayVolume,
                          BigDecimal makerBasisPoints, BigDecimal takerBasisPoints) {
        /** 检查阶梯字段；Maker费率允许为负数返佣。 */
        public FeeTier {
            Objects.requireNonNull(name, "name");
            requireNonNegative(minimumThirtyDayVolume, "minimumThirtyDayVolume");
            Objects.requireNonNull(makerBasisPoints, "makerBasisPoints");
            Objects.requireNonNull(takerBasisPoints, "takerBasisPoints");
        }
    }

    /** 已舍入且双腿平衡的手续费结果。 */
    public record FeeCharge(String tradeId, String tierName, LiquidityRole role,
                            BigDecimal notional, BigDecimal rateBasisPoints,
                            BigDecimal fee, BigDecimal userDelta,
                            BigDecimal feeAccountDelta) {
        /** @return 用户与手续费账户的变化是否严格平衡 */
        public boolean balanced() {
            return userDelta.add(feeAccountDelta).signum() == 0;
        }
    }

    /** 原手续费的反向分录。 */
    public record FeeReversal(String correctionId, String originalTradeId,
                              BigDecimal userDelta, BigDecimal feeAccountDelta) {
        /** @return 冲正双腿是否严格平衡 */
        public boolean balanced() {
            return userDelta.add(feeAccountDelta).signum() == 0;
        }
    }
}
