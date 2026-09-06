# FinCore Demo Walkthrough / 项目演示步骤

本页提供可复核的项目演示路线：先识别系统边界，再查看交易与账务实现，最后在隔离环境运行故障实验。
This walkthrough links the system design to executable evidence. It is not a production readiness or capacity claim.

## 一、访问公开展示 / Public overview

- [公网项目首页](https://124.223.164.254/)
- [项目 README 与阅读大纲](../../README.md)
- [整体架构与服务拓扑](../resilient-system-architecture.md)
- [Java/C++ 专项和实测边界](../java-cpp-trading-evidence.md)

从首页了解业务链路、关键不变量、源码位置和实验结果。公开仪表盘中的模拟资产、有限样本与已实现能力应分别阅读。

## 二、沿事务边界阅读 / Follow the transaction boundary

结算命令经 Kafka 进入服务，在一个 PostgreSQL 事务内完成 Inbox 去重、业务单创建、CAS 状态转换、
固定顺序账户加锁、平衡账本、余额更新、状态审计及 Outbox。未处理异常导致整体回滚，消息可以重试。

Uniqueness is enforced by database constraints and transactions. A Kafka offset, JVM cache entry or successful HTTP response alone does not prove a completed financial effect.

重点核验：

- 金额使用 `BigDecimal` 与数据库精确数值类型；精度以对应业务表和字段定义为准。
- 每笔账本交易借贷平衡，历史流水只追加；补偿通过独立业务单及反向流水完成。
- 同一业务意图被重复投递时只能产生一次资金效果。
- 分片 Worker 使用 Lease 与递增 Epoch；数据面围栏与资金写入必须在同一事务边界校验。

## 三、在隔离环境运行实验 / Run in an isolated environment

先按 [一键启动](../../README.md#一键启动) 准备本地依赖。实验会创建模拟业务数据并注入故障，
不要把测试脚本直接指向真实资金或其他应用共用的生产数据库。

在仓库根目录运行：

```bash
./scripts/run-demo.sh
```

| 检查 | 预期可观察结果 |
|---|---|
| 重复结算 | 同一业务意图只有一次资金效果 |
| 补偿重试 | 反向账本幂等，不重复冲正 |
| 手续费分片归集 | 归集结果与输入账本一致 |
| Worker 接管 | 新 Epoch 接管后旧 Epoch 写入被拒绝 |
| 人为余额差异 | 对账能发现故障注入造成的差异 |

每项业务断言应报告 `PASS`。同时检查报告中的输入和结果，不把进程正常退出当作资金验证成功。

The scenario exercises duplicate delivery, idempotent compensation, fee aggregation, fencing and reconciliation. Inspect both the assertions and the produced report; a healthy process alone is not evidence of financial correctness.

## 四、查看观测与报告 / Inspect evidence

以下地址仅适用于 README 默认本地依赖配置，不是公网服务端口：

- 本地应用健康：`http://127.0.0.1:8080/actuator/health`
- 本地 Prometheus：`http://127.0.0.1:9090`
- 本地 Grafana：`http://127.0.0.1:3000`
- 本地生成报告：`reports/latest-scenario.json`

结合业务结果查看队列、数据库锁、消息积压和 JVM 指标。有限实验用于验证语义与恢复路径，
不能直接推导目标硬件的生产吞吐、长稳或 RTO/RPO。

## 五、进一步验证 / Further validation

- [高并发与 JVM 参数](../high-concurrency-jvm-tuning.md)
- [恢复与容量证据](../recovery-capacity-evidence.md)
- [Java/C++ 复现说明](../../experiments/java-cpp-matching/README.md)
- [团队治理与产品运营协同](../management/README.md)

Coding-agent evaluation uses the repository's controlled tasks and rubric to compare implementation correctness,
recovery, tests and maintainability. It remains a bounded lab evaluation, not an authorization to deploy an agent's changes to a real financial system.
