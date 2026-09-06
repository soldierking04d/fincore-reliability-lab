# FinCore AI 工程化登记

## 先看程序员怎么用：AI 编程助手接入公司

当前主线是[仓库接入包](developer-adoption/README.md)：真实源文件地图、Claude Code/Codex 共用流程、三份开发任务单、独立候选验证和人工评审模板。
进一步实施见[Claude Code / Codex 共用方案](../docs/management/detailed/19-agent-based-development-implementation.md)：公司维护统一上下文、任务合同、独立候选验证和人工收件；两种 Agent 只适配执行入口，原 Cursor 规则为可选项。
围绕读懂仓库、按规范做小需求、失败测试驱动修复，详细见[落地手册](../docs/management/detailed/18-ai-developer-assistant-playbook.md)。
本次没有调用 Cursor 或真实 LLM；代码执行结果不等于模型表现，也不代表公司已经部署。

## 补充：内部需求与其他日常工作

保留[内部需求与测试](../docs/management/detailed/17-ai-requirements-testing-playbook.md)：
原始需求 → 产品澄清 → REQ/AC → 关联测试与数据 → 参考模型执行 → 评审与变更影响。
[源码](requirements-test-workflow.ts)与[独立测试](requirements-test-workflow.test.ts)使用合成资料，不接真实 LLM 或邮件系统。
执行 `node --test ai/requirements-test-workflow.test.ts` 可复算；未知规则保持阻塞，真实集成检查仍为未执行。

- [客服回复、内部知识问答、需求整理完整说明](../docs/management/detailed/16-ai-company-delivery-casebook.md)；
- [确定性演示源码](company-workflow.ts)与[独立测试](company-workflow.test.ts)；
- Node.js 24 执行 `node --test ai/company-workflow.test.ts`，无需模型密钥和网络；
- 页面以收益、返工、知识维护和两周小试点为主。所有样本合成，未接真实模型，不修改下方生产用例状态。

## 原有工程治理入口

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
