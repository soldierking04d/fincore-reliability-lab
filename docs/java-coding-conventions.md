# Java 代码与中文注释规范

本文档以阿里巴巴《Java 开发手册》P3C 的注释、命名和格式要求为基线，并补充 FinCore 金融可靠性项目
特有的不变量。规范面向生产源码、测试源码以及后续自动生成的代码。

## 1. 注释要求

- 每个对外类、枚举、Record 和接口必须有 Javadoc，并写明职责、边界、@author 与 @since。
- 模块入口、核心应用服务、执行器、消息适配器和热路径 Mapper 的类型注释必须回答四个问题：
  “解决什么问题”“请求怎样流转”“CPU/内存/锁/I/O 为什么这样设计”“正确性边界在哪里”。
- 对外方法必须说明业务意图、全部参数、返回值以及调用方需要处理的异常或终态。
- 每个枚举常量必须逐项说明，不能只解释枚举类型。
- 事务、并发锁、幂等键、状态机、Outbox、Fencing、SQL 更新条件和故障注入必须解释“为什么这样做”。
- 涉及 CPU 优化时必须写出可验证机制，例如线程数量上限、时间复杂度、分配来源、批次大小、
  上下文切换、锁竞争或 JDBC 往返；禁止仅写“高性能”“低延迟”“已优化”等无法证伪的结论。
- 若选择没有做某项优化，也要说明原因和触发条件。例如小集合线性去重是为了避免 HashSet 分配，
  BigDecimal 是金融精度成本，GPU/绑核/堆外内存只有在真实画像证明瓶颈后才进入专项设计。
- DTO、枚举和不可变值对象若没有独立 CPU 策略，不强行添加性能话术；由类型注释说明不变量，
  由对应 {@code package-info.java} 记录模块的执行资源与性能边界。
- 注释放在被解释代码的上方并随代码维护；禁止用注释简单复述变量名或保留已经失效的实现说明。
- 中文是默认说明语言；类名、方法名、协议名及 PostgreSQL、Kafka、Outbox、Epoch 等专有名词保留英文。

本轮注释的逐文件落点和真实性边界见 [代码注释与 CPU 优化索引](code-comment-coverage.md)；完整
P3C 扫描口径、626 条初始问题、Java 21 兼容策略和最终结果见
[阿里 Java 规范全量审计与整改记录](alibaba-java-guideline-audit.md)。

## 2. 格式与命名

- Java、XML 和 YAML 使用 4 个空格缩进，UTF-8、LF 换行，文件末尾保留换行。
- 一行只写一条语句，if、for、while 和 catch 始终使用大括号。
- 禁止通配符导入；类名使用 UpperCamelCase，方法、参数和局部变量使用 lowerCamelCase。
- 常量使用全大写加下划线；布尔变量用肯定语义表达，避免需要二次否定才能理解。
- 单个方法只承担一个清晰职责；复杂流程拆分为命名明确的私有方法，并在事务边界处保留整体说明。

## 3. FinCore 金融不变量

- 金额只使用 BigDecimal，禁止 float 和 double；比较金额使用 compareTo。
- 资金余额、账本分录、业务状态、Inbox 与 Outbox 必须在明确的事务边界内保持原子性。
- 历史账本只追加，不更新、不删除；纠错必须新增方向相反且可追溯的分录。
- 幂等唯一约束不能弱化；命中幂等键后仍需校验请求载荷，冲突重放必须失败。
- 多账户加锁按 UUID 排序，避免不同业务路径形成相反锁序。
- SUCCESS 是结算终态；补偿使用独立状态和独立业务键，不能覆盖原成功事实。
- Worker 接管后必须在数据写入事务内验证所有者与 Epoch，旧令牌不得只在入口层拦截。
- 对账发现差异时先冻结和留证；自动修复只能重建派生数据，不能篡改权威成交或历史账本。

## 4. 变更检查

提交前至少执行以下命令：

    ./scripts/verify-code-conventions.sh
    ./scripts/run-maven-verify.sh clean verify

第一条命令检查通配符导入、Tab、单行控制语句以及生产类型的 Javadoc、@author、@since 和包说明；
第二条命令执行 P3C-PMD 全部 10 组官方自动规则、项目架构测试、核心运行类文档契约、单元测试与
可用环境下的 Testcontainers 集成测试。CI 会强制数据库测试不得跳过。

参考资料：

- [Alibaba P3C](https://github.com/alibaba/p3c)
- [P3C-PMD](https://github.com/alibaba/p3c/tree/master/p3c-pmd)
- [Alibaba Java Formatter](https://github.com/alibaba/p3c/blob/master/p3c-formatter/eclipse-codestyle.xml)
