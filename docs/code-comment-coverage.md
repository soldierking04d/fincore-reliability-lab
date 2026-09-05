# 代码注释与 CPU 优化索引

本文记录 FinCore Reliability Lab 本轮中文注释增强的具体位置。目标不是增加注释数量，而是让读者从
源码中直接回答四个问题：这块代码解决什么故障，请求如何流转，CPU/内存/锁/I/O 为什么这样设计，
以及性能优化不能突破哪些金融正确性边界。

## 1. 统一注释结构

核心运行类统一采用以下结构：

1. **解决的问题**：说明流量、并发、重复、乱序、部分失败或接管带来的真实风险。
2. **执行链路**：说明入口、分片、事务、数据库和消息的先后关系。
3. **CPU/内存/锁/I/O**：写明具体机制、复杂度、线程预算、批量方式和对象分配，不写空泛的“高性能”。
4. **正确性边界**：明确数据库事务、唯一约束、不可变账本、幂等和 Fencing 仍是最终裁决。

DTO、枚举和纯值对象没有独立线程或 I/O 策略时，不强行加入 CPU 宣传。它们继续说明字段不变量，
模块级性能边界统一放在对应的 `package-info.java`。

## 2. 模块级注释落点

| 位置 | 新增说明 |
| --- | --- |
| `src/main/java/dev/fincore/package-info.java` | 项目整体问题、分层依赖、性能优化不得替代金融不变量 |
| `application/package-info.java` | 完整业务用例、Lane/UUID 锁序、事务与幂等边界 |
| `domain/package-info.java` | BigDecimal 精度成本、整数路由、UUID 128 位比较 |
| `exchange/package-info.java` | 交易所外围模型用途，以及绑核、NUMA、硬件卸载尚需专项验证 |
| `infrastructure/package-info.java` | 线程、连接、队列、批次的资源预算原则 |
| `infrastructure/concurrent/package-info.java` | 虚拟线程、撮合平台线程、Kafka 平台线程的分工 |
| `infrastructure/persistence/package-info.java` | 索引、批量 JDBC、Statement 级缓存和数据库 CPU |
| `infrastructure/persistence/mapper/package-info.java` | SQL 原子操作、锁范围和预编译参数边界 |
| `messaging/package-info.java` | Outbox 批量发布、Consumer 线程和至少一次投递 |
| `simulation/package-info.java` | 教学模型不用于证明生产 CPU/GC/网络容量 |
| `web/package-info.java` | 虚拟线程只承担短 I/O，HTTP 超时不取消已开始事务 |

以上包说明覆盖 101 个生产 Java 文件的分层背景；具体热点仍在类和方法处就地解释。

## 3. CPU 优化热点索引

