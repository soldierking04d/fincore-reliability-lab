# Java 21 / C++20 单交易对撮合对照实验

这里是可执行的算法对照，不是生产撮合替换，也不是资金账本。两个实现读取同一份确定性输入，在相同整数契约下维护独立的单线程内存订单簿。生产数据库基线 **未测量，不可直接比较**：本实验没有数据库、Kafka、交易网关、网络、资金预占、清算、账本、Outbox 或恢复时间。

## 一条命令复现

在仓库根目录执行：

```sh
bash scripts/verify-java-cpp-matching.sh
```

需要 Java **21**、支持 C++20 和 ASan/UBSan 的 `clang++`、Node.js 18+；不下载依赖。macOS 自动寻找 Java 21，可用 `MATCHING_JDK_HOME` 指定 JDK；`CXX` 可指定编译器。默认执行 7 个手写场景、9,000 条独立参考模型差分命令、三个各 24,000 条命令的性能负载，每个语言每种负载执行 5 个独立进程，每进程先完整预热 3 遍。全部产物写入已被根 `.gitignore` 忽略的 `target/java-cpp-matching/`。

```sh
# 只验证正确性，不跑性能。
bash scripts/verify-java-cpp-matching.sh --tests-only

# 自定义有限负载：count 1000..1000000，warmup 1..20，repeats 3..20。
MATCHING_COUNT=48000 MATCHING_WARMUP=5 MATCHING_REPEATS=7 \
  bash scripts/verify-java-cpp-matching.sh
```

每个子进程最多运行 120 秒，输出上限 128 MiB；超过上限失败退出，不把未完成运行报告成通过。所有请求、订单和响应都保留到进程退出，大规模输入可能超出有限内存；这不是生产保留策略。

主要产物：`report.json` 保存每次运行原始统计，`summary.json` 为展示端提供中位数汇总，`*.samples.*.csv` 保存逐命令纳秒样本，`workload-*.txt` 保存双方实际使用的输入。报告包含源文件、编译产物和输入 SHA-256。重跑保留旧样本文件，新报告只引用当前样本。

## 公共协议与金额契约

ASCII 行协议，字段由空白分隔，响应每条一行。`request`、`order`、`owner` 均为 1..40 位英数字或下划线；只支持一个隐含交易对。

```text
N request order owner B|S price quantity
C request order owner

A|request|order|remaining|maker:quantity:price,... 或 -|cancelled
C|request|order|cancelled
R|request|error
```

价格 `price` 为 **1/10000 报价币的整数 tick**；`quantity` 为整数最小交易单位。Java `long` 与 C++ `int64_t` 都限制在 `1..9223372036854775807`。拒绝零、负值、浮点文本、带 `+` 号的文本、越界整数，以及 `price × originalQuantity` 溢出。

每笔成交采用 maker 价格。卖单可能获得高于限价的成交，因此输入乘积合法仍不代表累计成交金额合法。双方在修改之前预检真实撮合路径：`已预计成交额 + 未成交余量 × 限价` 必须不超过有符号 64 位上界；遇自有 maker 则将剩余部分视为将被取消。这个预留条件同时保证后来作为 maker 的累计成交额不会溢出。拒绝不改变订单、价位或数量，但会缓存该有效请求的拒绝结果。C++ 用除法和减法上界检查，不依赖有符号溢出后的检测。

价格优先、同价位接收顺序 FIFO、部分成交、撤单归属检查均有手写样例。STP 使用 `CANCEL_TAKER`：先前和其他账户的有效成交保留，遇到自己的 maker 后取消 taker 剩余量，自己的 maker 不变。`original = remaining + filled + cancelled`，成交或撤销后的订单编号不可重新使用。FIFO 序号是本次进程的接收序号，不是持久化时间或全局序号。

全局 `request` 键以规范化空白后的全部原始字段为指纹。相同键、相同字段重放**首次响应**，不会再次撮合、撤单或修改数量；不同字段返回 `DUPLICATE_KEY`。数字文本 `01` 与 `1` 是不同指纹。业务拒绝也缓存；不完整行返回 `R|?|BAD_PROTOCOL`，不占用请求键。

