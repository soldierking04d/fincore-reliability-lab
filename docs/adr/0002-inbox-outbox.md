# ADR-0002: Transactional Inbox and Outbox

Status: Accepted

## Problem

Kafka 的至少一次投递会产生重复消息。数据库提交成功但 Kafka Offset 未确认时，消息必然再次处理；数据库提交成功但结果事件发布失败时，下游又会丢失通知。

## Decision

- 消费端在业务事务内写 Inbox；
- `message_id` 是数据库唯一键；
- 业务单再使用 `business_key` 做第二层唯一性保护；
- 结果事件与账务事务一起写入 Outbox；
- 后台任务原子抢占 Outbox 并发布；
- 发布失败重新进入 PENDING，成功后标记 PUBLISHED。

本项目不声称实现 Kafka 与数据库的“全局 Exactly Once”。它实现的是至少一次投递下可验证的业务幂等和最终事件投递。

