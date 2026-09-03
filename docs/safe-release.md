# 完整验收与项目级安全发布

## 为什么不能只看 BUILD SUCCESS

本机缺少 Docker 时，一部分测试会跳过。完整验收必须在 Docker 可用环境执行：

```bash
mvn -B -Dfincore.test.require-database=true clean verify
java scripts/VerifyTestReports.java
```

第二道门禁检查关键集成套件是否存在，且没有跳过、失败或错误。只跑单元测试可以用于开发反馈，不能据此发布。CI 自动保留 JUnit 报告与本次验证的 JAR。

2026-09-03 的 [CI 证据](https://github.com/soldierking04d/fincore-reliability-lab/actions/runs/33762357631)：92 项执行、0 跳过，包括合约 13 项数据库验收。小规模并发/回滚测试不等于生产吞吐认证。

## 发行包契约

发行包包含 `app.jar`、`web/index.html` 与对应 `_next` 资源、`Dockerfile.release`、`.dockerignore`、
`release.json` 和覆盖所有输入文件的 `SHA256SUMS`。不得包含私钥、Token、开发缓存或数据库备份。

`release.json` 至少记录 releaseId、backendCommit、frontendCommit、ciUrl、databaseVersion 和
tests 的 total/failed/errors/skipped。后端 JAR 来自该 commit 的成功 CI；前端从已通过测试和生产构建的同一提交导出，不手改压缩脚本。

本次后台 migration 仅增量新增 V7 实验表，不改现有现货资金模型。未来如有破坏性迁移，必须重新设计回滚策略，不能沿用这里的假设。

## 安全部署顺序

1. 检查发行包摘要、完整测试、已有健康状态和项目身份，持有发布锁。
2. 保存数据库备份、首页资源备份、旧应用镜像 ID 和其他容器 ID/启动时间。
3. 使用本机已有运行环境构建精确版本镜像；只替换 FinCore `app`，使用 `--no-deps`、`--no-build`、`--pull never`。
4. Nginx 只重载配置以重新解析应用地址，不重启容器；等待应用健康和 V7 成功。
5. 静态资源先到位，首页最后原子替换，旧资源保留。发布说明暴露在同一公网域的 `/release.json`。
6. 比较其他容器 ID/启动时间，确保 PostgreSQL、Kafka、Caddy、监控及同机其他项目均未重启。

发行脚本遇到失败会尝试退回旧应用和首页，并记录 `ROLLED_BACK_CHECK_HEALTH`，操作人员必须继续验证旧版健康。
原数据库备份仅在明确的数据恢复决策下使用，不能自动覆盖发布后交易；自动应用回退不删 V7 表。

回退材料保存在发行目录 `backup/`，不可公开提供下载。基础镜像保留本机 ID，未配置自动清理旧镜像或备份。

## 真实展示与边界

网页真实模式仅接受完整业务报告，包含必要检查、runId 和结果时间；HTTP 200、动画结束或检查条目数量都不能代替成功。
不自动重试写场景。超时属于“结果未知”，后台可能已执行，不伪装为失败回滚或成功。

监控独立读取应用 CPU、业务 HTTP QPS、JVM 堆和数据库连接，失效/缺失时显示缺数。回放曲线与实测分开，价格教学曲线不宣称来自外部真实行情。

仍待后续专项实施：委托资金冻结和双资产交割闭环、完整强平/保险/ADL、Kafka 持久化卷迁移及瓶颈定位、隔离环境压测/恢复演练。日常发布不擅自执行这些高影响改造。
