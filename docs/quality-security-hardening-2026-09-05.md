# 质量、错误语义与公网演示加固记录

本文记录 2026-09-05 的工程加固。目标不是增加“生产级”宣传，而是补上会让现有实验结论失真的
质量门禁、错误分类、消息确认和公网最小权限边界。资金精度、账本不可变、幂等唯一约束、事务边界、
UUID 锁序、成功终态与 Epoch Fencing 均未放宽。

## 1. 变更结论

| 风险 | 本轮处理 | 直接证据 |
| --- | --- | --- |
| 反向代理误配后写接口暴露 | `public-demo` Profile 增加应用内白名单；匿名仅能读业务数据、健康、Prometheus，并启动四个固定场景 | `PublicDemoAccessFilterTest` |
| 故障注入误上公网 | `LabFaultController` 仅在 `lab & !public-demo` 创建；即使管理令牌正确也不能在公网 Profile 加载 | Spring Profile 声明、控制器测试 |
| Actuator 暴露过宽 | 公网只暴露 health 与 prometheus，健康详情不公开；Nginx 精确允许 `/actuator/health`，其余 Actuator 路径拒绝 | `application-public-demo.yml`、`infra/nginx/cloud.conf` |
| 业务冲突与内部异常混为一类 | 参数错误返回 400；确定性权威状态冲突返回不可重试 409；未分类状态异常返回脱敏 500 和可关联 `errorId` | `BusinessConflictException`、`ApiExceptionHandlerTest` |
| 按异常文本判断旧 Epoch | 引入 `FenceRejectedException`；Listener 只对该类型失效 Lease，不再匹配消息前缀 | `SettlementListenerTest` |
| Kafka 发送调用即报告成功 | 故障场景收集全部发送 Future，并在有界超时内等待 broker 确认；失败整体上抛 | `LabFaultControllerTest` |
| 默认密码进入云环境 | 云 Compose 强制要求数据库密码和高熵管理令牌；`.env.example` 只保留占位符 | `docker-compose.cloud.yml` |
| Topic 依赖自动创建 | Kafka 禁止自动建 Topic，由一次性初始化容器显式创建四个 Topic 和固定分区数 | `docker-compose.yml` |
| 容器标签漂移 | JDK、Maven、PostgreSQL、Kafka、Nginx、Caddy、curl 镜像固定到精确版本 | Dockerfile 与 Compose 文件 |
| 构建工具和静态分析漂移 | Maven Wrapper 固定 3.9.11；Enforcer 限定 JDK 21/Maven 3.9；加入 SpotBugs 与 JaCoCo 门禁 | `pom.xml`、`.mvn/wrapper/` |
| 旧 P3C 内部错误却退出 0 | 兼容描述文件移除失稳的 PMD 6 DFA 元数据，验证脚本额外扫描内部错误文本并强制失败 | `config/pmd/`、`scripts/run-maven-verify.sh` |

## 2. 公网访问契约

`public-demo` 不是完整生产认证系统，而是公开作品集的第二道安全边界：

- 匿名 `GET`/`HEAD /api/**`：读取虚构演示数据；
- 匿名 `GET /actuator/health` 与 `/actuator/prometheus`：健康和监控采集；
- 匿名 `POST`/`OPTIONS`：只允许四个固定、已有全局限流的综合场景；
- 其他请求：必须携带 `X-FinCore-Admin-Token`；
- 故障注入控制器：在 `public-demo` 下根本不创建，不存在令牌绕过入口。

管理令牌只从 `FINCORE_ADMIN_TOKEN` 注入，不放入 Git、URL、响应或日志。固定白名单是演示用途的
最小权限措施，不替代生产系统的身份、角色、租户、审计、密钥轮换和 WAF。

## 3. 静态分析的真实边界

P3C-PMD 2.1.1 公开实现 54 条规则，发布时间早于 Java 21。项目使用 PMD 6.55 解析当前源码，保留
十个规则类别和全部规则实现；仅在并发与事务规则描述中移除会导致注解接口内部迭代失败的旧 DFA
元数据。JDK 21 编译器负责类型正确性，SpotBugs 负责现代字节码语义缺陷，二者不能被 P3C 的
“0 违规”替代。

JaCoCo 最低门槛为行覆盖 60%、分支覆盖 45%。没有 Docker 时，Testcontainers 集成测试会被跳过，
本机结果不能作为完整门禁证据；CI 和正式验收必须设置 `fincore.test.require-database=true`，并由
`VerifyTestReports` 拒绝任何跳过测试。

## 4. 可复现构建边界

仓库固定构建器和直接运行镜像版本，但首次构建仍要从 Maven Central 与容器仓库下载传递依赖。
这意味着当前达到“相同源码与固定入口”的可复现基线，尚未达到依赖制品全量镜像、哈希锁定、
SBOM 签名和完全离线重建。后续供应链增强应包括依赖代理、镜像 digest、SBOM、签名和漏洞门禁。

## 5. 验证入口

```bash
./scripts/verify-core.sh
./scripts/verify-code-conventions.sh
./scripts/run-maven-verify.sh -Dfincore.test.require-database=true clean verify
java scripts/VerifyTestReports.java
```

报告输出到 `target/maven-verify.log`、`target/pmd.xml`、`target/spotbugsXml.xml`、
`target/site/jacoco/` 与 `target/surefire-reports/`。只有完整命令和跳过检查都通过，才能把对应提交标为
**VERIFIED**；线上运行数据还必须记录提交、机器、持续时间、负载模型和原始报告，才能标为
**OBSERVED**。

### 5.1 本轮完整验收结果

2026-09-05 在腾讯云隔离临时目录和 Docker 29.1.3 环境执行了完整命令；该目录与线上运行目录、
数据库卷及应用容器分离。结果如下：

| 检查项 | 结果 | 证据等级 |
| --- | ---: | --- |
| JUnit/Testcontainers | 152 项，0 失败、0 错误、0 跳过 | VERIFIED |
| JaCoCo 行覆盖 | 3463 / 4066，85.17% | VERIFIED |
| JaCoCo 分支覆盖 | 1024 / 1692，60.52% | VERIFIED |
| P3C-PMD | 0 违规、0 已知内部处理错误 | VERIFIED |
| SpotBugs High/Max | 0 Bug、0 Error | VERIFIED |
| 基础与云端 Compose 解析 | 均通过，云端必填密钥使用一次性占位值 | VERIFIED |

原始 Maven 日志、JUnit XML、JaCoCo HTML/XML/CSV、PMD XML 和 SpotBugs XML 留存在本地
`target/remote-verification-2026-09-05/`；`target` 已被 Git 忽略，不把体积较大的机器产物提交到仓库。
GitHub Actions 会对提交后的源码重新执行同一门禁并保存构建产物。

## 6. 尚未由本轮证明的内容

- 未因增加 JVM 参数而宣称生产 QPS、p99/p999 或 GC SLA；
- 未因使用虚拟线程而宣称 CPU 吞吐必然提高；
- 未因固定容器版本而宣称完成供应链签名与漏洞治理；
- 未因应用层令牌而宣称达到生产多租户鉴权；
- 未把合约、链上、GPU/FPGA、NUMA 或内核旁路设计写成已运行能力。

这些项目仍需要目标硬件、真实负载模型、安全评审和可归档报告。
