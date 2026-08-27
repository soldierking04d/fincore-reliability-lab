# FinCore Demo Walkthrough / 项目演示步骤

本页按系统边界、写入链路、资金规则、并发接管和故障实验组织项目演示步骤。
This walkthrough connects the system design to its executable reliability checks.

## 中文演示步骤

### 1. 查看项目范围

本项目验证金融结算在重复消息、并发、事务失败、补偿、对账、热点账户和服务缩容之后，是否保持唯一、平衡、可审计。

### 2. 查看核心写入链路

结算命令通过 Kafka 进入服务。在同一个 PostgreSQL 事务里，系统完成 Inbox 去重、业务单创建、CAS 状态转换、账户锁定、平衡账本、余额更新、状态审计和 Outbox。任何未处理异常都会导致整体回滚，Kafka 可以安全重试。

最终唯一性由数据库约束和事务保证，不依赖 JVM 内存、Redis 或 Offset。

### 3. 核验资金安全规则

- 金额使用 `BigDecimal` 和 `NUMERIC(38,18)`。
- 每个账本交易的 Debit 和 Credit 必须相等。
- 历史账本只追加，冲正通过独立补偿单和反向流水完成。

### 4. 核验并发和缩容边界

账户按 UUID 固定顺序加行锁，状态通过 CAS 更新。分片 Worker 使用 Lease 和递增 Epoch；新 Worker 接管以后，旧 Worker 即使恢复也无法通过数据面 Fence 校验。校验和资金写入位于同一事务边界。

### 5. 运行自动实验

运行：

```bash
./scripts/run-demo.sh
```

它会自动验证重复结算只有一次资金效果、补偿幂等、手续费分片归集、旧 Epoch 拒写以及对账发现人为余额破坏。每项都必须是 `PASS`。

### 6. 查看 Coding Agent 评测范围

仓库把五类故障定义成统一任务，并使用 100 分 Rubric 评价功能、事务、幂等、恢复、资金安全、测试、性能、维护性和可观测性。评价标准是代码能不能在失败条件下上线，而不是能不能编译。

## English demonstration steps

### 1. Review the project scope

FinCore is an executable settlement-reliability lab. Its scenarios verify unique, balanced, auditable and recoverable financial results under duplicates, races, partial failure, compensation, reconciliation and worker takeover.

### 2. Inspect the transaction boundary

A Kafka command enters the settlement listener. One PostgreSQL transaction records the Inbox item, creates the business order, performs a CAS transition, locks accounts in deterministic order, appends a balanced journal, updates the balance view, writes state audit and Outbox, and completes the Inbox record. Any unhandled failure rolls everything back and Kafka can retry safely.

Uniqueness is enforced by database constraints and the transaction. JVM memory, Redis and an offset do not provide the final financial guarantee.

### 3. Verify financial invariants

Money uses `BigDecimal` and `NUMERIC(38,18)`. Every journal transaction has equal debit and credit totals. Historical postings are append-only. A correction creates a separate compensation order and reverse journal instead of mutating the successful transaction.

### 4. Inspect concurrency and fencing

Accounts are locked in deterministic UUID order and states change through versioned CAS. Workers own logical shards through leases and increasing epochs. After takeover, a recovered old worker cannot pass the data-plane fence. Fence validation and financial writes share the same transaction boundary.

### 5. Run the reliability checks

Run `./scripts/run-demo.sh`. The scenario proves one financial effect under duplicate delivery, an idempotent reverse journal, fee-shard aggregation, stale-epoch rejection, and detection of an injected reconciliation difference. Every check must report `PASS`.

### 6. Review the coding-agent evaluation scope

The same repository defines five controlled repair tasks and one 100-point rubric. A coding agent is judged on concurrency, idempotency, recovery, financial safety, tests, capacity, maintainability, and observability—not merely compilation.

## Useful live links

- Health: http://127.0.0.1:8080/actuator/health
- Prometheus: http://127.0.0.1:9090
- Grafana: http://127.0.0.1:3000
- Latest report: `reports/latest-scenario.json`

