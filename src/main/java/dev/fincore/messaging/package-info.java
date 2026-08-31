/**
 * Kafka 消费与事务 Outbox 发布适配器。
 *
 * <p>消息投递可能重复、乱序或暂时失败，因此消费端必须幂等，发布端必须允许安全重试。</p>
 */
package dev.fincore.messaging;

