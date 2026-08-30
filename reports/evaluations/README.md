# Coding Agent 受控评测

FinCore 使用相同缺陷提交、任务说明、公开测试、隐藏评分器和评分规则完成了两批受控评测：FC-001～FC-005 的 45 次三轮重复修复，以及 FC-006～FC-008 的 9 次复杂场景首轮修复，全程不进行人工修补候选补丁。

> **English summary:** This release covers 45 controlled Java/PostgreSQL repeat attempts plus nine first-pass advanced-scenario attempts. Codex achieved the strongest advanced safety result; Claude and Antigravity exposed an in-flight reconciliation race despite passing the original hidden checks.

## 新增：三个复杂场景首轮实测

FC-006～FC-008 已完成 3 个 Agent × 每题 1 次的 9 次隔离首轮评测，覆盖热门交易对全局锁、成交同步冲突重放，以及权威成交在途提交时的对账修复竞态。

| Agent | 完整绿灯 | Rubric 接受 | 分数 | 最终隐藏场景 | 安全否决 |
|---|---:|---:|---:|---:|---:|
| Codex · gpt-5.6-sol | 2/3 | 3/3 | 290/300 | 17/17 | 0 |
| Claude Code · sonnet | 1/3 | 2/3 | 195/300 | 15/17 | 1 |
| Antigravity · Gemini 3.7 Flash (High) | 1/3 | 1/3 | 175/300 | 10/17 | 1 |

最关键的发现不是分数，而是原始 FC-008 隐藏测试的盲区：Claude 和 Antigravity 原先均为 5/5，但在增加“权威成交事务未提交”场景后降为 5/7，并触发错误隔离安全否决；Codex 和强化后的参考实现均为 7/7。

- [复杂场景完整报告](advanced-scenarios-results.md)
- [复杂场景机器可读结果](advanced-scenarios-results.json)

这 9 次是每题一次的首轮样本，尚未并入下方 45 次三轮重复性统计。

## 负责人先看结论

这不是“谁的代码生成能力最强”的通用榜单。它反映的是资金系统里三个不同维度：

- **Codex：端到端交付更稳。** 15 次中有 12 次同时通过完整公开测试和全部隐藏场景。
- **Claude：隐藏边界覆盖更深。** 计划的 75 个隐藏场景中通过 70 个，但有两份补丁没有通过自己扩充后的公开测试。
- **Antigravity：响应速度最快。** 中位耗时 188 秒，但出现两份无法编译的补丁，完整交付率最低。

## 三轮重复评测汇总

| Agent / 模型 | 完整通过 | 计划隐藏场景 | 中位耗时 | 编译失败 |
|---|---:|---:|---:|---:|
| OpenAI Codex CLI · gpt-5.6-sol | 12/15（80.0%） | 66/75（88.0%） | 369 秒 | 0 |
| Anthropic Claude Code · sonnet | 11/15（73.3%） | 70/75（93.3%） | 447 秒 | 0 |
| Google Antigravity CLI · Gemini 3.7 Flash (High) | 10/15（66.7%） | 61/75（81.3%） | 188 秒 | 2 |

“完整通过”要求 Agent 正常退出、完整公开测试通过，并通过全部 5 个隐藏场景。编译失败按模型交付失败处理，在计划隐藏场景中计为 0/5，不从分母删除。不同 Agent 可以自行增加公开测试，因此公开测试数量不用于横向排名。

## 首轮分数如何使用

首轮主观 Rubric 分数为 Codex 495/500、Claude 490/500、Antigravity 485/500，继续作为历史证据保留。30 份重复运行补丁已有确定性的测试和耗时结果，但尚未逐份完成完整的主观 100 分复核，因此重复评测不虚构精确分数，而以完整通过、隐藏场景和耗时为主要证据。

## 报告与数据

- [中英双语评测网站](https://fincore-agent-benchmark.soldierking04d.chatgpt.site)
- [三轮重复评测报告](repeatability-results.md)
- [机器可读汇总](repeatability-results.json)
- [30 次新增运行脱敏索引](repeat-progress.json)
- [重复评测协议](repeatability-protocol.md)
- [重复运行证据说明](repeated-runs/README.md)
- [脱敏索引 Schema](run-index.schema.json)
- [首轮三 Agent 对比](coding-agent-comparison.md)
- [业务与技术评测介绍](../../docs/agent-evaluations/benchmark-introduction.md)
- [Codex 首轮汇总](summary.json)
- [Claude 首轮汇总](claude-summary.json)
- [Antigravity 首轮汇总](antigravity-summary.json)

## 解释边界

这是 Java、Spring Boot、PostgreSQL 和金融可靠性领域的窄范围案例，不是通用 Coding Agent 排名。每组 3 次运行能够暴露波动，但不足以建立总体置信区间，也不能外推到所有语言和工程任务。

原始对话、隐藏测试实现、答案补丁和认证材料继续保留在私有环境。公开证据仅包含评分卡、运行元数据、可用的 Token/成本摘要、脱敏场景结果、哈希和方法说明。
