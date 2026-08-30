# FinCore Coding Agent 公开评测套件

这套公开套件定义了 8 个接近生产问题的 Coding Agent 修复任务。前五题覆盖资金与状态安全；新增三题覆盖热门交易对并发、成交同步重放和对账修复竞态。每个任务从一个故意制造缺陷的分支开始，并使用同一套 100 分 Rubric。公开检查描述行为契约；隐藏测试位于独立的私有评分仓库，防止 Agent 针对具体实现寻找捷径。

> **English summary:** Eight controlled financial-reliability repair tasks share one rubric, including three advanced traffic and reconciliation scenarios. Public tests define the contract; hidden scenarios remain in a separate private grader.

## 任务分支

| 任务 | 缺陷分支 | 核心风险 |
|---|---|---|
| 重复结算 · Duplicate settlement | `benchmark/duplicate-settlement` | 重复资金影响 |
| 终态非法覆盖 · Illegal status overwrite | `benchmark/illegal-status-overwrite` | 终态被破坏 |
| 手续费热点账户 · Fee hot account | `benchmark/fee-hot-account` | 锁竞争与不安全归集 |
| 缩容接管 · Scale-down takeover | `benchmark/scale-down-takeover` | 过期 Worker 越权写入 |
| 重复补偿 · Duplicate compensation | `benchmark/duplicate-compensation` | 重复冲正 |
| 热门交易对并发撮合 · Hot-symbol matching | `benchmark/hot-symbol-matching` | 重复选单与成交竞争 |
| 成交同步重放 · Trade-sync replay | `benchmark/trade-sync-replay` | 重复、乱序与冲突事件 |
| 对账修复竞态 · Reconciliation repair race | `benchmark/reconciliation-repair-race` | 迟到权威数据被误隔离 |

## 评测协议

1. 把干净工作树重置到指定缺陷分支。
2. 只向 Agent 提供该任务的 `agentPrompt`、仓库说明和公开测试。
3. 记录模型与版本、工具配置、起止时间、Token 或金额成本、提示、补丁、测试输出和修复轮次。
4. 先执行公开测试，再执行独立存储的隐藏评分器。
5. 先应用 `rubric.json` 和一票否决规则，再计算数值分数。
6. 保存完整证据包，不能只根据文字回答评分。

验证公开套件：

```bash
./scripts/eval/validate-eval-kit.sh
```

在已验证的 `main` 提交存在且工作树干净后，可重复生成五个缺陷分支：

```bash
./scripts/eval/create-defect-branches.sh
```

`evals/defects/` 下受版本控制的补丁是缺陷定义的唯一来源。生成脚本为每个分支应用一个补丁，创建明确标记为 `INTENTIONAL BENCHMARK DEFECT` 的提交，最后返回 `main`。

`scorecard.schema.json` 定义可移植的结果格式，用于后续比较 Codex、Claude Code、Gemini、Cursor 和其他 Coding Agent。
