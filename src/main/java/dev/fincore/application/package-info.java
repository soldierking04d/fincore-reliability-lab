/**
 * 金融业务用例与事务编排模块。
 *
 * <p><strong>解决的问题：</strong>把用户/KYC、盘前风控、账户、撮合、清结算、补偿、对账、费用归集
 * 和 Worker 接管串成明确用例，避免控制器、Listener 或 SQL 各自拼出不一致的业务流程。</p>
 *
 * <p><strong>CPU 与并发：</strong>写请求按交易对进入有界 Lane，资金账户按 UUID 固定顺序加锁，
 * Outbox、对账和实验编排离开同步热路径。应用层只做必要的金额计算和状态判断，不在 JVM 内复制
 * 权威订单簿或资金账本，也不使用公共 ForkJoinPool 扩散数据库锁竞争。</p>
 *
 * <p><strong>正确性边界：</strong>涉及资金效果的方法必须明确事务边界，并保持幂等唯一约束、
 * 确定性加锁、不可变账本、Inbox/Outbox 原子提交和数据面 Fencing。</p>
 */
package dev.fincore.application;
