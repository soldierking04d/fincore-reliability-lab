# 结算对照工具：把响应、终态与核账分开

返回[性能专项总览](../../docs/performance/README.md)。已有 `run-performance-lab.sh` 是原混合负载入口；本目录新增的 `settlement-comparison.mjs` 专门比较账户共享造成的结算争用，不替代持续容量压测。

## 1. 执行边界

- Node 24；默认只打印计划，零网络请求。`--run --confirm-isolated` 同时存在才创建合成账户并提交结算。
- 仅接受 `http://127.0.0.1:显式端口`，端口至少 1024；拒绝 DNS 名称、公网、凭据、路径、查询参数和重定向。不读取 Token、钱包或生产配置。
- **回环地址不是隔离证明**：不能指向生产 SSH 隧道、反向代理或现有真实资金库。操作人必须确认数据源和 Broker 都是独立实验资源。
- 每轮创建全新的 `PF…` 合成资产；付款、收款和费用账户不复用。无归集、无删除、无自动重发、无自动补偿。中断不代表已经发出的命令被撤销。
- 本工具只验证整数合成资金；不替代全部 `NUMERIC(38,18)` 金额、现货预占、双资产交割及安全测试。

## 2. 从零准备独立实验环境

前提：本机 Node 24、Docker Engine/Desktop 与 Compose v2；镜像和 Maven 依赖可下载。Docker 建议分配至少 6GiB 可用内存并保留构建空间。这是启动预算建议，不是已测容量。

使用专用 [compose.isolated.yml](../../infra/performance/compose.isolated.yml)，**不与根目录 Compose 合并**。它只发布 `127.0.0.1:18080`，数据库与 Broker 不映射宿主端口；卷由项目名隔离，无 external volume、无生产配置挂载。实验密码是公开占位密码，只适用该隔离网络。

以下所有命令均从仓库根目录执行。示例项目名 `fincore-perf-20260920a` 应为本次新建且未使用的名称；先只读检查，若已有同名实例/卷或端口 18080 被占用，停止并查明归属，不删除或覆盖它：

```bash
docker context show
docker compose ls -a
docker ps --filter publish=18080
docker volume ls --filter label=com.docker.compose.project=fincore-perf-20260920a
docker volume inspect fincore-perf-20260920a_performance-postgres fincore-perf-20260920a_performance-kafka fincore-perf-20260920a_performance-runtime
docker compose -p fincore-perf-20260920a -f infra/performance/compose.isolated.yml config --quiet
```

`volume inspect` 预期对三个名字都报告不存在；若返回任何既有卷，停止并换用未使用的项目名，不复用它。精确名称检查用于发现没有 Compose 标签的旧卷。确认 Docker context 及可能设置的 DOCKER_HOST / DOCKER_CONTEXT 指向自己的本机环境，不是远程服务器；确认项目名未使用后再启动：

```bash
docker compose -p fincore-perf-20260920a -f infra/performance/compose.isolated.yml up -d --build
docker compose -p fincore-perf-20260920a -f infra/performance/compose.isolated.yml ps -a
curl --fail http://127.0.0.1:18080/actuator/health
```

首次构建可能较慢；`health` 为 UP 后才运行工具。若 app 未启动，查看该项目的 `app/postgres/kafka/kafka-init` 日志；如果 Kafka 卷权限或镜像拉取失败，先解决该实验栈，不切换成公网实例。配置固定 app 为 2 CPU / 1536MiB、Consumer 2、Hikari 12；与主项目默认 4 个 Consumer 不同，比较报告必须记录实际配额。Kafka 单 Broker / 单副本不提供生产级容灾证明。

本轮只静态检查隔离配置，未实际启动该 Docker 栈；完整启动与真实吞吐数据仍待具备容器条件的环境复验，不能把模板存在当作运行成功。

## 3. 三组输入与运行步骤

| 场景 | payer | payee | fee | 主要比较对象 |
| --- | --- | --- | --- | --- |
| `shared-fee` | 每笔独立 | 每笔独立 | 公共 1 个 | 公共手续费行 |
| `sharded-fee` | 每笔独立 | 每笔独立 | 正式分片 16 个 | 分散费用写入后的变化 |
| `hot-payer` | 公共 1 个 | 每笔独立 | 正式分片 16 个 | 分片不能消除付款方热点 |

每笔金额 10、费用 1；独立 payer 期初 111，热点 payer 期初 `笔数×11+100`，payee 和 fee 期初为 0。按真实 route API 固定每笔 `feeAccountId`；保存实际费用分布，不能假设恰好平均。每轮 runId/业务键新建，工具没有固定随机种子选项，不能宣称不同轮次的 hash 分布完全相同；比较时结合实际分布和多轮波动。

