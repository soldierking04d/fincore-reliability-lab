# 结算性能优化：实现补齐与验收记录

更新日期：2026-09-21。基础版本 `ee8ed2c`；本文描述其上的结算专项补丁，不把公网旧后端视为已切换的新版本。

独立展示入口：[腾讯云结算性能专项](https://124.223.164.254/#settlement-optimization)。完整背景、诊断 SQL、对照实验协议见[实施手册](settlement-performance-playbook.md)。

## 1. 本次补什么，不用什么冒充完成

本次从“性能方案”补到可执行代码：先修复会扭曲性能结果的资金幂等与统计错误，再减少确定的重复 SQL，最后补逐笔审计。不是靠隐藏待办、删除失败用例或放宽资金校验来完成页面。

| 缺口 | 实现位置 | 保持不变的资金约束 |
| --- | --- | --- |
| 同业务键不同载荷被当成普通重复 | `SettlementService` 的经济字段比较 | 旧订单、原分录与账户余额不改写；金额数值相等但 scale 不同允许重放 |
| 新 messageId 重投旧业务，再次重放查不到原单 | `SettlementMapper` 查询 Inbox 中已持久化的命令关联 | 不覆盖原订单第一次 messageId；重复消息仅增加自己的 Inbox，不再扣款 |
| 未消费的业务键查询产生空指针 | `SettlementNotVisibleException`、`ApiExceptionHandler`、控制器查询契约 | 未可见不是确定失败，不据此补单或补偿 |
| 等热点锁或外层事务跨 TTL 后继续落账 | `SettlementService`、`SpotDeliveryService` 提交前 Fence 复核 | 原子回滚分录、余额、Inbox、订单状态和 Outbox；旧 Epoch 不增加资金效果 |
| 外键 KEY SHARE 升级账户锁导致死锁 | `LedgerMapper`、`SpotFundsMapper` 使用 `FOR NO KEY UPDATE` | 不更新账户键；余额写入仍互斥，统一 UUID 锁序不取消 |
| success/completed 先于真实提交增加 | `TransactionMetrics`、通用结算与现货交割服务 | 回调只更新观测，不迁移资金动作；回滚不计成功 |
| Consumer 计时漏掉路由与 Lease | `SettlementListener` 与 histogram 配置 | 异常仍抛给 Kafka；不提前确认 offset；标签保持低基数 |
| 现货账户锁后再次逐个查询资产 | `SpotDeliveryService`、`SpotFundsService` | 复用本事务锁定行，保留同样的资产和资金校验，不建跨事务余额缓存 |
| 只有账户净额，发现不了抵消的错误分录 | `settlement-database-audit.mjs` | 业务全集、逐笔分录、借贷、余额和 Outbox 分层核验；只读，不调平 |

## 2. 幂等：重复的是事实，不只是字符串编号

例：原命令 `businessKey=order-1`，付款 100，转账 10，手续费 1。完成后付款 89、收款 10、费用 1。

- 相同 messageId、相同业务和经济字段：返回原结果，余额仍为 89 / 10 / 1。
- 新 messageId、相同业务和经济字段：保存新消息关联；之后再次重放该 messageId 仍能找到原结果。
- 同 messageId 换 businessKey，或同业务键换金额、付款/收款/费用账户、资产和费用：显式冲突，整笔事务回滚，不能把旧成功包装成新请求成功。
- `10.0` 与 `10.00` 用 `BigDecimal.compareTo` 按数值比较，不能因为格式变化制造资金冲突。
- 已提交 FAILED 也属于原业务结果，充值后重放不能把这张旧单悄悄变成新扣款。

数据库唯一约束仍是并发仲裁者。两个 Worker 同时提交同业务键时，未提交的唯一键冲突会等待前一事务结果；失败重试也沿原键处理。没有用 JVM Map、Redis 或消息 offset 取代数据库事实。

冲突检测发生于异步消费者，因此 HTTP 202 不代表后续载荷一定通过。当前坏消息仍由既有消费者策略重试，必须报警和审核；本补丁没有增加自动丢弃资金命令的捷径。

## 3. 热点锁与租约：复核在等待之后

场景：Worker 先拿到有效 Fence，随后阻塞在公共付款账户锁；等待过程中租约已过期。入口校验不能证明事务稍后仍有权提交。

测试以独立事务锁住账户，另一个线程运行真实结算，并从 `pg_stat_activity` 验证确实进入锁等待，然后越过租约期限再释放锁。预期是 Fence 复核拒绝，资金修改整体回滚；不能只 sleep 一段时间猜测已发生等待。

`FOR SHARE` 会阻止 Lease 行被并发更新，但不会让 TTL 到期时自动终止正在运行的事务。数据面 Fence 与到期检查承担不同职责。复核也不是数据库层严格的墙钟提交截止线：最终复核之后到 COMMIT 仍有短窗口；硬截止需进一步定义事务预算与故障语义，不能宣称数学上零窗口。

通用结算与现货交割都注册 `beforeCommit`：加入外层事务时，检查随最外层真实提交执行，而不是仅在内部服务返回前执行。现货原方法尾检查仍保留，提前发现明显过期；没有真实事务时拒绝执行资金流程。

### 3.1 统一锁顺序之外，还要避免锁升级

两笔不同业务先插入引用同一账户的订单，PostgreSQL 外键检查会持有账户行的 KEY SHARE。随后同时请求 `FOR UPDATE`，即使 UUID 顺序一致，也可能互相等待锁升级而死锁。

本项目余额写入不修改账户主键，所以两个账户锁 Mapper 改用 `FOR NO KEY UPDATE`：它与外键 KEY SHARE 兼容，余额修改之间仍互斥。保留确定 UUID 锁序、条件扣款与影响行数检查。测试先真实复现 `40P01 deadlock detected`，再验证共同付款方、反向转账和现货账户引用；不能把“所有死锁都消除”作为结论，其他表/索引/锁序仍需诊断。

## 4. 提交后计数与分段监控

`TransactionMetrics.incrementAfterCommit(counter)` 在真实事务提交后增加计数。事务方法体运行完毕不等于 COMMIT 成功；延迟约束或提交阶段故障仍可能导致回滚。

资金写入继续位于原事务内。回调不执行资金 SQL、不发网络通知；通知依旧通过事务 Outbox。进程可能在数据库提交后、回调执行前崩溃，所以 Counter 仍不是精确总账，最终完成量要按数据库业务键校准。

| 指标 | 计时范围 | 使用注意 |
| --- | --- | --- |
| `fincore.settlement.consumer.processing` | 进入 Listener 到返回/抛错，含路由、租约与资金处理 | 不含此前 Broker 排队；新旧版本计时起点不同，不能直接拼接基线 |
| `fincore.settlement.consumer.stage` | 以 stage 分开路由、租约和事务代理调用 | 业务代理调用已经含连接获取及提交/回滚，不能再加一次 Hikari 等待 |
| `fincore.settlement.consumer.inflight` | 包括路由与租约等待中的消息 | 不再遗漏尚未进入资金事务的忙消费者 |
| 通用 success / 现货 completed | 提交后增加 | 有回滚不能计数；进程崩溃仍需账务对账校准 |

Histogram 可用于窗口 P95/P99 查询，但启用 histogram 不等于已经测得 P99。标签只使用 `type={settlement,spot,unsupported}`、`stage={routing,lease,transaction}`、`outcome={success,failure,fence_rejected}`，禁止用账户 UUID、业务键或异常原文作标签。Consumer 的 success 表示同步调用正常返回，包含重复和已提交业务 FAILED；新增资金完成另看提交后计数及账务事实。

## 5. 减少 SQL，而不减少校验

现货交割锁定四个参与账户时已获得资产及资金快照。原先随后再读四次用于资产检查；现在直接使用本事务中每次锁定返回的行校验资产。撮合预占还复用锁定的付款账户快照，另减少一次重复读取。

交割路径少四次重复账户读取，但本轮同时增加一次提交前 Fence 查询，因此正常成功路径相对原实现**净少三次 SQL**；预占路径少一次重复读取。不能只报告删掉的查询而漏掉新增安全检查。这是源码与调用次数证明，不是“TPS 提升 X%”的实测。余额、预占、在途更新、行数检查和平衡分录均保留。

数据库连接池不能因为 SQL 少了就立即扩大。先用相同数据分布验证连接持有时间、锁等待、每完成单 CPU/WAL 成本，再单变量改变 Consumer 数或池大小。

## 6. 从账户合计升级到逐笔证据

账户净额相同可能掩盖两条相互抵消的错误分录。新增审计工具读取本轮完整命令、账户和业务键清单，生成受限只读 SQL，再独立校验快照。

审计使用专用数据库、专用只读角色、隔离标记、REPEATABLE READ READ ONLY、有限语句/锁超时。SQL 生成不联网，不会自动执行；工具不持有生产凭据。

验收包含：业务键集合闭合；每笔账本和每条预期分录恰好一次；同资产借贷相等；期初加分录等于余额；原命令经济字段一致；Inbox 与 Outbox 归属正确；事件最终发布状态明确。所有金额按精确字符串和 BigInt 处理。

PUBLISHED 只代表发布器记录 Broker 确认，不证明下游业务恰好处理一次。下游仍需 Inbox/业务幂等；单纯往数据库夹具填 PUBLISHED 不构成 Broker 实际送达证明。

## 7. 验证记录

定向红绿测试在本机隔离 PostgreSQL 运行：第一轮修改前 21 项中 13 个断言失败、4 个错误；第二轮补充锁升级与外层事务边界，在 39 项中复现 2 个断言失败、3 个数据库死锁错误。修复后最终 **57 项定向测试通过，0 失败、0 错误、0 跳过**：25 项通用真实 PostgreSQL、8 项现货真实 PostgreSQL、24 项控制器/Listener/指标/快照测试。

现货真实数据库测试包含：真实提交、外层事务回滚、延迟约束在 COMMIT 阶段失败后原键重试、资产不匹配整单回滚，以及 6 个并发重放仅 1 次资金完成、5 次幂等返回。通用测试覆盖载荷变化、别名重放、金额格式、未知查询、账户锁等待跨 TTL、终态与并发。

Prometheus 注册表真实 scrape 已验证两种 Timer 的 histogram buckets；没有实际压测时不填造 P99。

| 验证层级 | 实际执行结果 | 边界 |
| --- | --- | --- |
| Java 定向回归 | 57 通过、0 跳过 | 33 项真实 PostgreSQL；其余为单元/配置测试 |
| 工具协议与审计测试 | 34 通过、0 跳过 | 无网络夹具；不当作真实吞吐 |
| 数据库审计本机实验 | 两笔合成交易：正常快照通过；额外 Inbox、净额抵消重复分录被拒绝；只读角色写入被拒绝 | 使用真实 PostgreSQL；PUBLISHED 为夹具，不证明 Kafka 已送达 |
| 完整本地 Maven verify | 204 项中 149 实际通过、55 Docker 依赖项跳过；0 失败/错误 | 整体命令失败：行覆盖率 48% 低于 60%、分支 36% 低于 45%；后续 PMD/SpotBugs 未运行 |

完整本地 verify **未通过**，门禁保持不变。没有 Docker/Kafka 的环境不能替代完整集成 CI，不能把定向通过等同于全部发行门禁通过。新增两套数据库测试也已纳入强制报告清单；严格模式无数据库时实际验证为退出 1，而不是跳过后通过。最终以严格模式和显式本机测试数据库重验，仍为 57 项全通过。

此次在独立临时源码快照中构建，规避同步目录产生的重复迁移文件；163 个源码/测试/配置/pom/门禁脚本文件哈希与工作区匹配。使用最终独立报告，不引用工作区中旧的 target 报告。历史无网络工具与路由测试见[2026-09-20 基线记录](VERIFICATION.md)。

## 8. 仍需完整环境验收的范围

| 项目 | 当前决定 | 不能省略的下一步 |
| --- | --- | --- |
| 多实例 Kafka 分区 / Worker shard 归属协调 | 不能由单机测试推出扩容正确性 | 双实例归属冲突、滚动更新、分区扩容与接管实验 |
| 持续现货 / 混合负载容量 | 单元和有限资金样本不替代容量认证 | 固定配额、数据分布、持续到达率、至少三轮对照，资金与积压同时验收 |
| COMMIT 网络回包丢失 | 提交前/后程序故障与网络黑洞不等价 | 隔离网络代理制造真实不确定提交，原键查询恢复，不盲目补单 |
| 正式费用版本、账户 Lane、有界归集与回补 | 属于热点账户协议，不因拆出结算章节自动实现 | 映射迁移、资金支持的预算、退款缓冲业务决策和前后台共同验证 |
| 多笔资金合成大事务 / 净额结算 | 不作为默认优化，不是漏写一段循环 | 需独立业务批次、水位、审计与冲正规则；无协议时不实施 |
| 腾讯云后端升级 | 网站发布与资金后端发布分开 | 通过完整数据库/Kafka CI 与既有发行门禁后再切换；不伪造 skipped=0 |

部署不删除历史账务，不清 Inbox/Outbox，不重置 offset，不撤销已提交经济事实。未通过完整发行门禁前保持旧运行后端；代码与可验证说明可独立展示。

## 9. 官方语义参考

- [Spring TransactionSynchronization](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)：afterCommit 才发生在提交之后；回调中的数据库访问不应被当成另一次自动提交。
- [PostgreSQL 16 行锁](https://www.postgresql.org/docs/16/explicit-locking.html)：锁兼容关系、等待与事务结束时释放。
- [Micrometer Histograms and Percentiles](https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html)：histogram 与百分位计算的区别。
