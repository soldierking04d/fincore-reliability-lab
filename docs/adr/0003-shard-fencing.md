# ADR-0003: Shard ownership and fencing

Status: Accepted

## Problem

缩容或网络分区后，旧 Worker 可能认为自己仍拥有分片。仅依赖心跳和 Lease 不够，因为暂停的旧进程恢复后可能继续提交数据。

## Decision

每次发生所有权接管都会增加 Epoch。Kafka Listener 根据付款账户稳定路由到逻辑分片，并在处理前取得或续租 Lease。数据写入方必须同时持有：

- shard id；
- owner id；
- epoch；
- 未过期且处于 RUNNING 的 Lease。

旧 Worker 的 Epoch 小于当前 Epoch，即使恢复也无法通过 Fencing 校验。账务事务使用 `FOR SHARE` 锁定 Lease 行，接管方使用 `FOR UPDATE`，从而关闭“校验通过后、资金提交前所有权变化”的窗口。缩容中的 Worker 先进入 DRAINING，停止领取新任务，再处理或释放已有任务。

控制面 API 和 Kafka 消费数据面均已接入该机制。实验用的进程内直接调用不对外暴露，公开结算 API 只负责向 Kafka 提交命令。
