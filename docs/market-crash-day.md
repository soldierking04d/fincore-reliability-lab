# 市场暴跌日：公开事故驱动的端到端恢复实验

这不是对任何一家交易所的源码复刻，也不使用真实公司的内部流量、容量或灾备参数。它把公开事故中反复出现的故障类型组合成一个可重复实验，回答一个负责人真正关心的问题：

> 当价格快速下跌、订单集中涌入、客户端重试、旧节点恢复写入、结算消息重复、成交同步又发生漏数和错值时，系统最终能否得到唯一成交、唯一资金效果和可证明的 CLEAN 状态？

> **English summary:** A deterministic composite failure drill inspired by public incidents. It proves recovery invariants, not production capacity.

## 三个公开事故给出的警示

| 公开事件 | 已公开的核心问题 | 本实验提炼的故障类型 |
|---|---|---|
| Robinhood 2020 年 3 月故障 | 官方说明称历史性波动、创纪录流量与账户增长造成基础设施压力，并形成 thundering herd，进而触发 DNS 故障 | 热点流量、集中重试、依赖级联 |
| Knight Capital 2012 年事故 | SEC 称错误部署激活了缺陷逻辑，45 分钟内为 212 个客户订单发送超过 400 万个订单，最终损失超过 4.6 亿美元 | 错误版本、失控重试、缺少出站风险护栏 |
| 东京证券交易所 2020 年故障 | JPX 调查报告称 NAS 内存故障后自动接管未发生，人工切换准备也不足，现货交易停摆一整天 | 接管失败、旧节点风险、恢复与复市程序不清 |

公开来源：

