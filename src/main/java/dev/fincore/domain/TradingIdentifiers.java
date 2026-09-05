package dev.fincore.domain;

import java.util.regex.Pattern;

/**
 * 交易对和资产代码的统一格式规则。
 *
 * <p><strong>解决的问题：</strong>下单、成交同步、撮合查询和盘前风控必须接受完全相同的标识符，
 * 否则同一请求可能在前置入口通过、在下游恢复或查询阶段失败。</p>
 *
 * <p><strong>CPU 优化：</strong>{@link Pattern} 在类加载时只编译一次，避免每笔订单调用
 * {@link String#matches(String)} 重复解析正则。校验仍是纯计算，不替代数据库产品配置或交易状态。</p>
 *
 * <p><strong>边界：</strong>本项目实验标识符只允许 2—20 位大写字母或数字，交易对由两个资产
 * 以短横线连接；真实交易所若支持其他字符，必须通过版本化产品配置扩展，不能局部放宽某个入口。</p>
 *
 * @author FinCore Reliability Lab
 * @since 1.4.0
 */
public final class TradingIdentifiers {
    /** 单个资产代码规则。 */
    private static final Pattern ASSET_PATTERN = Pattern.compile("[A-Z0-9]{2,20}");
    /** 现货或合约交易对规则。 */
    private static final Pattern SYMBOL_PATTERN =
        Pattern.compile("[A-Z0-9]{2,20}-[A-Z0-9]{2,20}");

    /** 工具类不允许实例化。 */
    private TradingIdentifiers() {
        throw new IllegalStateException("utility class");
    }

    /**
     * 判断资产代码是否符合实验格式。
     *
     * @param asset 待验证资产代码
     * @return 非空且完整匹配资产规则时返回 true
     */
    public static boolean isAsset(String asset) {
        return asset != null && ASSET_PATTERN.matcher(asset).matches();
    }

    /**
     * 判断交易对是否符合实验格式。
     *
     * @param symbol 待验证交易对
     * @return 非空且完整匹配交易对规则时返回 true
     */
    public static boolean isSymbol(String symbol) {
        return symbol != null && SYMBOL_PATTERN.matcher(symbol).matches();
    }
}