| 代码位置 | 解决的问题 | 已落地的 CPU/资源机制 | 正确性边界与验证指标 |
| --- | --- | --- | --- |
| `FinCoreApplication` | 各入口采用不同线程和恢复策略 | 只负责统一装配，不临时创建执行器；资源由 Spring 生命周期管理 | 启动成功不等于依赖健康，检查 Actuator |
| `ConcurrencyConfiguration#virtualThreadMetrics` | 虚拟线程可能被同步块长期固定 | 用 JFR Binder 暴露 pinned/submit failed 指标 | 指标只发现问题，需用 JFR 栈定位 |
| `ConcurrencyConfiguration#matchingTaskExecutor` | 同交易对有序、不同交易对并行 | 固定单平台线程 Lane；Lane 数决定实例 CPU 并行度 | 跨实例仍依赖 PostgreSQL 交易对锁 |
| `ConcurrencyConfiguration#settlementKafkaConsumerExecutor` | Consumer 与撮合争抢线程 | Consumer 数为 `min(配置, 可用处理器)`，零容量平台线程池 | offset 在记录成功后提交，异常必须上抛 |
| `StripedTaskExecutor` | 洪峰下无界排队和撤单饿死 | O(1) Lane 寻址、预启动单线程、ArrayDeque、普通/撤单双有界队列、8 次优先突发上限 | 饱和明确拒绝，不使用 CallerRuns 或静默丢弃 |
| `MatchingCommandCoordinator` / `TradingOrderCoordinator` | HTTP 线程直接撮合导致竞争 | 虚拟线程只等待 Future，CPU/数据库写转交目标 Lane | HTTP 超时不取消可能已开始的事务 |
| `MatchingService` | JVM 全量订单簿排序与多实例竞态 | 最优价筛选下推复合索引，每轮只保留一个 Maker；同实例先经 Lane | advisory lock、CAS、唯一约束和事务仍是最终保证 |
| `ShardRouter` | 全局队列无法扩展 | 字符串哈希+高低位混合+floorMod，O(1)、零集合分配 | 改分片数会重映射，必须版本化迁移 |
| `FeeShardRouter` | 单手续费账户行锁热点 | 2 的幂分片、位掩码 O(1) 路由 | 分片账户仍是正式账本，改分片数需迁移 |
| `UuidOrder` | 多账户反向锁序死锁 | 直接比较两个 64 位字段，不转字符串；2～4 个账户线性去重避免 HashSet 分配 | 所有资金模块必须复用同一全序 |
| `TradingIdentifiers` | 多入口格式规则漂移、逐单重复编译正则 | 类加载时预编译资产与交易对 Pattern，全部入口复用 | 只验证实验格式，产品扩展必须版本化 |
| `BalancedJournal` | 不平衡分录进入账本 | 固定小列表两次 O(n) 遍历，保持审计清晰 | BigDecimal 精度优先，不改用 float/double |
| `VirtualTaskExecutors` | 实验 I/O 等待占用大量平台线程 | Java 21 每任务虚拟线程、统一命名与关闭责任 | 不增加 CPU 并行度，不绕过 Lane、连接池或 Kafka 有界准入 |
| `SpotFundsService` / `SpotDeliveryService` | 预占、撤单和成交争用多账户 | 先收集账户、原地排序去重、固定顺序加锁 | 分桶覆盖约束与事务防止重复花费 |
| `SettlementService` | 重复结算和部分成功 | 固定少量分录、批量写入、确定性锁序 | Inbox、余额、分录、状态、Outbox、Fence 同事务 |
| `WorkerLeaseManager` | 每条消息续期形成 WAL/行锁热点 | 命中仅 Map 读+时间比较；compute 只合并同 shard 续期 | 缓存不是授权，资金事务重新锁行校验 Epoch |
| `SettlementListener` | 旧 Worker、重复消息和线程膨胀 | 两个 Topic 共用 CPU 封顶平台线程池；同步处理避免二次 Future/切换 | 方法成功后才提交 record offset |
| `OutboxPublisher` | 数据库/Kafka 双写和逐条网络等待 | 有界批量、列表预分配、异步发送重叠网络等待、批量状态回写 | 超时未知留 PROCESSING；协议是至少一次 |
| `FeeAggregationService` | 高频手续费写入单账户 | 位掩码分片，高频写分散；低频归集离开成交热路径 | 归集唯一键、平衡分录和余额同事务 |
| `TradeReliabilityService` / `TradeReliabilityMapper` | 全量数据拉回 JVM 对账 | 数据库集合运算/全外连接，查询和修复按上限或分页 | 自动修复只作用派生投影，不改权威成交 |
| `ReconciliationService` / `ReconciliationMapper` | 余额与账本漂移 | 聚合下推数据库，后台扫描与交易热路径隔离 | 默认只冻结和留证，不自动改余额 |
| `LedgerMapper` | 逐条分录产生大量 JDBC 往返 | 多值批量 INSERT、条件扣款/CAS 下推数据库 | 批量只改善 I/O，不改变事务原子性 |
| `MatchingMapper` | 动态 SQL 与全簿扫描 | 固定买/卖 SQL、索引排序、LIMIT、目标行锁 | 价格时间优先必须由 SQL、索引和事务共同保证 |
| `OutboxMapper` | 多发布者互相阻塞和逐条更新 | UPDATE…RETURNING 有界抢占、SKIP LOCKED、批量成功/失败回写 | publisherId 所有权、异常 PROCESSING 恢复 |
| `PostgresUuidTypeHandler` | foreach UUID 映射和字符串转换 | 原生 UUID/OTHER 参数，避免 toString 与数据库再解析 | 不负责业务归属或锁序 |
| `application.yml` | 默认值把压力转为无界资源 | HTTP 入口预算、12 个固定 DB 连接、50 条 poll、LZ4+5ms+64KiB、线程/队列/批次上限 | ack、超时和池大小不能越过事务语义 |
| `config/jvm/g1.options` | 分配率、停顿和诊断缺证据 | 容器感知、65% 堆上限、并行引用处理、字符串去重、轮转 GC 日志、有限 JFR | 200ms 是目标不是 SLA，参数必须压测 |
| `config/jvm/zgc.options` | 大堆 G1 尾停顿不达标 | 分代 ZGC 作为 A/B 备选，保留相同内存和诊断边界 | 4 核 CPU 饱和时未必优于 G1 |

## 4. 业务与实验类补充位置

