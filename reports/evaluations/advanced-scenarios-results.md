# 高流量与异常同步首轮评测

本轮把 FC-006～FC-008 三个复杂场景交给 OpenAI Codex CLI、Anthropic Claude Code 和 Google Antigravity CLI，各执行一次隔离首轮修复，共 9 次。结果不是简单看“测试绿不绿”：公开测试、私有场景、100 分 Rubric 和任务级安全否决同时生效。

> **English summary:** Three coding agents completed nine isolated first-pass attempts on hot-symbol matching, trade-sync replay, and reconciliation-repair races. Codex was the only agent to pass the strengthened in-flight authoritative-transaction checks. Claude and Antigravity triggered the FC-008 safety veto despite passing the original five hidden scenarios.

## 负责人先看结论

- **Codex：3/3 达到 Rubric 接受线，最终 290/300。** FC-008 通过最终 7/7 隐藏场景，但使用表级 SHARE 锁造成跨交易对过度串行化，容量维度扣 5 分。
- **Claude：2/3 被接受，最终 195/300。** FC-008 原始公开测试和 5 个隐藏场景全部通过，但强化竞态证明它会在权威成交事务仍未提交时提前隔离投影，触发安全否决。
- **Antigravity：1/3 被接受，最终 175/300。** 速度最快，但 FC-006 测试代码无法编译，FC-008 还同时出现公开契约失败和安全否决。
- **FC-007 是三者共同强项。** 三套 Agent 都正确识别相同事件号的载荷冲突，公开与隐藏场景全部通过。

## 总览

| Agent / 模型 | 完整绿灯 | Rubric 接受 | 最终分数 | 公开测试 | 隐藏场景 | Agent 耗时 | 否决 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Codex · gpt-5.6-sol | 2/3 | 3/3 | 290/300 | 91/92 | 17/17 | 1,132 秒 | 0 |
| Claude Code · sonnet | 1/3 | 2/3 | 195/300 | 94/97 | 15/17 | 1,945 秒 | 1 |
| Antigravity · Gemini 3.7 Flash (High) | 1/3 | 1/3 | 175/300 | 63/65 | 10/17 | 486 秒 | 1 |

“完整绿灯”要求完整公开套件和所有计划隐藏场景都通过；“Rubric 接受”要求最终分数不低于 80 且无否决。编译失败的计划隐藏场景按 0 分计入，不从分母删除。

## 分任务结果

| 任务 | Codex | Claude | Antigravity |
|---|---:|---:|---:|
| FC-006 热门交易对并发撮合 | 95 | 95 | 75 |
| FC-007 成交同步重放 | 100 | 100 | 100 |
| FC-008 对账修复竞态 | 95 | 0（否决；诊断分 75） | 0（否决；诊断分 65） |

## FC-006：核心修复相近，交付质量不同

三者都识别出全局数据库 advisory lock 是跨交易对吞吐问题，并把核心锁边界收敛到交易对。差异出现在 Agent 自己补充的测试：

- Codex 的业务修复通过 5/5 隐藏场景，但一个回滚测试直接用对象相等比较不同 scale 的 BigDecimal，公开结果为 30/31。
- Claude 同样通过 5/5 隐藏场景，但三个并发压力测试分别超过 20 秒超时，公开结果为 30/33。
- Antigravity 的生产代码方向正确，但新增测试漏掉 UUID import，导致整个测试编译阶段失败，公开与隐藏用例都无法执行。

因此前两者只在测试质量维度各扣 5 分；Antigravity 因不可构建交付被评为 75 分，未达到接受线。

## FC-007：三者全部正确

相同 event_id 再次到达时，不能直接当作无害重复；必须比较持久化的 trade_id 和不可变载荷指纹。三者都补上了数据库记录与当前命令的冲突校验，并保持 Inbox 与投影写入的单事务边界。

| Agent | 公开测试 | 隐藏场景 | 分数 |
|---|---:|---:|---:|
| Codex | 32/32 | 5/5 | 100 |
| Claude | 32/32 | 5/5 | 100 |
| Antigravity | 34/34 | 5/5 | 100 |

## FC-008：测试全绿仍可能不能上线

原始隐藏评分器覆盖“迟到成交已经提交，再开始修复”，三套 Agent 都通过了 5/5。审查 Claude 的实现时发现，它先查询权威成交是否存在，再在下一条 SQL 中隔离投影；两条语句之间没有和权威写入共享同一交易对锁。

评分器随后增加两项确定性竞态：

1. 权威写事务获取交易对 advisory lock，写入成交但暂不提交；
2. 并发启动投影修复；
3. 修复必须等待写事务提交，再重新读取权威成交并重建投影；
4. 最终权威成交存在、投影为 ACTIVE、修复结果为 rebuilt=1、quarantined=0。

强化后的参考实现通过 7/7。Codex 也通过 7/7；Claude 和 Antigravity 都只通过 5/7，并会在权威事务提交前返回隔离结果。该行为命中任务级否决：**不得把当前由权威成交背书的投影留在隔离状态。**

Codex 虽然正确，但额外执行了 trade_execution 表级 SHARE 锁。它能挡住未协作写入，却会让一个交易对的修复阻塞其他交易对的权威成交写入，因此性能与容量维度只得 5/10，最终 95 分。

## 为什么同时保留诊断分与最终分

一票否决先于数值分数。Claude 和 Antigravity 的 FC-008 仍给出 75、65 的“诊断分”，用于说明它们在快照、幂等、审计等其他维度完成了多少；但最终分必须为 0，且不能标记为可上线。这样既保留分析信息，也不会用平均分掩盖权威数据安全风险。

## 隔离与证据边界

- 每次运行只包含一个缺陷分支快照，重新建立单提交候选仓库并删除 Git remote；
- Agent 容器不挂载私有评分器、宿主 Docker socket或其他运行结果；
- Agent 退出后补丁立即冻结，再由宿主机执行公开和隐藏测试；
- 三套 Agent 使用同一缺陷提交、任务提示和 Rubric；
- FC-008 最终结论统一使用强化后的 7 场景评分器重新复测冻结补丁；
- 完整 transcript、候选补丁和隐藏测试实现保持私有，公开仓库只提供脱敏计数、评分理由和机器可读结果。

## 成本与解释限制

Claude CLI 报告本轮三题合计成本约 3.9457 美元。Codex 的 ChatGPT 认证流和 Antigravity 的 Google AI Pro 认证流没有提供可比较的逐次美元金额。不同厂商的缓存与 Token 计数口径不同，因此不用于横向排名。

这仍然是 Java、Spring Boot、PostgreSQL 与金融可靠性窄领域的每题一次首轮样本，不是通用模型排行榜。后续应对 FC-006～FC-008 各重复三轮，才能判断结果是否稳定。

机器可读原始汇总见 [advanced-scenarios-results.json](advanced-scenarios-results.json)。

