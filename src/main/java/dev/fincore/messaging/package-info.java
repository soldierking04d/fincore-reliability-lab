/**
 * Kafka 消费与事务 Outbox 发布适配器模块。
 *
 * <p><strong>解决的问题：</strong>把数据库内的业务事实可靠传播到 Kafka，并把至少一次投递转换成
 * 最多一次金融效果。</p>
 *
 * <p><strong>CPU 与线程：</strong>Consumer 使用固定平台线程并受容器 CPU 配额限制；Outbox 在后台
 * 批量抢占、异步发送和批量回写，减少轮询、序列化和 JDBC 往返对交易热路径的干扰。</p>
 *
 * <p><strong>边界：</strong>消息可能重复、乱序、超时或结果未知；消费端必须依靠 Inbox、业务唯一键
 * 和 Epoch Fencing，发布端必须允许安全重试，Offset 不能作为资金账本。</p>
 */
package dev.fincore.messaging;