| 文件/符号 | 本轮补充重点 |
| --- | --- |
| `AccountService` | 汇总下推数据库，但差异查询不自动调账 |
| `CompensationService` | 反向分录、UUID 锁序、批量写和原成功事实不可变 |
| `TradingLifecycleService` | KYC/风控/行情/额度事务链及查询上限 |
| `TradeReliabilityService` | 指纹只计算一次、数据库集合对账、修复边界 |
| `ShardLeaseService` | 长 GC/网络分区后的旧 Worker、按 shard 行锁和按需续期 |
| `DerivativesLabService` | 保证金/强平/Epoch，BigDecimal 成本及 GPU 不适用边界 |
| `AdvancedLabScenarioService` | 并发只制造受控竞争，不把实验吞吐当生产成绩 |
| `LabScenarioService` | 综合故障编排不进入生产请求路径 |
| `MarketCrashScenarioService` | 暴跌组合实验的固定规模和真实性边界 |
| `TradingLifecycleScenarioService` | 场景锁与真实交易 Lane 的区别 |
| `ExchangeCoverageScenarioService` | 九类模型证明语义，不证明微秒低延迟或硬件能力 |
| `AccountMapper` | SQL 聚合属于对账路径而非撮合热循环 |
| `CompensationMapper` | 原结算局部行锁、唯一键和状态 CAS |
| `DerivativesLabMapper` | 合约实验 CAS、账户锁序和 lab 限制 |
| `FeeAggregationMapper` | 幂等建分片、有限集合查询和账务职责分离 |
| `LabScenarioMapper` | 故障注入 SQL 只允许 lab Profile |
| `ReconciliationMapper` | 后台聚合的 CPU/数据库资源隔离和只留证原则 |
| 其余核心 Mapper | 类级注释分别补充数据库下推、锁范围、批量 I/O 和事务边界 |

## 5. 配置注释的具体位置

- `src/main/resources/application.yml`
  - `server.tomcat`：连接数/accept 队列是入口预算，不是 CPU 线程数。
  - `spring.threads.virtual`：只用于短时阻塞 I/O；撮合和 Kafka 不共享。
  - `spring.datasource.hikari`：固定连接池防止虚拟线程压垮 PostgreSQL。
  - `spring.kafka.consumer/producer/listener`：poll 上限、LZ4/批次取舍和 record ack 边界。
  - `mybatis.configuration`：Statement 缓存和 SQL 超时不能转换为成功。
  - `fincore.concurrency`：Lane、撤单保留队列、Lease 续期、Outbox 批次、调度池用途。
- `config/jvm/g1.options`：逐组解释容器内存、堆余量、暂停目标、引用处理、字符串去重、OOM、日志和 JFR。
- `config/jvm/zgc.options`：解释 ZGC 的并发 CPU 成本、适用条件和与 G1 的 A/B 验证方法。

## 6. 不宣称已经实现的能力

以下能力没有被注释包装成“已经优化完成”，因为当前仓库没有相应生产实现或目标硬件证据：

- CPU affinity/绑核、NUMA 感知、cache-line padding 和无锁环形缓冲；
- 堆外订单簿、零拷贝网络、内核旁路、DPDK/RDMA；
- GPU/FPGA 撮合或风控；当前规则规模下，数据搬运和调度成本可能高于计算收益；
- 微秒级生产延迟、生产 QPS、峰值容量或跨地域 SLA；
- ZGC 必然优于 G1、虚拟线程必然提升吞吐等未经画像验证的结论。

进入这些专项的条件是：JFR/async-profiler、GC 日志、数据库执行计划、Kafka Lag、队列等待和业务
p99/p999 已定位同一个瓶颈，并且在目标 CPU、内存、内核和容器配额上有可重复 A/B 数据。

## 7. 自动防退化与验证

`CodeConventionTest` 新增两项检查：

- 核心运行文件必须同时包含“解决的问题”、CPU/资源机制和“边界”；
- 本索引必须保留 CPU 热点、关键类及未实现能力的真实性声明。

本地验证：

```bash
./scripts/verify-code-conventions.sh
./scripts/run-maven-verify.sh clean verify
```

`verify` 阶段同时运行 P3C-PMD 2.1.1 全部 10 组官方自动规则。整改前后的分类数量、Java 21
兼容处理、唯一虚拟线程豁免和静态扫描的能力边界见
[阿里 Java 规范全量审计与整改记录](alibaba-java-guideline-audit.md)。

性能验证不能只看平均 QPS。至少同时观察：CPU 使用/节流、运行队列、上下文切换、分配率、GC
p99/p999、虚拟线程 pinned、Lane 深度/等待/拒绝、Hikari active/pending、SQL 锁等待、Outbox backlog、
Kafka Lag、结算失败与 Fencing 拒绝。