响应中的成交列表是请求回执数据，**不是新增成交事件流**。重复回执仍包含原成交，不允许把每次读到的回执重新发布为成交。本实验没有外部事件发布器；手写“成交后重复请求”样例及后续真实撮合证明数量不会重复执行。未来事件接口需要持久化事件 ID、序号和可靠投递契约。

`STATE|...` 是验证用的按订单 ID 排序的摘要，字段依次为 `id:owner:side:price:original:remaining:filled:cancelled:executedNotional`。它不包含请求缓存和 FIFO 序号，不是可恢复快照。最终 FNV-1a 散列只覆盖这份摘要；完整响应对比、FIFO 内部检查及手写样例提供额外验证，不能用散列相同宣称所有内部状态或恢复能力相同。

## 与现有数据库撮合的边界

| 项目 | 本实验 | 现有实现及可核查来源 |
|---|---|---|
| 撮合优先级 | 单线程有序价位与 FIFO | `MatchingService.executeNextMatch`、`MatchingMapper` 使用数据库选择最优 maker |
| 自成交保护 | 保留已完成成交，取消 taker 余量 | `MatchingService.executeNextMatch` 的 `CANCEL_TAKER` 分支；顺序规则一致 |
| 通用现货精度 | 4 位报价 tick、整数数量、有符号 64 位金额边界；无舍入 | 通用 `PlaceOrderCommand` 将价格、数量无损规范为 18 位小数；现货数据库 `V3__matching_engine.sql` 为 `NUMERIC(38,18)`，即最多 20 位整数 |
| 受控现货精度 | 不实现生产资金入口 | `SpotFundsService.validate/exact` 对受控现货限价单进一步要求价格、数量各最多 8 位有效小数；乘积最多 16 位有效小数，存入 18 位小数列，并拒绝成交名义金额超过 20 位整数的输入；不允许静默舍入 |
| 衍生品精度 | 不实现衍生品 | `V7__derivatives_lab.sql` 使用独立 `NUMERIC(28,8)` 模型，不能将其与通用现货 18 位存储、受控现货 8 位输入限制混为一层 |
| 幂等键 | 显式全局请求键，缓存首次回执及业务拒绝 | 数据库 `UNIQUE(user_id, client_order_id)`；`MatchingService.placeInternal` 重放**当前**订单快照与已有 taker 成交 |
| 撤单重试 | 同请求键重放；新请求键撤已结束订单返回 `TERMINAL` | `MatchingService.cancel` 对已取消订单返回当前快照；行为不同 |
| 取消余量 | 转入独立 `cancelled`，`remaining=0` | 数据库保留 `remaining_quantity` 并通过终态表示不再可撮合；字段语义不同 |
| 订单类型 | 仅限价 GTC 行为 | 生产 LIMIT/MARKET、交易生命周期及其他保护规则不在此对照内；本实验没有 MARKET/IOC/FOK/OCO/止损/减仓/强平 |
| 权威与失败恢复 | 易失内存，单进程单写者 | 数据库事务、约束、advisory lock、CAS、成交与审计及 Outbox 原子提交 |

源码定位均为仓库相对路径：

- `src/main/java/dev/fincore/application/MatchingService.java`：生产的事务、重放、STP、成交及撤单语义。
- `src/main/java/dev/fincore/domain/PlaceOrderCommand.java`：通用现货价格、数量的 18 位小数规范化。
- `src/main/java/dev/fincore/application/SpotFundsService.java`：`validate/exact` 对受控现货限价输入进一步施加 8 位有效小数限制，并核验乘积的整数位数。
- `src/main/resources/db/migration/V3__matching_engine.sql`：数据库唯一性、订单状态和数量守恒。
- `src/main/resources/db/migration/V8__spot_funds_delivery.sql`：受控现货资金、交割金额仍存储为 `NUMERIC(38,18)`。
- `src/main/resources/db/migration/V7__derivatives_lab.sql`：独立的衍生品 8 位小数模型。

本目录是算法实验；没有改动上述生产文件、数据库约束或账本代码。Java 源码有中文边界说明和显式控制流大括号，独立编译验证；根项目针对 `src/main` 的 PMD/P3C 门禁**不覆盖**这里，不能把本目录宣称为已经通过完整阿里规约扫描。

