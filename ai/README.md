# FinCore AI 工程化登记

本目录不是模型宣传页，而是 AI 能力进入 FinCore 前必须通过的治理入口。

- [`use-cases.json`](use-cases.json)：机器可读的用例、模型版本、价值基线、风险、数据、权限、发布阈值、人工批准、关闭开关和降级方案；
- [`use-cases.schema.json`](use-cases.schema.json)：登记结构及允许值；
- [`verify-ai-governance.sh`](../scripts/verify-ai-governance.sh)：本地与完整检查使用的硬边界校验；
- [`AiGovernanceRegistryTest`](../src/test/java/dev/fincore/AiGovernanceRegistryTest.java)：随 Maven 和 CI 执行的同等硬边界回归测试；
- [AI 工程化落地概要](../docs/management/13-ai-engineering-adoption.md)；
- [AI 工程化落地实战](../docs/management/detailed/14-ai-engineering-adoption-playbook.md)。

## 当前结论

已落地的是 Coding Agent 受控评测：候选补丁在隔离仓库生成，使用公开测试、隐藏场景、机器评分卡
和人工结论，不自动合并。事故诊断、对账解释、支持摘要和容量分析仍处于规划状态。

任何 AI 用例都不能直接修改账本、余额、权威订单或成交，不能批准资金补偿，不能绕过发布与安全
门禁。AI 可以检索、总结、解释、提出候选方案和生成隔离补丁，最终动作由确定性业务服务和授权
人员完成。

## 本地校验

```bash
./scripts/verify-ai-governance.sh
```

校验会拒绝重复用例 ID、无证据却标记已落地、高风险用例缺少人工批准、允许自主资金写入、模型
与提示不可追溯、发布门禁过期、缺少关闭责任、缺少禁用动作或没有降级方案等情况。
