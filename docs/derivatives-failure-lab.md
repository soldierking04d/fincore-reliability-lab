# 合约交易四类关键故障：从业务损失到可验证保护

## 先看结论：这部分证明什么

这不是给现货撮合增加一个“杠杆倍数”字段，而是独立补充合约容易出错的四条金融约束：

| 代表性问题 | 用户看到的事故 | 实验里的保护 | 最小复算例子 |
|---|---|---|---|
| 多交易对抢同一份保证金 | 两笔开仓都被放行，实际资金不够 | 账户级预算锁、持久化占用、释放幂等 | 余额 10,000，两笔各占 6,000，只能一笔成功 |
| 资金费重送、晚到或方向错误 | 同一期重复扣费；平仓后历史费用算错 | 固定周期快照、业务唯一键、消息冲突校验、双腿账本 | 1 BTC × 60,000 × 0.01% = 6 USDT，多头只扣一次 |
| 多张只减仓订单竞争 | 原本平多仓，最后变成意外空仓 | 执行时重查方向与仓位，超出量取消 | 现有 1 BTC，两张各平 0.8，实际总量只能为 1 |
| 旧强平任务继续执行 | 用户已经补保证金，旧 Worker 仍强平 | 账户版本、Epoch、标记价时效与写入 CAS | 补入 2,000 后旧快照失效；接管后旧 Epoch 被拒绝 |

