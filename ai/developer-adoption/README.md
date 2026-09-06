# 公司内 AI 辅助研发：仓库接入包

优先做三件事：读懂仓库、按规范完成小需求、用失败测试驱动修复。借鉴 Cursor 解决研发问题的方式，以 Claude Code 或 Codex 为执行工具；公司只维护一套任务、上下文与验收，不先自建 IDE。

先读[工具无关实施方案](../../docs/management/detailed/19-agent-based-development-implementation.md)：公司维护什么、开发者怎么用、同一任务怎样换工具、什么时候才需要共享执行脚本或 Worker 平台。

## 本仓库已经提供什么

| 可实施点 | 现在可以使用的产物 | 公司还需要做什么 |
| --- | --- | --- |
| 仓库理解 | [可检查的源文件地图](context-map.json)、共享规则、只读任务单 | 模块负责人维护地图；确认索引和代码出域策略 |
| 按规范开发 | [三份任务单](task-pack.md)、Java 前后版本、独立验收用例 | 在授权开发环境中让工具生成自己的候选补丁，接原有 PR |
| 测试驱动修复 | [可重复的 Java 练习](workshop/README.md)、同组用例的红绿报告 | 对真实任务另补数据库/HTTP/集成回归，不把练习通过当上线许可 |
| 人工评审与反馈 | [可填写评审记录](review-template.md)、四周试点方案 | 记录全流程用时和缺陷；明确维护人，不按代码行数评价人 |

详细做法：[公司 AI 编程助手落地手册](../../docs/management/detailed/18-ai-developer-assistant-playbook.md)。

本次没有调用真实模型生成候选，未绑定企业账号，没有模型提效实测。Java 练习是实际执行的合成样例，不是本系统线上缺陷、真实工单 API 或生产压力测试。原有 54 次 Coding Agent 评测保持独立口径。

## 开始使用

1. 公司先批准工具、模型供应商、可输入仓库和数据范围；不允许代码出域的仓库应选满足边界的方案并单独验证，不把隐私选项当离线保证。
2. 开发者打开获准仓库：Codex 通过 `AGENTS.md`，Claude Code 通过 `CLAUDE.md` 引用同一份 `common-workflow.md`。实际客户端需检查加载与权限；规则不是访问控制。原 `.cursor/rules/` 只是可选入口。
3. 选择 `task-pack.md` 的一个任务，先只读定位，再在隔离分支写候选代码。公司接口文档不齐时先补最小地图；目前不需要接 Jira、MCP 或向量数据库。
4. Node 24 与可运行的 JDK 21 环境下，在仓库根目录执行：

```bash
node --test ai/developer-adoption/verify-kit.test.mjs
node ai/developer-adoption/workshop/run-workshop.mjs
```

脚本只编译和执行本地 Java，报告及 class 写入随机临时目录，终端打印报告位置。可通过 `--jdk` 指定 JDK，或设置专用变量 `WORKSHOP_JDK`。不自动下载依赖，不需要密钥。报告保存环境版本、每条输入/期望/实际值与源码 SHA-256；源码摘要用于匹配版本，不等于数字签名。

网站下载包只包含接入材料与独立练习，不含完整 FinCore 业务源码。下载包中可直接运行 Java 练习；地图的 `verify-kit.test.mjs` 需在完整仓库中运行，否则会因源文件缺失而正确报错。额外负向检查 `node --test ai/developer-adoption/verify-runner.test.mjs` 验证缺 JDK 与“旧版缺陷已经消失”时不会被当成成功复现。

5. 查看实际差异与报告，按评审模板交付。普通业务源码变更仍需运行原有规范检查和完整 CI；这个练习的通过不替代它们。

## 给任一 Agent 准备同一任务

```bash
node ai/developer-adoption/prepare-task.mjs understand
node ai/developer-adoption/prepare-task.mjs implement --json
node ai/developer-adoption/prepare-task.mjs repair
```

只输出选定任务的合同、来源内容与 SHA-256，不写文件、不调用模型、不运行候选。将材料交给任一获准 Agent，人工确认改动范围后在独立副本实施。公司内部真实仓库应先审查白名单内容；文件摘要不等于脱敏检查。

下载包包含独立练习，可直接准备 implement/repair；understand 依赖完整 FinCore 仓库的真实源文件，缺失时明确报错，不伪造地图。

## 验收 Claude Code / Codex 实际提交的候选

先在获准的隔离环境审阅候选，再显式批准执行。以下路径是开发者提供的单个候选文件，不是本仓库预置参考答案：

```bash
node ai/developer-adoption/verify-candidate.mjs implement /path/to/candidate/TicketQuery.java --approve-local-execution
node ai/developer-adoption/verify-candidate.mjs repair /path/to/candidate/TicketQuery.java --approve-local-execution
```

`implement` 必须满足完整合同 12/12；`repair` 必须让 P03 修复、其余测试行为与受信旧版一致。后者任务验收为 12/12，完整需求覆盖仍只有 5/12，报告明确分列。不采纳候选目录里的测试/探针，不把固定参考答案的结果当候选成绩。

验证器自身不调用模型，但会实际执行 Java；它不是 OS 沙箱，不能阻止恶意代码联网或读文件。公司必须另设隔离、权限和资源限制；日志与候选输出也要审查后才能对外提供。固定 12 条样例只证明其覆盖行为，不能证明全部源码范围未改、没有恶意行为或生产可用。

## 共享规范与权限

- 平台负责人维护通用规则；模块负责人维护路径和实现参考；测试负责人维护输入合同和回归集。
- 一次只把反复发生、可以明确说明的错误变成规则。不要把全部文档复制到每次对话。
- `.cursorignore` 等文件只属于特定工具，不会自动成为 Claude Code/Codex 的权限规则。生产密钥应根本不出现在开发隔离环境中，实际权限分别配置。
- 工具终端授权、网络权限和 CI 合并门禁由真实系统设置；这个仓库包没有替公司打开或改变这些权限。

## 验证与发布边界

`verify-kit.test.mjs` 检查地图引用存在、关键符号未漂移及包文件齐全，不做语义完整性证明。Java runner 验证补丁前后的合同行为，编译失败、缺 JDK、空用例不算预期红。

本次内容先更新本地网站 `#ai`。公网地址与首页入口不变，但未经推送部署，公网不会自动包含本地新增材料。
