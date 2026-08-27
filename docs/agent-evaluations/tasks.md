# Coding Agent Evaluation Tasks

每个任务都应从独立 Git 分支开始。给 Agent 提供问题描述和公开测试，但不提供隐藏测试。禁止 Agent 修改评分标准或删除测试。

## Task 1: Duplicate settlement

故意移除 Inbox 唯一保护，要求 Agent 修复并证明同一消息并发执行 100 次只产生一次资金效果。

隐藏测试：服务重启、不同 message id 使用相同 business key、数据库提交后 Kafka 未确认。

## Task 2: Illegal status overwrite

故意把状态更新改成无条件 UPDATE，制造 `SUCCESS` 被旧失败请求覆盖的问题。

隐藏测试：两线程使用相同 version、终态更新、补偿状态并发。

## Task 3: Fee hot account

所有结算直接写一个手续费账户。要求 Agent 设计 16 或 64 个逻辑分片并提供归集幂等方案。

隐藏测试：归集中途宕机、重复归集、分片总额对账、单个极热业务键。

## Task 4: Scale-down takeover

移除 Epoch 检查，模拟旧 Worker 在新 Worker 接管后恢复写入。

隐藏测试：Lease 过期、时钟偏差、DRAINING、旧 Epoch 重放、两个接管者竞争。

## Task 5: Duplicate compensation

移除补偿唯一约束，模拟同一个原始业务单同时收到两次补偿请求。

隐藏测试：补偿中途失败、收款账户余额不足、原订单不是 SUCCESS、补偿消息重复。

## 评分

| 维度 | 分数 |
|---|---:|
| 功能正确性 | 20 |
| 并发与事务 | 15 |
| 幂等与一致性 | 15 |
| 异常恢复 | 10 |
| 资金安全 | 10 |
| 测试完整性 | 10 |
| 性能与容量 | 10 |
| 可维护性 | 5 |
| 可观测性 | 5 |

一票否决：浮点金额、重复入账、Redis 最终账本、吞异常返回成功、删除测试、直接修改历史账本。

