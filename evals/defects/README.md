# 故意缺陷补丁 · Intentional defect patches

这些补丁专门用于创建包含已知金融可靠性缺陷的评测分支。**严禁**把它们应用到主分支、生产系统或含有真实资金数据的仓库。

`create-defect-branches.sh` 会在已验证的主提交之后，把每个补丁分别应用到独立评测分支。每个缺陷提交都明确标记为 `INTENTIONAL BENCHMARK DEFECT`。

补丁公开是为了让仓库可审计。正式受控评测只向 Agent 提供选定分支和任务提示；隐藏测试实现始终保存在独立私有评分器中。

> **English note:** These patches intentionally introduce financial-reliability defects for benchmark branches only. Never use them in production or with real financial data.
