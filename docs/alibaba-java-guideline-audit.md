# 阿里 Java 规范全量审计与整改记录

本文记录 FinCore Reliability Lab 对阿里巴巴 Java 开发规约的自动化审计范围、整改结果、Java 21
兼容策略和仍需人工判断的边界。它既是本次整改证据，也是后续提交的防退化基线。

## 1. 审计结论

审计日期：2026-09-05。

| 项目 | 整改前 | 整改后 |
| --- | ---: | ---: |
| P3C 自动规则违规（迁移扫描累计） | 626 | 0 |
| 静态分析内部处理错误 | 曾出现（正则消息与旧 DFA 两类） | 0 |
| 扫描范围 | 生产代码与测试代码 | 生产代码与测试代码 |
| 规则范围 | P3C-PMD 2.1.1 的 10 个规则类别 | 54 条可执行规则，覆盖全部 10 个类别 |

同日完整 Docker 验收执行 152 项测试且无跳过，P3C-PMD 为 0 违规，SpotBugs 为 0 Bug/0 Error；
JaCoCo 行覆盖 85.17%、分支覆盖 60.52%，均超过 60%/45% 门槛。大体积原始报告保存在 Git 忽略的
`target/remote-verification-2026-09-05/`，提交后由 GitHub Actions 再执行同一门禁。

本结论表示：当前源码通过 P3C-PMD 可以自动执行的规则，不表示仅靠静态扫描就完成了阿里手册全部
人工条款、金融业务审计、SQL 性能评审或生产容量证明。金融不变量、事务、并发与代码注释另有项目级
测试和脚本共同约束。

## 2. 自动审计基线

Maven `verify` 阶段执行 P3C-PMD 2.1.1 声明的 54 条可执行规则，覆盖以下 10 个类别：

1. `ali-comment`：注释与 Javadoc；
2. `ali-concurrent`：线程池、并发工具和同步使用；
3. `ali-constant`：常量与魔法值；
4. `ali-exception`：异常处理与事务回滚；
5. `ali-flowcontrol`：分支、循环和控制流；
6. `ali-naming`：类、方法、变量与常量命名；
7. `ali-oop`：对象、相等判断与返回类型；
8. `ali-orm`：持久化与数据库约束；
9. `ali-other`：方法长度、复杂度等通用规则；
10. `ali-set`：集合容量、遍历和修改规则。

扫描包含 `src/main/java` 和 `src/test/java`，失败优先级设置为 5，因此所有级别违规都会阻断构建。
除 `skipPmdError=false` 外，`scripts/run-maven-verify.sh` 还检查 PMD 的已知内部错误文本；即使旧 PMD
错误地返回退出码 0，只要日志出现处理失败，构建仍会失败。

## 3. 整改明细

| 规则类别 | 整改前 | 主要整改 |
| --- | ---: | --- |
| 方法与 Mapper Javadoc | 470 | 补齐中文业务意图、全部 `@param` 与 `@return`，保留 SQL 锁、幂等和事务边界说明 |
| 魔法值 | 64 | 提取状态、容量、阈值、资产、指标名和测试规模常量，常量名表达业务含义 |
| 事务回滚 | 39 | 所有金融事务显式声明 `rollbackFor = Exception.class`，防止未来受检异常造成部分提交 |
| 线程池创建 | 13 | 生产虚拟线程统一由 `VirtualTaskExecutors` 创建；测试使用命名、定长、有界执行器 |
| 空值安全相等判断 | 11 | 常量置于 `equals` 左侧，减少可空状态值导致的异常 |
| `CountDownLatch` 释放 | 9 | `countDown()` 放入 `finally`，避免异常路径造成测试或场景永久等待 |
| 控制语句大括号 | 7 | 为单行分支和循环补齐大括号，降低后续修改引入控制流错误的风险 |
| 过长方法 | 4 | 拆分撮合、完整交易生命周期和故障场景编排，保留原事务入口和金融原子性 |
| 复杂条件 | 4 | 提取有业务含义的布尔条件和选择方法，降低误改风险 |
| lowerCamelCase | 2 | 调整测试方法名，继续用行为语义表达验收目标 |
| 集合初始容量 | 2 | 根据已知输入规模预分配，避免扩容和再散列 |
| 包装类型返回 | 1 | 使用显式数值转换，避免不必要的包装语义 |

迁移扫描先后遇到两类内部错误：旧规则对正则量词 `{2,20}` 的消息模板解析缺陷，以及 PMD 6 DFA
在 MyBatis 注解接口上的迭代失败。资产与交易对校验已集中到预编译的 `TradingIdentifiers`；并发与
事务规则通过前述兼容描述移除失稳的 DFA 元数据。验证脚本会搜索这两类错误特征，防止假通过。