- [Robinhood：An Update from Robinhood’s Founders](https://robinhood.com/us/en/newsroom/an-update-from-robinhoods-founders/)
- [SEC：Knight Capital Market Access Rule Enforcement](https://www.sec.gov/newsroom/press-releases/2013-222)
- [JPX：System Failure Investigation Report (PDF)](https://www.jpx.co.jp/english/corporate/news/news-releases/0020/b5b4pj000003xrsa-att/InvestigationReport.pdf)
## 负责人先看：这套代码怎样应对

| 事故压力 | 代码中的控制 | 最终证明 |
|---|---|---|
| 12 路市价卖单同时吃单 | 每个交易对共享数据库事务锁，成交序列持久化 | 60 条成交、60 个唯一序列 |
| 客户端超时后重试 | 用户 + clientOrderId 唯一；相同请求返回原结果 | 重试前后成交仍为 60 条 |
| 重放时偷偷改数量 | 业务键相同但载荷不同立即拒绝 | 冲突重放无新成交 |
| 深度被完全吃光 | 市价单剩余量不能挂单，进入 REJECTED/CANCELED 终态 | 不产生幽灵成交 |
| 旧 Worker 恢复 | Lease + Epoch + 数据事务内 Fencing | 旧 Epoch 拒写，新 Epoch 成功 |
| 结算消息并发投递 17 次 | Inbox、message_id、business_key 与单事务账本 | 只有 1 次资金效果 |
| 成交消息乱序、重复和漏失 | eventId + 载荷指纹 + tradeId 投影 | 重复幂等，顺序不影响事实 |
| 投影错值和幽灵成交 | 权威成交与活动投影做全量外连接 | MISSING、MISMATCH、EXTRA 各 1 |
| 恢复任务重复提交 | 独立 repair key 唯一 | 第一次修复，第二次只返回已有结果 |
| 修复可能污染历史 | 只重建派生投影、隔离 EXTRA | 权威成交快照修复前后完全一致 |
| 是否可以结束事故 | 修复后强制再对账 | 只有 CLEAN 才返回 RECOVERED |

## 全过程

~~~mermaid
flowchart TD
    A[三档买方流动性] --> B[12 路市价卖单]
    B --> C[重试与深度耗尽]
    C --> D[Worker Epoch 接管]
    D --> E[17 次结算重投]
    E --> F[同步复合故障]
    F --> G[全量对账与修复]
    G --> H[再次对账 CLEAN]
~~~
### T-05m：准备订单簿

实验创建 60 张限价买单：

- 100 USDT：20 张；
- 99 USDT：20 张；
- 98 USDT：20 张；
- 每张数量 1。

这不是为了模拟真实市场深度，而是建立一个确定性价格瀑布，便于核对价格时间优先和数量守恒。

### T+00s：市场卖压同时到达

12 个卖方并发提交市价卖单，每单数量为 5，总卖量刚好为 60。系统必须：

- 吃穿 100、99、98 三档；
- 形成 60 条成交；
- 保证交易对内成交序列唯一；
- 保证每张订单“原始量 = 已成交量 + 剩余量”；
- 为每条成交写入同事务 Outbox。

观察到的 trades/s 只记录当前实验机结果，不作为生产 TPS 宣传。

### T+01s：集中重试和冲突重放

实验再次提交第一张市价卖单：

1. 完全相同的请求必须返回原订单和原成交；
2. 使用相同 clientOrderId 但把数量从 5 改成 6，必须拒绝；
3. 数据库中的成交总数必须仍为 60。

这对应“网络超时并不等于业务失败”的基本原则。

### T+02s：流动性耗尽

再提交一张数量为 10 的市价卖单。由于订单簿已经没有买方深度，系统应安全拒绝，不能生成零价成交、留下市价单、产生负数数量或凭空改变余额。
### T+03s：旧 Worker 恢复写入

实验让旧结算 Worker：

1. 领取分片 Lease；
2. 进入 DRAINING；
3. Lease 到期；
4. 新 Worker 领取更大的 Epoch；
5. 旧 Worker 携带旧 Epoch 尝试结算。

Fencing 校验与资金事务共享数据库锁。旧 Epoch 必须在产生 Inbox、账本或余额效果之前整体回滚，新 Worker 才能继续处理。

这里抽象的是“接管后旧执行者不能再写”。它不是对 JPX NAS 硬件切换过程的复刻。

### T+04s：重复结算风暴

同一条结算命令并发投递 17 次。系统同时依赖 Inbox message_id 主键、结算单 business_key 主键、账户固定顺序行锁、借贷平衡流水，以及余额、账本、状态与 Outbox 单事务提交。

验收结果必须是 1 次真实资金效果和 16 次重复返回。

### T+05s：同步链路同时出五种问题

60 条权威成交进入查询投影时，实验故意：

- 倒序发送；
- 重复发送一条相同事件；
- 丢掉一条权威成交；
- 把一条投影数量加 1；
- 注入一条权威表不存在的幽灵成交。

下游不能只比较总数，因为此时权威成交和活动投影都可能是 60 条，总数相等仍然有三类结构性差异。
### T+06s：全量对账与幂等修复

对账按 tradeId 做全量外连接，必须同时得到：

| 差异 | 数量 | 处置 |
|---|---:|---|
| MISSING | 1 | 从权威成交重建 |
| MISMATCH | 1 | 用权威不可变字段覆盖投影 |
| EXTRA | 1 | 标记 QUARANTINED，不删除权威事实 |

修复任务使用唯一 repair key。相同 repair key 第二次提交必须返回 duplicate=true，不能再次修改数据。

### T+07s：再次对账和事故关闭

恢复不以“修复 SQL 执行成功”为结束，而以再次对账为结束：

- 60 条权威成交；
- 60 条 ACTIVE 投影；
- 幽灵成交仍保留审计证据，但状态为 QUARANTINED；
- 修复前后权威成交的数量、序列和成交额快照一致；
- 三个场景资金账户的余额与不可变账本一致；
- 最终对账状态为 CLEAN；
- API 才返回 RECOVERED。

## 一键运行

以 lab Profile 启动后执行：

~~~bash
curl -s -X POST   http://127.0.0.1:8080/lab/scenarios/market-crash-day
~~~

响应包含 designBasis、timeline、metrics、recovery 和 checks，分别用于解释事故抽象、过程、指标、恢复证据和业务不变量。
自动测试：

~~~bash
mvn -Dtest=LabScenarioIntegrationTest#marketCrashDayRecoversAndPreservesAllInvariants test
~~~

完整回归：

~~~bash
mvn test
~~~

## 不能从这个实验推出什么

这个场景证明的是“小规模、可重复、数据库约束下的正确性与恢复闭环”，不能推出：

- 真实交易所可承受多少 TPS；
- P99 延迟能达到多少；
- DNS、BGP、机房断电或跨地域多活一定成功；
- PostgreSQL 单点可以直接用于生产；
- 撮合已经达到微秒级延迟；
- 只靠这些代码就满足监管、灾备与安全要求。

生产化仍需要真实容量模型、入口限流、价格与名义金额风险闸门、全局 kill switch、部署一致性检查、多 AZ/Region 演练、备份恢复证明、权限隔离和事故指挥流程。

## 设计上的关键取舍

1. **不为了可用性牺牲资金正确性。** 深度耗尽或旧节点状态不确定时选择拒绝，而不是猜测性成功。
2. **不把派生数据当权威事实。** 查询投影可以重建，成交和账本不可原地改写。
3. **不把消息队列 Offset 当幂等。** 最终唯一性落在数据库业务键。
4. **不把自动修复等同于自动改账。** 资金差异进入人工审查或唯一补偿单。
5. **不把一次本机压测包装成生产容量。** 报告始终带实验边界。
