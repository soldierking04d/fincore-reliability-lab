package dev.fincore.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 带资产类型的高精度金额值对象。
 *
 * <p>金融金额统一使用 {@link BigDecimal}，并固定为 18 位小数。不同资产之间禁止直接
 * 加减，避免把 USDT、BTC 等不同计价单位混入同一计算。</p>
 *
 * @param amount 金额
 * @param asset 资产代码
 * @author FinCore Reliability Lab
 * @since 2026-08-27
 */
public record Money(BigDecimal amount, String asset) {
    /** 统一金额精度并校验资产代码。 */
    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(asset, "asset");
        if (asset.isBlank()) {
            throw new IllegalArgumentException("asset is blank");
        }
        amount = amount.setScale(18, RoundingMode.UNNECESSARY);
    }

    /**
     * 从十进制字符串创建金额，避免调用方先经过浮点数造成精度损失。
     *
     * @param amount 十进制金额字符串
     * @param asset 资产代码
     * @return 金额值对象
     */
    public static Money of(String amount, String asset) {
        return new Money(new BigDecimal(amount), asset);
    }

    /**
     * 对同资产金额执行加法。
     *
     * @param other 另一个同资产金额
     * @return 相加后的新金额对象
     */
    public Money add(Money other) {
        requireSameAsset(other);
        return new Money(amount.add(other.amount), asset);
    }

    /**
     * 对同资产金额执行减法。
     *
     * @param other 另一个同资产金额
     * @return 相减后的新金额对象
     */
    public Money subtract(Money other) {
        requireSameAsset(other);
        return new Money(amount.subtract(other.amount), asset);
    }

    /** @return 金额是否小于零 */
    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** @return 金额是否大于零 */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /** 校验参与运算的金额属于同一资产。 */
    private void requireSameAsset(Money other) {
        if (!asset.equals(other.asset)) {
            throw new IllegalArgumentException("asset mismatch");
        }
    }
}