**实现状态：** 已加入 Spring Boot 服务、MyBatis Mapper、独立迁移、单元测试及 PostgreSQL 集成验收用例。
**验证边界（2026-09-03 更新）：** [CI 33762357631](https://github.com/soldierking04d/fincore-reliability-lab/actions/runs/33762357631) 在真实 PostgreSQL 测试容器中执行了全部 92 项测试，0 失败、0 错误、0 跳过，其中新增合约单元检查 23 项、数据库并发/回滚验收 13 项。它证明这些用例的正确性，不是生产规模压测或完整强平链路认证。
**交付边界：** 本模块仅在 `lab` Profile 加载，未新增公开资金操作接口；此补充本身不代表已推送 GitHub、已部署或已进入网页演示。

这些是根据公开合约规则建立的工程故障模型，不是声称任何交易所实际发生了对应事故。

## 一、范围与不可突破的约束

### 1.1 本次真正落地的范围

- USDT 线性合约实验，正数量代表多仓、负数量代表空仓；一个账户最多一个净仓位。
- 账户余额、预算、版本和接管 Epoch 存在 PostgreSQL，不用 Redis/JVM Map 充当金融事实。
- 资金费与平仓已实现盈亏使用相反的双腿分录；钱包可按期初余额加分录重算。
- 业务决定、资金费快照、账本和 Inbox 都只追加，数据库触发器拒绝修改和删除历史。
- 占用/释放、资金费、平仓、补充保证金、接管、强平准入都有明确重试行为。
- 复用现有事务 Outbox，事件类型前缀为 `DERIVATIVE_LAB_`。现有发布器将非撮合事件发往配置的结算事件 Topic；本次不增加 Kafka 消费器，也未验证合约事件的端到端消息投递。

### 1.2 不能据此声称已经完成的内容

保证金占用是**预算并发实验**，金额由可信实验输入，不是完整初始保证金引擎；它没有自动绑定到现货挂单、真实开仓或全仓净额计算。强平只做到**风险重验和进入冻结状态**，不做到强平成交、分级减仓、保险基金、ADL 或破产损失分摊。

期初余额和仓位是实验夹具，不是充值或开仓业务。平仓的价格也是可信实验成交输入，不是来自实际合约撮合。本次没有真实资金、外部行情源、认证授权、阶梯保证金、手续费、双向持仓、币本位、到期交割、全仓多合约风险聚合或组合保证金。

因此，准确定位是“**合约关键金融约束的实现与验收作品**”，不能写成“成熟合约交易所”或“已验证生产规模高并发”。

### 1.3 统一精度和状态口径

金额、价格、数量、费率统一最多八位小数，数据库为 `NUMERIC(28,8)`；超精度输入明确拒绝，不由数据库静默舍入。资金费与已实现盈亏的计算结果统一 `HALF_EVEN` 到八位，结算双方使用**同一已舍入金额的相反数**，避免双边独立舍入差。

- `wallet`：已实现现金，不含未实现盈亏。
- `reserved`：实验中尚未释放的开仓预算，不是维持保证金，也不是已持仓初始保证金。
- `equity = wallet + (mark − entryPrice) × signedQuantity`：仅适用于本实验的单一线性净仓位。
- `maintenance = abs(quantity) × mark × 0.005`：固定 0.5% 的实验参数，不代表任何交易所的实际规则。
- `wallet` 可以因已发生的费用或亏损变负。不能以“余额不足”为由拒绝记录已经发生的损失；负权益不是问题已解决，必须停止新增预算并进入后续风险处置。

## 二、场景一：行情急变时，多交易对重复占用保证金

### 概设

按 BTC、ETH 分别串行撮合，不等于同一账户的资金也串行。市场波动时，用户或策略可以同时在多个交易对发单；如果各自读到同一份余额，都会认为可以开仓。

### 具体过程与修复

1. 账户有 10,000 USDT，BTC 单和 ETH 单都需要 6,000 预算。
2. 错误实现是两个线程都读取 10,000，各自批准，最终批准 12,000。
3. 本实现先锁定 `lab_derivative_account` 的同一行，再检查 `wallet − reserved`。
4. 第一笔把 `reserved` 更新为 6,000；第二笔在取得锁后读取最新状态，返回 `INSUFFICIENT_MARGIN`。
5. 取消后使用原订单键调用 `release`，生成独立释放决定。再次释放不会再加回资金。

原拒绝决定也是不可变记录。释放预算后若想重新下单，需要用户发起**新的业务意图**；网络超时的原请求必须保持原业务键重试，不能由 SDK 私自换键。

**代码：** `DerivativesLabService.reserve/release`；`DerivativesLabMapper.lockAccount/changeAccount`。
**数据库验收：** `concurrentSymbolsCannotSpendTheSameMarginTwice`、`reservationReplayIsImmutableAndConflictsAreRejected`。
**验收断言：** 一笔成功、一笔拒绝；占用 6,000；释放两次后占用为零而不是负数。

**业务与运营协同：** 产品应区分“明确拒绝”和“提交结果未知”；客服按业务键查询最终决定；运营不能因为用户投诉超时就人工补发一笔新订单。技术负责人需要定义拒绝率、账户锁等待、重复请求比例，并以资金不重复占用为发布门槛。

## 三、场景二：同一期资金费重复扣收，或者晚到后按错仓位计算

### 概设

Kafka 的消息编号不是资金费的业务身份。人工补发、消费重试或定时任务重复触发，可能生成不同消息编号，但对应的是同一账户、同一合约、同一个资金费周期。

公开规则中，资金费与周期时点的持仓有关；正费率通常由多头支付给空头，负费率相反。各合约周期并不必然固定为八小时。[Bybit 资金费说明](https://www.bybit.com/en/help-center/article/Funding-fee-calculation?category=cd60af6303161fd598)

### 具体过程与修复

1. 实验在明确的逻辑周期边界调用 `captureFunding`，固化数量、标记价、费率和账户版本。
2. 例如多头 1 BTC，标记价 60,000，费率 0.0001：用户现金变动为 −6，模拟结算池为 +6。
3. `applyFunding` 的业务键由合约与整秒 UTC 周期构成，并通过账户维度唯一约束确定身份。
4. 两条不同消息并发执行，只生成一条业务操作和一对分录；每条投递可记录独立 Inbox，但不能再扣费。
5. 若平仓先于收费任务到达，收费仍使用已固化周期数量，不能使用当前零仓位把原费用抹掉。
6. 相同周期改费率/标记价，或换结算对手方，明确报冲突；同一消息编号串到另一个周期也拒绝。

**时间边界说明：** 本实验使用人工控制的逻辑周期，没有实现交易所级事件水位、跨分片屏障或历史仓位重建。示例中的 UTC 周期是复算标签，不能拿当前仓位直接补算真实历史资金费。

**代码：** `captureFunding/applyFunding/inbox/cash`。
**数据库验收：** `fundingUsesBusinessCycleInsteadOfDeliveryId`、`fundingSnapshotSurvivesCloseAndNegativeRateCreditsLong`、`conflictingDeliveryIdCannotCrossFundingCycles`、`fundingRollsBackAllEffectsWhenOutboxFails`。
**验收断言：** 钱包只减 6；双腿之和为零；消息冲突不留新的资金效果；Outbox 注入失败时钱包、分录、操作、Inbox 全部回滚。

资金费还可能让账户跌破维持保证金线：1 BTC 标记价 60,000，实验 MM=300；钱包由 305 扣 6 变成 299。`fundingCanCrossMaintenanceThreshold` 要求重新评估后进入强平准入，而不是因为费用处理成功就忽略风险。

**业务与运营协同：** 账单应展示周期、仓位快照、费率、金额和账本编号。费率纠错需要经过风险/财务复核，新增冲正与调整业务，不能让技术直接修改原流水。本实验未实现冲正操作入口，只保留不可修改历史的边界。

## 四、场景三：两张平仓单竞争，止盈变成反向开仓

### 概设

用户可能同时挂止盈单、手动平仓单，或止损后旧挂单才成交。如果“只减仓”只在下单时检查，真正执行时仓位已经被其他订单减少，旧请求可能开出反向仓位。公开的 Reduce-only 规则强调只减少现有仓位，超额订单需要缩减或取消。[Bybit Reduce-only 说明](https://www.bybit.com/en/help-center/article/Reduce-Only-Order?category=5f2fb74e9c8b771130)

### 具体过程与修复

- 当前多仓 1 BTC，平均入场价 60,000。
- 两张 SELL 只减仓请求各 0.8 BTC，实验成交价 61,000。
- 第一笔实际平 0.8，第二笔在锁内读到只剩 0.2，因此仅平 0.2，剩余 0.6 取消。
- 总已实现盈亏为 `(61,000 − 60,000) × 1 = 1,000 USDT`，不是按请求总量 1.6 计算。
- 最终仓位为零，不是 −0.6；重送任意成交业务键不增加盈亏。

空头同理：−1 BTC 在 60,000 开仓、59,000 买入平仓，已实现盈亏是 +1,000。空头 SELL 会扩大风险，因此以 `WRONG_SIDE` 拒绝；已平仓请求返回 `NO_POSITION`。

**代码：** `reduceOnly`；先锁两个资金账户，再读取仓位、方向和状态，更新真实执行数量并双腿记账。
**数据库验收：** `competingReduceOnlyFillsCannotOpenAReversePosition`、`reduceOnlyChecksDirectionAndSupportsShortPnl`。
**范围提醒：** 此处是成交执行阶段的只减仓保护，不包含整个挂单优先级、条件单触发与撮合系统。没有真实手续费和滑点，不能用示例盈亏当作用户交易收益预测。

**业务与运营协同：** UI 必须展示“请求 0.8、实际 0.2、取消余量 0.6”的原因；客服不能把保护性缩量解释成系统吞单。风控和产品应共同确定超量是全部拒绝还是部分执行，本实验明确选择后者。

## 五、场景四：GC 暂停、Worker 接管与补仓交错，产生过期强平

### 概设

风险任务生成后，用户可能补充保证金、平仓，资金费也可能改变权益。旧 Worker 在 GC 暂停后恢复，还可能持有旧接管身份。因此，单独判断“任务生成时需要强平”或“Redis 锁仍在”都不够。

标记价格、维持保证金和最终强平成交价格是不同概念，不能直接以最新成交价替代风险标记价，也不能把预估强平价当成保证成交的价格。[OKX 强平问题说明](https://www.okx.com/en-gb/help/frequently-issues-of-contracts-for-compulsory-liquidation)

### 具体过程与修复

1. 多头 1 BTC，入场 60,000，钱包 5,000；标记价跌到 54,000。
2. 实验权益为 −1,000，MM=270，风险任务携带账户版本 V 和 Epoch E。
3. 若用户先补 2,000，钱包变 7,000、版本变 V+1，旧任务返回 `STALE_ACCOUNT`。
4. 重取快照后权益为 1,000，高于 MM=270，返回 `NOT_REQUIRED`。
5. 若新 Worker 已把 Epoch 提升至 E+1，旧 Worker 即使持有未过期账户版本，也返回 `STALE_EPOCH`。
6. 标记价超过五秒或来自未来，返回 `STALE_MARK`；五秒是实验阈值，不是行业通用配置。
7. 全部检查通过后，数据库写入仍带 `WHERE version=? AND epoch=? AND state='ACTIVE'`，未命中唯一行就抛错回滚。

**代码：** `takeover/assess/liquidate/liquidationStatus` 和 `enterLiquidation` 的最终 CAS。
**数据库验收：** `oldLiquidationWorkerIsFencedAtTheDatabase`、`marginTopupInvalidatesLiquidationSnapshot`、`staleOrFutureMarkFailsClosed`。
**并发语义：** 补仓/平仓先提交，旧风险快照失效；强平准入先提交，普通新预算与平仓不能绕过冻结状态。已成功的同业务键重试只返回原结果，不再执行。

本实验的 `takeover` 是受控的手动接管注入，不含实际选主、租约 TTL 和 Worker 身份认证；`assess` 输入也是实验价格，不是可信外部行情签名。`LIQUIDATING` 只是冻结后的强平处理中状态，不代表仓位已经被市场成交，也没有自动恢复 ACTIVE 的流程。

**业务与运营协同：** 风控负责阈值与价格源策略，研发负责一致性与拒绝证据，运营负责对用户解释处理中的状态。价格源异常时不能简单展示“系统正常”；应停止使用过期价作出破坏性决定，并明确风险敞口与处置责任人。

## 六、代码结构、事务边界与性能取舍

| 层次 | 文件 | 责任 |
|---|---|---|
| 应用服务 | [DerivativesLabService](../src/main/java/dev/fincore/application/DerivativesLabService.java) | 业务规则、锁顺序、事务、幂等与失败契约 |
| 持久化 | [DerivativesLabMapper](../src/main/java/dev/fincore/infrastructure/persistence/mapper/DerivativesLabMapper.java) | 行锁、CAS、周期快照、双腿分录、Inbox |
| 数据结构 | [V7 迁移](../src/main/resources/db/migration/V7__derivatives_lab.sql) | 六张隔离表、唯一约束、不可变历史触发器 |
| 无数据库测试 | [DerivativesLabServiceTest](../src/test/java/dev/fincore/application/DerivativesLabServiceTest.java) | 计算、符号、边界、载荷冲突、调用契约、Mapper 解析 |
| 数据库验收 | [DerivativesLabIntegrationTest](../src/test/java/dev/fincore/DerivativesLabIntegrationTest.java) | 真实并发、持久幂等、原子回滚、版本接管 |

资金操作事务边界是：**确定顺序锁住全部账户 → 读取权威状态 → 核对业务键 → 写决定/仓位/双腿账本/钱包/Inbox → 写 Outbox → 提交**。任何持久化异常都向上抛出，由 Spring 回滚；命令响应丢失则使用原业务键重试。运行入口必须通过 Spring 代理，不能 `new` 服务后误以为注解事务仍生效。

账户锁会牺牲同一账户的并行度，但防止跨交易对重复使用资金。不同账户通常可以并行；本实验为了简化双腿对账共用模拟结算池，池账户会形成热点，**不能拿它做生产吞吐基准**。生产资金费批处理需要按资金域分片、确定性清算批次、背压和账务净额方案，不能靠取消资金锁提速。

## 七、怎样验收，哪些数字不能冒充证据

### 7.0 本次实际验证记录（2026-09-03）

| 检查 | 实际结果 |
|---|---|
| JDK 21 / Maven 全项目 `verify` | 干净构建成功，包含 Spring Boot 可执行 JAR 打包 |
| 全项目测试统计 | CI 中 92 项全部通过，0 失败、0 错误、0 跳过 |
| 新增 `DerivativesLabServiceTest` | 23 个全部执行通过，包含 MyBatis SQL 映射解析 |
| 新增 `DerivativesLabIntegrationTest` | 13 项真实 PostgreSQL 并发/回滚验收全部通过 |
| Java 规范检查、原有核心可靠性脚本 | 通过；原有核心脚本结果不冒充新增合约证明 |

本地没有可用 Docker；另尝试启动隔离 PostgreSQL 时，系统拒绝共享内存创建，数据库没有成功启动。未连接线上数据库，也未为测试启动桥接 UTM 虚拟机。首次打包发现历史构建目录中存在重复 `.class` 产物；保留旧构建目录后重新构建成功，没有为此修改业务源码或删除测试。

**当前数据库测试门槛已满足。** 之前本机缺少 Docker 导致 33 项跳过的结果仍不算通过；这里的结论来自上述 CI 实际执行记录。工作流额外运行 `scripts/VerifyTestReports.java`，缺失关键套件或任意跳过都会阻止发布。

### 7.1 本地分支与计算检查

要求 JDK 21 和兼容的 Maven：

```bash
mvn -Dtest=DerivativesLabServiceTest test
./scripts/verify-code-conventions.sh
```

这些测试不需要 Docker，但 Mock 只能证明分支和调用契约，不能证明 PostgreSQL 的隔离、锁争用或事务回滚。

### 7.2 必须真正运行数据库的验收

在 Docker 正常的环境中执行：

```bash
mvn -Dfincore.test.require-database=true \
  -Dtest=DerivativesLabIntegrationTest test
```

也可使用**专门新建、可丢弃、完全没有业务数据**的 PostgreSQL 16 测试库：

```bash
mvn -Dfincore.test.require-database=true \
  -Dfincore.test.jdbc-url=jdbc:postgresql://127.0.0.1:5432/fincore_derivatives_test \
  -Dfincore.test.db-user=postgres \
  -Dtest=DerivativesLabIntegrationTest test
```

测试会执行 Flyway 并写入夹具，严禁指向线上库。需要密码时通过本地受控配置传入 `fincore.test.db-password`，不要提交凭据。集成测试关闭 Kafka 消费与定时发布，因此只证明 Outbox 同事务持久化，不证明 Broker 已投递。

无数据库且不加 `require-database` 时，集成测试会显示跳过。CI 已加此强制参数，缺少数据库不允许假绿；本地执行 Maven 成功也仍须核对 `Skipped` 数量。

### 7.3 建议检查的资金与运营指标

以下查询来自实验真实表，不是随机生成仪表盘数据；尚未接入网页监控面板或专用 Prometheus 指标。

```sql
-- 拒绝、成功和过期任务的分布；高频 STALE_ACCOUNT 通常需要优化风险任务新鲜度。
SELECT kind, status, count(*)
FROM lab_derivative_operation GROUP BY kind, status ORDER BY kind, status;

-- 必须为零行：任意操作的资金双腿不平衡。
SELECT operation_id, sum(delta) AS difference
FROM lab_derivative_ledger GROUP BY operation_id HAVING sum(delta) <> 0;

-- 必须为零行：钱包不能偏离期初余额加不可变分录。
SELECT a.account_id, a.wallet-a.opening_wallet-COALESCE(sum(l.delta),0) AS difference
FROM lab_derivative_account a LEFT JOIN lab_derivative_ledger l USING(account_id)
GROUP BY a.account_id HAVING a.wallet-a.opening_wallet-COALESCE(sum(l.delta),0) <> 0;

-- 需要明确关注，而不是隐藏：负现金、资金不足和强平中账户。
SELECT account_id, wallet, reserved, version, epoch, state
FROM lab_derivative_account WHERE wallet < reserved OR state='LIQUIDATING';
```

发现账差默认冻结并复核，不修改历史流水或自动把金额“抹平”。性能验收还应测 P95/P99、账户锁等待、风险重评积压、资金费周期延迟及行情过期率；本次小规模并发用例不是压测报告，没有产生生产 QPS 数字。

## 八、如何用于能力展示，以及后续优先级

可以这样描述：

> 我已有现货撮合、账务幂等和故障恢复的基础，并把合约特有的保证金竞争、周期资金费、只减仓和过期强平问题落到了独立实验代码与验收用例。除了复用底层技术，我能明确区分钱包和权益、消息幂等与业务幂等、风险快照与执行时状态，并把规则变成资金不变量、失败契约和对账证据。数据库并发验收的实际状态会如实展示，不把实验边界包装成生产经验。

后续优先补充，而不是本次宣称完成：

1. **已完成：真实 PostgreSQL 并发/回滚验收**；后续每次修改仍须执行完整验收，合约专属交互页面尚未开放。
2. **P0：价格源异常与跳价防护**：多源指数、标记价、异常剔除、版本与风险事件水位。
3. **P0：完整强平处置**：撤单/释放预算、分级减仓、成交回报、破产价、保险基金、ADL、公平性与审计。
4. **P1：真实开仓与保证金闭环**：IM/MM 阶梯、挂单占用、手续费缓冲、全仓跨合约风险与多账户并发。
5. **P1：业务生命周期扩展**：条件单、双向持仓、资金费冲正、币本位、到期交割与对账。

这四个故障的价值不在于数量多，而在于从“用户会损失什么”一路落到“谁负责规则、什么状态必须拒绝、哪一条数据库约束保护资金、怎样复算并向用户解释”。
