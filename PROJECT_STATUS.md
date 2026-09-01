# 项目状态 · Project Status — v0.5.0

## 已完成 · Completed

- [x] PostgreSQL 账户、余额与只追加平衡账本
- [x] Kafka 结算命令、事务型 Inbox/Outbox 与失败恢复
- [x] 数据库唯一约束、CAS 状态机、审计和独立补偿单
- [x] 手续费分片、Lease、Epoch、DRAINING 与数据面 Fencing
- [x] 限价/市价撮合、价格时间优先、部分成交、撤单和自成交保护
- [x] 订单/成交持久化序列、撮合审计与独立事件 Topic
- [x] 成交同步 Inbox、乱序收敛、冲突重放拒绝
- [x] MISSING/MISMATCH/EXTRA 对账与只修派生投影的修复边界
- [x] 修复与撮合共享交易对锁，覆盖权威成交事务在途提交
- [x] Prometheus、Grafana、故障注入与一键实验
- [x] Java 21 虚拟线程、有界撮合 Lane 与 Kafka/调度平台线程隔离
- [x] Worker Lease 缓存、Outbox 异步批处理与账本批量写入
- [x] G1/ZGC、GC 日志、Heap Dump、JFR 与混合负载基线脚本
- [x] JUnit、PostgreSQL Testcontainers、纯 JDK 模拟与 k6 压测
- [x] 中英双语业务、技术、评测与演示文档
- [x] 8 个公开 Coding Agent 任务、缺陷补丁和分支生成器
- [x] 独立私有隐藏评分器与隔离 Agent 运行器
- [x] FC-001～FC-005：3 Agent × 5 题 × 3 轮，共 45 次重复评测
- [x] FC-006～FC-008：3 Agent × 3 题，共 9 次复杂场景首轮评测
- [x] 机器可读结果、评分报告与公开评测网站
- [x] 技术负责人职责全景、13 类职责证据地图与 12 个管理专题
- [x] 技术战略、人才全周期、交付质量、安全数据、SLO 灾备、FinOps 和客户支持实战手册

## Ubuntu 24.04 ARM64 实验机验证

- 原版本已在实验机完成 Java 17 验证；当前高并发版本已经升级到 Java 21，重新部署时将执行全套回归。
- 原版本 PostgreSQL Testcontainers、Flyway V1～V4 迁移通过；当前版本新增 V5 并由 CI 重新验证。
- Docker Compose 应用、PostgreSQL、Kafka、Prometheus、Grafana 可启动。
- 重复结算、反向补偿、手续费归集、过期 Epoch 拒写和对账差异实验通过。
- 撮合并发、成交重放和投影修复场景通过。
- FC-008 强化参考实现通过 7/7 私有场景，包括两项在途权威事务竞态。
- 实验机继续使用隔离网络；遗留 DHCP 与 split-gateway 服务保持停用。

## 当前评测证据

| 证据集 | 规模 | 结论 |
|---|---:|---|
| FC-001～FC-005 重复性 | 45 次 | Codex 完整通过率最高；Claude 隐藏场景通过最多；Antigravity 最快但编译失败更多 |
| FC-006～FC-008 首轮 | 9 次 | Codex 290/300；Claude 195/300；Antigravity 175/300 |
| FC-008 强化复测 | 参考实现 + 3 候选 | 参考实现与 Codex 7/7；Claude、Antigravity 5/7 并触发安全否决 |

## 公开边界 · Public-release boundary

仓库只包含虚构数据和通用参数，不包含前雇主源码、未公开容量、内部事故、客户/供应商信息、凭证、私钥或机器专用路径。完整 transcript、候选答案补丁、隐藏测试实现与认证材料保留在私有环境。

## 下一阶段 · Next

- 对 FC-006～FC-008 各再执行两轮，形成与前五题一致的三轮重复性证据。
- 把 FC-008 的在途事务竞态加入公开业务说明，但继续隐藏具体评分实现。
- 增加 Kafka listener 级重复、乱序、DLT 与 replay 场景。
- 在不同 CPU/内存配额下重复混合压测，形成 G1/ZGC 与 Lane/连接池参数的容量曲线。
- 按管理手册的分阶段方案补齐认证授权、密钥、SBOM、安全门禁、数据治理和独立安全审查。
- 定义并实测业务 SLO、错误预算、RTO/RPO 和备份恢复；文档中的示例值不作为生产承诺。
- 建立脱敏成本基线、单位业务成本和关键供应商退出验证。