先跑无网络测试和计划：

```bash
node --test scripts/performance/settlement-comparison.test.mjs
node scripts/performance/settlement-comparison.mjs --scenario shared-fee --count 64 --concurrency 4
```

在已确认的独立实验栈上执行；准备与测量可能需要等待，不因终态查询短暂不可用而再次启动同一轮：

```bash
node scripts/performance/settlement-comparison.mjs \
  --scenario shared-fee --count 64 --concurrency 4 \
  --deadline-ms 60000 --poll-ms 200 \
  --base-url http://127.0.0.1:18080 --run --confirm-isolated
```

然后依次把场景改为 `sharded-fee` 和 `hot-payer`，其他参数不变。不要并行跑三组互相干扰。工具不维护磁盘报告：**运行前保存终端会话，或用两个不重复文件分别接收 stdout（最终 JSON）与 stderr（恢复清单）**，避免覆盖上一轮。stdout 尚无结果不代表未发生写入。

- 首次写入前输出 `MANIFEST`：runId、asset、场景、计划数量。
- 创建及路由完毕、首个结算提交前输出 `PREPARED`：账户与稳定业务键清单。
- 结束时输出最终 JSON。手动中断后用上述清单查权威状态，不重新生成键“补发”。创建账户时响应丢失也可能已经提交，可按合成 asset 检索，不以本地 accounts 数量推断所有写入。

准备和核账各限 60s；单请求连同响应体最多 8s；测量阶段整体由 `--deadline-ms` 限制。数量 1～1000、并发 1～32、测量时限 1～300s、轮询间隔 50～2000ms。超时保留现场，不自动清库或重试。1000 个账户组可能无法在 60s 内准备完，届时应减小组大小或先单独完善夹具，而非取消上限。

## 4. 报告如何读

| 字段 | 正确含义 |
| --- | --- |
| acceptedCount / acceptanceLatencyMs | 收到匹配本业务键的 HTTP 202 / ACCEPTED；不是资金到账 |
| ackUnknownCount / ackHttp | 没拿到有效接受确认的数量及已知 HTTP 状态；可能已接受，不等于资金失败 |
| successCount / failedCount / unresolvedCount | 逐业务键查询到的 SUCCESS / FAILED / 到期仍 UNKNOWN |
| notSubmittedCount | 总体期限到达时尚未提交的数量，不能从报告分母中隐去 |
| plannedFeeDistribution | 准备阶段实际路由的计划命令数，含零命中分片；最终成功收费分布仍需按 SQL 核对 |
| observedCompletedPerSecond | 测量窗口内观察到成功的数量/秒；排除准备与核账，**不是生产 TPS 上限** |
| observedFinalLatencyMs | 请求开始至首次查询看到 SUCCESS 的时间上界，包含轮询与网络误差 |
| reconciliation | 用独立 BigInt 期望核对期初、余额、账本净额、重算余额；不是完整逐笔审计 |

JSON 金额在解析阶段保留原始数字文本，再做精确比较；不能让 double 把 `100.000000000000000001` 变成 100。`0E-18` 允许作为精确零，其他非整数不属于本工具输入模型。分位数为 nearest-rank，少于 1000 样本会标 `lowSampleWarning`；不要删除此提示。