## 验证内容与失败行为

`fixtures/golden.json` 是先于实现编写的期望输出，覆盖不同价格和同价 FIFO、部分成交、撤单归属、终态、幂等冲突、原始请求重放、STP、中间有效成交保留、整数边界、超过 JavaScript 安全整数范围的精确价格、输入乘积溢出与累计成交溢出。累计成交溢出测试先观察到旧实现错误成交，加入预检后通过。

`harness.mjs` 用 `BigInt` 和线性扫描/排序实现独立参考模型，不复用价位树算法。种子 `1/42/20260906` 每个生成 3,000 条命令，逐条比对三份实现；每条命令后检查订单簿与索引一致、同价 FIFO、正数剩余量、无交叉挂单、数量守恒及累计金额上界。三份实现分别是 Java、优化 C++、原生 ASan/UBSan C++。完整性能输入另外重放，逐条比对 Java/C++ 响应并核对最后摘要。

ASan/UBSan 使用 `-fno-sanitize-recover=all`，错误必须终止进程。独立 `fixtures/sanitizer-probe.cpp` 故意分别产生有符号整数溢出和堆越界，验证工具必须观察到非零退出及对应错误诊断，否则整项验证失败。探针不会链接进撮合或性能程序。

引擎只能由一个写线程调用；并发调用不是受支持用法，C++ 并发访问会有数据竞争风险。普通业务拒绝发生在状态修改前，正常 STP 按契约保留先前成交。内存分配失败、进程退出、系统异常没有事务回滚或持久恢复保证；程序以失败退出，不伪造成功，不继续对外提供部分状态。不能把内存重放缓存当作生产最终账本或可靠幂等存储。

## 性能统计怎么解读

双方对**同一文件**先解析，再创建预热引擎，完整重放预热，随后创建全新的测量引擎。`mixed` 是限价/撤单/重试混合负载；`sweeps` 每 200 条先挂 160 条卖单，再发 40 条扫单；`retries` 提高重复请求比例。每个独立进程只测量一次，交替 Java/C++ 先后顺序。

每次 `apply()` 前后读取单调时钟，计入幂等查询、业务参数/金额校验、累计金额只读预检、STP、撮合、索引/状态更新、响应对象缓存和内部内存分配。**不计入**文本/数字解析、文件 I/O、回执格式化、引擎构造、预热、逐条不变量检查或最终摘要。Java 迭代器/集合节点和 C++ 树/链表/哈希节点仍然会分配，未使用对象池。

每次进程报告最近秩定义的 p50/p99/p999/max，单位纳秒；汇总是各次 p50/p99/p999 的中位数，以及所有次最大延迟中的最坏值。吞吐为命令数除以整个测量循环耗时，包含取时间戳和保存样本的开销。量级小于时钟粒度的数值没有额外精度保证。未扣除计时器开销，没有 CPU 绑核、温控或系统隔离，没有宣称 JIT 已稳定。GC/JIT/调度暂停可能落入样本，不能据单次尾延迟推断根因。

Java 记录测量线程在测量循环期间分配的字节数（JDK 支持时）；C++ 分配量没有插桩，值为 `null`。两者不能做直接分配量对比。进程 user/system CPU 来自外层计时，包含解析、所有预热、校验、输出和退出，**不是引擎 CPU 时间**；Java 总 CPU 也包含辅助线程。macOS 沙箱不允许 `time -l` 所需查询，因此 RSS 为 `null`；Linux 使用 GNU time 的 KiB 转为字节。RSS 即便可得也不是 Java 堆大小。

报告记录实际 CPU、内存、操作系统、JDK、编译器、编译参数、进程架构和翻译状态。此工作站 Java 21 为 x86_64，宿主为 Apple Silicon；脚本把计时 C++ 也编译成 x86_64，双方经 Rosetta 执行。ASan/UBSan 为宿主原生构建，只用于正确性。这个结果不能外推到原生 ARM、生产 Linux 或数据库系统，也不能推出 C++ 总是更快。迁移决策还需要原生部署环境、持久化协议、故障恢复、峰值负载及端到端测量。
