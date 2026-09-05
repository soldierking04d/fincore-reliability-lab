package dev.fincore.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 交易资产与交易对统一格式的回归测试。
 *
 * @author FinCore Reliability Lab
 * @since 1.4.0
 */
class TradingIdentifiersTest {

    /** 合法的大写字母数字资产和交易对应被所有入口接受。 */
    @Test
    void acceptsSupportedIdentifiers() {
        assertTrue(TradingIdentifiers.isAsset("BTC"));
        assertTrue(TradingIdentifiers.isAsset("1000PEPE"));
        assertTrue(TradingIdentifiers.isSymbol("BTC-USDT"));
        assertTrue(TradingIdentifiers.isSymbol("1000PEPE-USDT"));
    }

    /** 空值、小写、非法字符、缺少一腿和超长代码必须保持拒绝。 */
    @Test
    void rejectsUnsupportedIdentifiers() {
        assertFalse(TradingIdentifiers.isAsset(null));
        assertFalse(TradingIdentifiers.isAsset("btc"));
        assertFalse(TradingIdentifiers.isAsset("B"));
        assertFalse(TradingIdentifiers.isSymbol("BTC"));
        assertFalse(TradingIdentifiers.isSymbol("btc-USDT"));
        assertFalse(TradingIdentifiers.isSymbol("ABCDEFGHIJKLMNOPQRSTU-USDT"));
    }
}
