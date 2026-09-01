# 参与 FinCore Reliability Lab

[English](#english) | 简体中文

感谢你愿意改进这个项目。FinCore 接受的不只是 Java 代码，也欢迎业务流程、风险规则、可观测性、管理治理、AI 评测和数字资产可靠性方面的贡献。

## 可以贡献什么

| 方向 | 合适的贡献示例 |
|---|---|
| 业务与产品 | 补齐客户旅程、拒绝原因、运营指标、异常处置或演示场景 |
| 金融与风控 | 新增可执行不变量、限额规则、对账分类或安全补偿实验 |
| 工程与性能 | 并发保护、背压、数据库/Kafka 优化、容量测试或可观测性 |
| 团队与治理 | SLO、灾备、FinOps、安全、交付、技术雷达或跨部门决策模板 |
| AI 落地 | 可复现的用例基线、成本/质量门禁、红队测试或评测任务 |
| 数字资产 | 测试网充值/提现、链重组、Nonce/UTXO、签名或链上链下对账设计 |

## 提交前先确认

1. 不提交真实客户、账户、密钥、公司内部参数或前雇主代码。
2. 不弱化金融不变量：金额必须精确；借贷必须平衡；历史账本只追加；成功状态不可被迟到失败覆盖。
3. 新功能同时说明业务目标、失败边界、监控指标和验收方法。
4. 设计中的能力不得表述为已经生产落地；请明确标记“已实现”“已验证”或“规划中”。
5. 代码注释优先使用清晰的中文，公共类型、事务边界和不直观的并发决策需要说明原因。

## 建议流程

1. 先使用 Issue 模板描述问题或场景；小型错别字和文档链接修复可直接提交。
2. 从 `main` 创建短分支，保持一次提交只解决一个清晰问题。
3. 运行与改动相称的验证：

```bash
./scripts/verify-core.sh
./scripts/eval/validate-eval-kit.sh
mvn test
```

4. Pull Request 中填写业务影响、风险边界、验证证据、可观测性和回滚方法。
5. 涉及账本、幂等、状态机、Fencing 或自动修复的改动，必须提供失败场景测试。

详细代码规则见[Java 代码与中文注释规范](docs/java-coding-conventions.md)，核心金融边界见[资金安全 ADR](docs/adr/0001-financial-invariants.md)。

## English

Contributions are welcome across business and product flows, financial safety, engineering and performance, organizational governance, practical AI adoption, and digital-asset reliability—not only Java code.

Before opening a pull request:

1. Never include real customer data, credentials, proprietary parameters, or former-employer code.
2. Preserve the financial invariants: exact money, balanced journals, append-only history, and immutable successful outcomes.
3. Describe the business goal, failure boundary, operational signals, and acceptance evidence.
4. Clearly separate implemented, verified, and planned capabilities.
5. Run the relevant core, evaluation-kit, and Maven checks, and add failure-path tests for financial or concurrency changes.

By contributing, you agree that your contribution is licensed under the repository's Apache License 2.0.
