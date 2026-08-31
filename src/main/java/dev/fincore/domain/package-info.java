/**
 * 金融领域值对象、命令、状态机与纯计算规则。
 *
 * <p>领域对象不依赖 Web、数据库或消息中间件；金额统一使用 {@link java.math.BigDecimal}，禁止使用
 * {@code float} 或 {@code double} 表示资金。</p>
 */
package dev.fincore.domain;

