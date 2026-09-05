/**
 * 金融领域值对象、命令、状态机与纯计算规则模块。
 *
 * <p><strong>解决的问题：</strong>集中表达金额、订单状态、账本平衡、分片路由和围栏令牌，使相同规则
 * 不会在 Web、消息和数据库适配层各写一份。</p>
 *
 * <p><strong>CPU 取舍：</strong>高频路由使用整数散列与位运算，多账户排序直接比较 UUID 的两个
 * 64 位分量；资金金额仍统一使用 {@link java.math.BigDecimal}，不会为了减少对象或提高算术速度改用
 * {@code float}/{@code double} 牺牲十进制正确性。</p>
 *
 * <p><strong>边界：</strong>领域对象不依赖 Web、数据库或消息中间件，也不保存跨请求可变权威状态。</p>
 */
package dev.fincore.domain;
