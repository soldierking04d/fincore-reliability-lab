# 交易、支付与资金一致性：内容覆盖增量

本清单以现有源码和展示页面为基线，区分**新增、深化、复用**。标记表示内容覆盖，不表示生产功能已经实现。
公开展示入口保持 [FinCore 首页](https://124.223.164.254/)，专项入口为 [交易、支付与一致性边界](https://124.223.164.254/#transaction-boundaries)。

## 核心缺项与本次补充

| 原有缺口 | 补充位置 | 交付与边界 |
| --- | --- | --- |
| 有资金闭环，但没有 100 买单吃 40/40/50 的逐笔数字链路 | 页面 `#order-case`；`experiments/funds-boundaries/model.mjs` | 五条可运行教学路径；金额使用 BigInt；不是生产撮合引擎 |
| 没有双侧独立累计、逐笔角色及费用联动 | 同上，展开双方明细 | 3 笔 Trade 对应 6 条参与方视图；现有 Java 表仍是 3 行，不改生产表 |
| 未把“撤单已生效、已成交待结算、资金可用”同屏区分 | 撤单先于交割、再成交后撤单路径 | 撤 30 留 20；再成交 10 后只撤 20，保留待交割 30 |
| 缺跨数据库分支半成功的完整恢复说明 | 页面 `#workflow-case` 与手册第 2 节 | 待可用资产、不可逆决议、幂等恢复与反向补偿的内存模型 |
| 缺真正 2PC 与两次本地事务的区别 | 页面协议专题与手册第 3 节 | prepare / decision / recovery；没有部署 XA 管理器 |
| 主备说明未解释日志位置与云盘/Pod/PVC 关系 | 页面恢复专题与手册第 4 节 | 四水位、复制与共享存储对照，明确现网单机边界 |
| 缺完整支付执行、回调及逆向业务 | 页面两个支付专题与手册第 5 节 | Intent/Order/Attempt、UNKNOWN、四层幂等、退款预占；真实通道待接入 |
| 缺稳定币兑换、库存、预融资与对冲链路 | 页面稳定币专题与手册第 6 节 | 产品/风险协议、签名策略、资产身份及敞口，不接真实资金 |
| 缺对账切面、事务代理、控制面与交接的集中说明 | 页面末两专题及手册第 7—9 节 | 与现有 Java / 性能 / 治理文档互链，不重复建设 |

## 逐项覆盖矩阵

“新手册”指 [交易、支付与资金一致性实施手册](transaction-boundaries-playbook.md)。
“复用”项没有为了增加篇幅而再建第二份实现。

### 支付架构与外部执行

| 范围 | 处理 | 位置与验收重点 |
| --- | --- | --- |
| Intent、Order、Attempt、Ledger、FX、Hedge 责任 | 新增 | 新手册 §5/6；分离履约事实、渠道身份与风险头寸 |
| 银行超时、查询、重试与通道切换 | 新增 | §5；UNKNOWN 不开放第二次履约 |
| API、业务动作、账务事件与渠道四层幂等 | 新增 | §5；相同键不同参数拒绝，合法退款独立身份 |
| 回调验签、防重放、乱序与 ACK | 新增 | §5；持久收件后 ACK，状态按权威事实推进 |
| 收款/付款/退款/拒付状态与费用 | 新增 | §5；逆向动作独立流水，累计额度并发约束 |
| 渠道能力矩阵和路由资格 | 新增 | §5；查询、幂等、营业时间、预融资优先于成功率排序 |
| 同步受理、异步执行与商户状态合同 | 新增 | §5；202 / PROCESSING / UNKNOWN 不代表成功 |
| 双系统迁移、影子、切换及回退 | 复用＋适配 | [迁移专项](java-cpp-trading-migration.md)；§5 适配外部 Attempt 身份，影子无出款权 |

### 账务与并发

| 范围 | 处理 | 位置与验收重点 |
| --- | --- | --- |
| Ledger、Balance、业务状态的权威边界 | 复用 | [资金闭环](spot-funds-closure.md)；新手册 §1 串联数值 |
| 本地事务、跨账户与跨数据库分片 | 新增 | §2；固定必要分支、待可用资产、持久决议与恢复任务 |
| 分片单写、缩容半单和旧 Worker | 复用＋深化 | [围栏 ADR](adr/0003-shard-fencing.md)；§2/4 解释所有权与数据库分库区别 |
| 手续费热点、机构额度与预扣 | 复用 | [热点账户](performance/hot-account-optimization-playbook.md)；明确不能任意拆可花余额 |
| 数据库与消息、Outbox、Kafka EOS 边界 | 复用＋深化 | [事件 ADR](adr/0002-inbox-outbox.md)；§3 不把可靠通知当全局原子性 |
| 幂等、冲正、多次退款与业务动作身份 | 深化 | §5；退款进行中也占可退额度 |
| ID、事件时间与事务提交顺序 | 新增 | §7；ID101/102 倒序提交反例 |
| 独立对账、数据完整性、水位与尾差 | 深化 | §7；差异按在途、规则、真实缺失分类 |
| 批处理、热点与事务边界 | 复用 | [结算性能](performance/settlement-performance-playbook.md)与[真实验收](performance/settlement-completion.md) |

### 稳定性、基础设施与交付

| 范围 | 处理 | 位置与验收重点 |
| --- | --- | --- |
| 资金正确性、受理可用性与结果确定性 SLO | 复用＋深化 | [SLO 手册](management/detailed/11-slo-disaster-recovery-playbook.md)；§5 加 UNKNOWN 年龄 |
| 资金事故指挥、止付范围和恢复条件 | 深化 | §7；限制按风险作用域，恢复依赖证据与审批 |
| Java 变慢、分段耗时与容量 | 复用 | [高并发 JVM](high-concurrency-jvm-tuning.md)、[中间件优化](performance/middleware-optimization-playbook.md) |
| 超时、重试预算与安全降级 | 深化 | §5/8；结果未知不放资金，重试不放大风险 |
| 多可用区、多地域、RPO/RTO 与单写 | 深化 | §4；数据可恢复、旧主隔离、实际演练条件 |
| 主从读写、连接池与事务陷阱 | 深化 | §8；关键裁决不依赖滞后副本 |
| Kafka 积压与重平衡 | 复用 | [中间件优化](performance/middleware-optimization-playbook.md)、[恢复证据](recovery-capacity-evidence.md) |
| Nacos 控制面与堆扩容故障定位 | 新增 | §8；按版本功能核验，不把服务发现当资金写权 |
| 故障注入、安全发布及数据修复 | 复用 | [安全发布](safe-release.md)、[代码质量](quality-security-hardening-2026-09-05.md) |

### 稳定币、托管与兑换

| 范围 | 处理 | 位置与验收重点 |
| --- | --- | --- |
| 稳定币收款→兑换→法币付款 | 新增 | §6；链、内部账、兑换和银行流水独立核验 |
| 网络/合约身份、最终性与重组 | 复用＋适配 | [链上可靠性](blockchain-digital-asset-reliability.md)；§6 适配支付场景 |
| 广播未知、替换加速与重复付款保护 | 复用＋适配 | 同上；用业务意图绑定多份链上尝试 |
| HSM、MPC、多签与签名审批 | 深化 | §6；算法不替代意图、地址、额度和恢复治理 |
| 锁价、库存、预融资与对冲订单 | 新增 | §6；Quote 到期、资金准备与 Hedge 结果分离 |
| 部分对冲、净额、脱锚与商户承诺 | 新增 | §6；1000 收款、600 套保、400 敞口示例 |
| 多币种、gas、费用、舍入及多方对账 | 深化 | §6/7；原币守恒，汇率与尾差单独核算 |
| KYC、KYB、筛查与 Travel Rule 状态门 | 深化 | §6；适用规则需具体确认，不宣称统一合规认证 |

### 技术治理与研发

| 范围 | 处理 | 位置与验收重点 |
| --- | --- | --- |
| 团队前 90 天的系统盘点与交付目标 | 复用 | [管理总览](management/README.md)与[端到端案例](management/detailed/06-end-to-end-case-study.md) |
| 架构评审、平台化收益与停止条件 | 复用 | [架构治理](management/detailed/07-technical-strategy-architecture-playbook.md) |
| Owner、技术深度、产品冲突与责任分工 | 复用 | [跨部门案例](management/detailed/03-cross-functional-casebook.md) |
| 多项目优先级、错误决策与重构复盘 | 复用＋深化 | [管理运行机制](management/detailed/05-management-operating-system-playbook.md)；§9 增量决策证据 |
| 技术债、预算和 SLO 的关系 | 复用 | [成本与供应商](management/detailed/12-finops-vendor-playbook.md) |
| 招聘培养、绩效与晋升公平 | 复用 | [人才生命周期](management/detailed/08-talent-lifecycle-performance-playbook.md) |
| 跨时区沟通与接班确认 | 深化 | §9；UTC、未决状态、升级联系人与明确接班 |
| AI 研发流程和可验证交付 | 复用 | [Agent 研发实施](management/detailed/19-agent-based-development-implementation.md) |
| Spring 事务代理、自调用和回滚 | 深化 | §8；代码路径＋真实数据库失败断言 |
| 线程池、锁和虚拟线程 | 复用＋深化 | [并发 JVM](high-concurrency-jvm-tuning.md)；§8 补线程间事务边界 |
| SQL 索引、超扣、死锁及重复付款 | 复用＋深化 | [结算验收](performance/settlement-completion.md)；§8 条件扣减与唯一约束 |
| 源码定位、框架边界和近期验证证据 | 复用 | [代码注释清单](code-comment-coverage.md)、现有测试与本次独立模型 |
| Git / CI、可回退资金变更 | 复用 | [安全发布](safe-release.md)；没有更换既有 CI 平台 |

## 不能标为“生产已完成”的工作

1. 真正数据库分库下的分支事务、协调日志、恢复 Worker、并发围栏与故障切面。
2. 真实 XA / 2PC 的 prepare、数据库锁与崩溃后恢复；本次只是状态模型。
3. 多节点持久日志复制、多可用区存储、真实网络分区与持续 RTO/RPO 证据。
4. 银行、托管、法币通道、真实行情/对冲和具体适用规则的外部联调。
5. 现货生产交割接入手续费与费率版本；模型的费率计算不改变现网账务。

这些项目需要独立资源、接口合同、风险授权和验收环境，不通过修改展示文案宣称完成。
