# 重复运行证据 · Repeated-run evidence

重复阶段已经完成：在原有 15 次首轮运行基础上增加 30 次独立修复，使每个 Agent/任务组合都有 3 次结果，共计 45 次受控运行。所有新增运行在同一台 Ubuntu ARM64 实验虚拟机上顺序执行，尽量降低 CPU、Docker 和数据库竞争对耗时的影响。

> **English summary:** The completed release contains 45 attempts. Codex led clean-run rate, Claude led planned hidden-case passes, and Antigravity was fastest but less reliable end to end.

## 结果

45 次运行中，33 次端到端完整通过；4 次只在隐藏场景失败；3 次没有通过完整公开测试；2 次生成的补丁无法编译。公开测试失败总数为 3，是因为除新增运行的 2 次外，还包括首轮 Antigravity FC-005。

重复运行改变了首轮结果的解释：Codex 的完整通过率最高，Claude 通过的计划隐藏场景最多，Antigravity 最快但端到端稳定性较低。FC-003 和 FC-004 的九次尝试全部通过；FC-001 只有两次完整通过。

100 分主观 Rubric 仍只覆盖首轮 15 次。30 份重复补丁已有确定性测试和耗时结果，但尚未逐份完成完整主观评分，因此重复排名使用完整通过、隐藏场景和耗时证据，不虚构数值分数。

## 公开与私有证据

公开索引记录源码提交、Agent/模型身份、耗时、可用使用量、测试结果和 SHA-256 摘要。候选补丁、原始对话和隐藏测试保持私有，避免后续 Agent 取得答案。

- [三轮重复评测报告](../repeatability-results.md)
- [机器可读聚合结果](../repeatability-results.json)
- [30 次新增运行脱敏索引](../repeat-progress.json)
- [运行索引 Schema](../run-index.schema.json)
- [完整重复评测协议](../repeatability-protocol.md)
