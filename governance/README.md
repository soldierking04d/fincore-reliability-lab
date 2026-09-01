# FinCore 治理运行台账

本目录把管理手册中的责任、风险、指标、技术选择和审计证据转换为可以持续校验的数据，而不是
再增加一组只靠人工阅读的说明文件。

## 五份台账

| 台账 | 回答的问题 | 主要责任人 |
|---|---|---|
| [`services.yaml`](services.yaml) | 服务由谁负责、依赖什么、承诺什么 | 服务 Owner |
| [`risks.yaml`](risks.yaml) | 哪些风险仍然存在、怎样处置、何时复查 | 风险 Owner |
| [`metrics.yaml`](metrics.yaml) | 指标如何定义、谁负责、能否用于决策 | 指标 Owner / Steward |
| [`technology-radar.yaml`](technology-radar.yaml) | 技术是采用、试验、评估还是暂停 | 技术 Owner |
| [`audit-evidence.yaml`](audit-evidence.yaml) | 控制怎样验证、证据在哪里、有哪些限制 | 控制 Owner |

文件扩展名使用 YAML，内容采用 JSON 兼容的 YAML 1.2 子集。这样既可以被 YAML 工具读取，也能
直接使用仓库和 CI 已经具备的 `jq` 校验，不额外引入解析器供应链。结构约束保存在
[`schemas/`](schemas/)；跨台账引用、日期、状态和证据文件由
[`verify-governance.sh`](../scripts/verify-governance.sh) 与 Maven 测试共同校验。

## 维护规则

1. 服务、风险、指标、技术和控制使用稳定 ID，不以显示名称作为关联键；
2. 每项必须有负责人、上次复查和下次复查时间；
3. `demonstrated` 只表示仓库内证据可以复核，不代表生产认证；
4. 网站回放数据必须标记 `demoOnly: true`，不能冒充线上采样；
5. `adopt` 技术和 `demonstrated` 控制必须指向真实存在的证据文件；
6. 接受风险必须填写批准人和到期日，不能用“已知风险”无限延期；
7. 修改任一台账后运行 `./scripts/verify-governance.sh`。

## 数据关系

```text
服务目录 ──关联──> 风险登记 ──约束──> 指标口径
   │                  │                    │
   └────依赖────> 技术雷达 <────证明──── 审计证据
```

这套关系让技术负责人可以从一项业务服务继续追到剩余风险、监控口径、技术决策和验证证据，也能
在审计或复盘时明确区分“已有证明”“计划实施”和“公开演示限制”。