## 4. Java 21 兼容策略

P3C-PMD 2.1.1 内置 PMD 6.15，发布时间早于 Java 21，无法解析项目源码。项目保留 Java 21 编译和
运行基线，并仅把规则引擎替换为 PMD 6 系列最终版 6.55；该解析器的配置入口最高为 Java 20，但能
解析本项目实际使用的 Record、模式匹配和普通 Java 21 源码结构。若将来引入它无法理解的新语法，
构建会因 `skipPmdError=false` 失败，而不会静默跳过。

P3C 2.1.1 的旧数据流引擎在 Spring Boot 3 / Java 21 注解接口上会出现内部状态迭代错误，因此
`ali-concurrent` 与 `ali-exception` 使用仓库内的兼容描述文件：规则名、实现类和优先级保持不变，仅移除
九条并发规则及事务规则的旧 `dfa` 元数据。扫描同时关闭跨类路径类型解析，按原规则实现检查源码语法树；
Java 类型、泛型和依赖正确性由 JDK 21 编译器负责，现代字节码语义缺陷另由 SpotBugs 承担。
这一限制被明确记录，不能把 P3C 结果描述为 Java 21 全语义静态分析。

P3C 的传统线程池规则不知道 Java 21 虚拟线程，把每任务虚拟线程执行器误判为无界平台线程池。
项目没有删除虚拟线程能力，而是把创建集中在 `VirtualTaskExecutors`，只在该工厂调用保留一处带原因的
`PMD.ThreadPoolCreationRule` 定向抑制。撮合和 Kafka 仍使用定长、有界的平台线程池；虚拟线程只承载
短时阻塞 I/O。

## 5. 金融项目附加门禁

P3C 不理解 FinCore 的业务不变量，因此项目另外验证：

- 金额继续使用 `BigDecimal`，不以 `float` 或 `double` 表示资金；
- 余额、不可变账本、状态、Inbox 与 Outbox 保持同一显式事务边界；
- 所有 `@Transactional` 都显式声明受检异常回滚范围；
- 幂等唯一约束、确定性 UUID 锁序、成功终态和 Epoch Fencing 不得弱化；
- 核心热路径注释必须解释解决的问题、CPU/资源机制和正确性边界；
- MyBatis Mapper 禁止 `${...}` 字符串直替参数，生产代码禁止绕开 Mapper 直接使用 Spring JDBC。

这些规则由 `CodeConventionTest`、`scripts/verify-code-conventions.sh`、架构测试和数据库集成测试共同
验证，不以增加注释数量代替业务正确性测试。

测试进程把 Mockito 显式配置为 Java Agent，避免依赖 JDK 运行时动态附加。这样本机受限环境和 CI
使用同一种 Mock 初始化路径，也为后续 JDK 默认关闭动态代理附加做好兼容。

构建还固定 Maven Wrapper 3.9.11，通过 Enforcer 限定 JDK 21 与 Maven 3.9，并在 `verify` 阶段运行
SpotBugs High 级语义缺陷检查和 JaCoCo 行 60%/分支 45% 最低覆盖率。首次构建仍需要从 Maven Central
获取传递依赖；“固定工具链”不等于“源码仓库可完全离线构建”。

## 6. 本地与 CI 复现

执行完整门禁：

```bash
./scripts/run-maven-verify.sh clean verify
```

只运行快速格式、文档和金融事务检查：

```bash
./scripts/verify-code-conventions.sh
```

报告位于 `target/pmd.xml`、`target/site/pmd.html`、`target/spotbugsXml.xml` 与
`target/site/jacoco/`。GitHub Actions 使用 JDK 21 执行相同的 `clean verify`；出现 P3C 违规、源码处理
错误、SpotBugs High 缺陷、覆盖率不足、测试失败或强制数据库测试跳过时均不能通过。

## 7. 审计边界

以下内容仍必须结合人工评审和运行证据，不能从“0 条静态违规”推导：

- SQL 执行计划、索引选择、锁等待与真实数据量下的数据库 CPU；
- p99/p999 延迟、吞吐、GC、虚拟线程 pinned、Lane 排队和容量上限；
- 资金、清算、结算、强平、链上确认等业务规则的完整性；
- 安全威胁模型、密钥托管、生产网络和权限隔离；
- 注释是否与线上行为和最新业务决策持续一致。

参考：[Alibaba P3C 官方仓库](https://github.com/alibaba/p3c)与
[P3C-PMD 模块](https://github.com/alibaba/p3c/tree/master/p3c-pmd)。