本工具采用**有界并发闭环**：提交及终态轮询共同占用一个槽位；服务变慢会降低下一笔的产生速率。因此不能用它证明固定到达率的持续稳定性，也不能掩盖 coordinated omission。需要持续容量时另用固定到达率负载并记录未启动迭代、拒绝与未知集合，说明参见 [k6 到达率执行器](https://grafana.com/docs/k6/latest/using-k6/scenarios/concepts/arrival-rate-vu-allocation/)。查询本身会占数据库资源；比较时固定轮询间隔并记录压测端 CPU。

| 最终状态 | 处理方式 |
| --- | --- |
| PLAN_ONLY | 未联网，非性能结果 |
| VERIFIED_LAB_RUN | 本轮全部成功、有效接受确认齐全、账户级摘要匹配；仅限本轮检查范围 |
| VERIFIED_WITH_ACK_WARNINGS | 已核到成功且账户匹配，但接受确认存在未知；检查恢复清单，不能无告警归为正常 |
| SETUP_FAILED | 尚未提交结算；可能已经创建部分账户，按 asset 保留并核查 |
| INCONCLUSIVE | 已提交命令仍有未知终态，不以假定成功/失败核账 |
| INCOMPLETE_LAB_RUN | 有业务失败或未发送，不能宣称本组负载全部成功 |
| INCONCLUSIVE_RECONCILIATION | 核账超时、接口不可用或证据缺失；unverifiedAccounts 不是资金差异 |
| FAILED_RECONCILIATION | 有有效证据显示与本轮预期不符；暂停新实验，保留清单，不自动调平 |

退出码 0 只用于计划或无告警 VERIFIED；其他报告状态为 2，参数/工具错误为 1。真实差异与未知并存时保留两个清单，以 FAILED_RECONCILIATION 优先告警。

## 5. 逐笔数据库审计：净额相等还不够

`settlement-database-audit.mjs` 补充最终报告的账户级核账。它默认只生成 SQL，不连接网络；执行 SQL 必须使用本轮隔离数据库的专用只读角色。它与 `settlement-comparison.mjs` 应放在同一目录；Compose 初始化依赖同目录下的 [01-audit-role.sql](../../infra/performance/01-audit-role.sql)，不能只下载 YAML。

先把最终 stdout JSON 保存为本轮新的 `run.json`。若进程在 PREPARED 后中断，完整准备清单也可用来审计已有事实；不要重新发命令来凑完整结果。示例文件名只用于新目录，勿覆盖旧证据：

```bash
# 仅生成受限 SQL；不发 HTTP、不提交结算、不签名。
node scripts/performance/settlement-database-audit.mjs --report run.json --sql > audit.sql

# 仅限本节创建的独立栈。角色、库名和隔离标记有硬校验。
docker compose -p fincore-perf-20260920a -f infra/performance/compose.isolated.yml exec -T postgres \
  psql -X -q -U fincore_audit -d fincore_performance < audit.sql > snapshot.json

# 本地精确核对；不修改数据库。
node scripts/performance/settlement-database-audit.mjs --report run.json --snapshot snapshot.json
node --test scripts/performance/settlement-comparison.test.mjs scripts/performance/settlement-database-audit.test.mjs
```

SQL 使用 `REPEATABLE READ READ ONLY` 一致快照、15 秒语句超时与 2 秒锁超时。仅认可 `fincore_performance`、`fincore_audit` 及 `fincore.isolated_performance=synthetic-only`；不能为了通过而给生产库加标记。初始化 SQL 只会在新 PostgreSQL 卷首次启动时运行；已有实验卷缺角色时先保留现场，重新创建独立实验项目，不复用生产账号绕过。

核验对象包括完整业务键集、每笔恰好一组预期分录、借贷平衡、期初与终值、经济字段、Inbox 关联及 Outbox 发布状态。金额保留原始十进制文本，按 BigInt 精确核验；关联本轮业务/资产的非标准 messageId 也不能逃过集合检查。

| 审计结果 | 含义与下一步 |
| --- | --- |
| VERIFIED_DATABASE_AUDIT | 本轮快照中全部检查满足；不证明下游完成或生产容量 |
| INCONCLUSIVE | 订单未可见、非终态或 Outbox 尚未发布；等待后重新取只读快照，不重发资金命令 |
| FAILED | 重复/额外分录、未知关联行、金额或载荷错误等确证差异；暂停新实验并保留原始文件 |

即使账户净额相同，额外一借一贷也会因笔数和唯一性失败。单纯在夹具中写入 PUBLISHED 只验证审计判定，不是 Kafka 实际发布证明。本轮 Node 协议测试及本机 PostgreSQL 合成快照实验见[补齐记录](../../docs/performance/settlement-completion.md)。

## 6. 补充诊断、停止与保留

```bash
docker compose -p fincore-perf-20260920a -f infra/performance/compose.isolated.yml exec -T kafka \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group fincore-settlement-v1
docker compose -p fincore-perf-20260920a -f infra/performance/compose.isolated.yml exec postgres \
  psql -X -U fincore_lab -d fincore_performance
```

进入 psql 后按[结算手册](../../docs/performance/settlement-performance-playbook.md)用 `perf-<runId>-` 限定业务键，核对非零样本、逐笔账本和 Outbox；按[中间件手册](../../docs/performance/middleware-optimization-playbook.md)记录锁、池、WAL 与积压。别将数据库累计指标直接当成本轮独占指标。

先记录未决清单、等待已接受任务收敛，再停止专用栈（不删卷）：

```bash
docker compose -p fincore-perf-20260920a -f infra/performance/compose.isolated.yml stop
```

数据、日志卷与恢复清单保留以便复核；后续只对明确属于本次实验的资源安排清理。本手册不包含销毁卷命令，不对既有公网演示或真实账户作任何操作。
