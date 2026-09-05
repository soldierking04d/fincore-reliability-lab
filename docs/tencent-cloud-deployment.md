# 腾讯云公网演示环境

## 公网地址

本项目的腾讯云轻量应用服务器位于上海，使用 4 核 CPU、4 GB 内存和 40 GB SSD。
服务器实际系统为 Ubuntu 22.04 LTS，项目运行目录为 `/opt/fincore-reliability-lab`。

| 服务 | 公网地址 |
|---|---|
| FinCore 完整项目讲解与腾讯云实时实验 | <https://124.223.164.254/> |
| Spring Boot 健康检查 | <https://124.223.164.254/actuator/health> |
| Grafana 实时监控面板 | <https://124.223.164.254/grafana/d/fincore-overview/fincore-reliability-overview?orgId=1&from=now-15m&to=now&refresh=5s> |
| Prometheus 查询界面 | <https://124.223.164.254/prometheus/> |
| 完整项目讲解网站（Sites 备用入口） | <https://fincore-reliability-demo.soldierking04d.chatgpt.site/> |
| AI Agent 评测网站 | <https://fincore-agent-benchmark.soldierking04d.chatgpt.site/> |

公网 HTTPS 由 Caddy 自动签发和续期证书，再转发给 Nginx；HTTP IP 入口仍保留用于兼容访问。
PostgreSQL、Kafka、Spring Boot、Prometheus 和 Grafana 的宿主机端口
均只绑定 `127.0.0.1`，数据库与消息队列不会直接暴露到互联网。

## 4 GB 演示模式

云端环境使用 `docker-compose.cloud.yml` 叠加低内存限制：

- Spring Boot 容器上限 1100 MB，Java 21 G1 根据容器限额计算堆大小；
- Kafka 堆限制为 512 MB，日志仅保留 24 小时；
- PostgreSQL 使用 128 MB `shared_buffers` 和最多 30 个连接；
- 应用使用 2 条撮合 Lane、2 个 Kafka 消费线程和 6 个数据库连接；
- Prometheus 只保留 24 小时或 256 MB 指标；
- 服务器配置 2 GB Swap，`swappiness=10`，仅用于构建和瞬时内存峰值兜底。

这些参数用于功能演示和有限并发验证，不代表生产容量结论。k6 压测应从外部机器发起，不能与服务端
容器争抢同一台 4 GB 实例的 CPU 和内存。

## 公网安全边界

- Caddy 公开 TCP/443，Nginx 保留 TCP/80；SSH 使用腾讯云密钥登录；
- Nginx 与应用内 `public-demo` 过滤器同时限制访问；`/api/` 仅允许匿名读取，Actuator 仅公开健康摘要；
- `/lab/faults/**` 等任意故障注入入口禁止公网访问；
- 仅公开“完整资金实验”“市场暴跌日”“用户到撮合完整链路”和“九域覆盖”四个固定场景，并分别限制启动频率；
- 数据库密码和管理令牌只保存在服务器权限为 `0600` 的 `.env` 中，不写入仓库和 URL；
- Grafana 使用匿名只读 Viewer，Prometheus 仅提供查询；
- 项目使用完全虚构的账户与交易数据，不承载真实资金。

当前 HTTPS 地址直接使用公网 IPv4，并由 Caddy 申请和自动续期 Let's Encrypt 的短期
IP 证书，不依赖额外购买域名，也不会触发腾讯云对未备案域名的拦截。正式生产环境仍应
接入自有域名、完成所需备案并把 `FINCORE_PUBLIC_BASE_URL` 更新为正式域名。

## 上线验收记录

2026-09-01 首次上线完成后执行了真实“市场暴跌日”场景，验收运行号为 `2445e4e6`：

- 最终状态 `RECOVERED`，10 项业务与资金校验全部为 `PASS`；
- 12 路并发卖单形成 60 条唯一成交，17 次重复结算投递只产生一次资金效果；
- 生成 6 笔平衡账本事务，修复 2 条异常投影并隔离 1 条幽灵成交；
- Outbox 的 132 条累计事件全部进入 `PUBLISHED`，不存在待发布或处理中记录；
- Prometheus 抓取目标为 `UP`，Grafana 已显示 QPS、延迟、CPU、JVM、数据库连接、
  撮合背压、Outbox 和结算指标；
- 七个容器均无 OOM，宿主机稳定状态仍有约 2.1 GB 可用内存，Swap 基本未使用。

该记录证明本次部署的链路和观测面可以工作，不应解释为生产容量或可用性认证。

2026-09-02 已把完整技术负责人作品集同步到腾讯云首页，业务架构、服务拓扑、核心时序图、
高并发监控、管理实践、AI 落地和区块链可靠性内容均可通过同一公网入口访问。部署后再次执行
真实验收：完整交易链路运行号为 `0a9bde04`，8 项检查全部通过；市场暴跌日运行号为
`091b20ea`，10 项检查全部通过并恢复到 `RECOVERED`。Spring Boot 健康检查、Grafana 和
前端静态资源同时验证可用。

2026-09-03 发布 `20260903-9566aae-9591a93`，后端 `9566aae`、前端 `9591a93`，数据库升级至 V7。
后端完整 CI 执行 92 项、0 跳过，前端契约测试 13 项通过。公网首页默认实测模式，教学回放独立切换；
真实场景报告必须有完整检查和运行编号，监控缺数不再补成成功或预设数值。
本次市场实验运行号 `4f29cf8d`，10 项通过；盘前到撮合运行号 `3528f27e`，8 项通过。
仅替换 FinCore 应用，其他 11 个容器 ID 和启动时间保持不变；发布期间应用启动存在短暂不可用，不能宣称零停机。
详见[发布验收记录](release-acceptance-2026-09-03.md)，线上精确版本见 [release.json](https://124.223.164.254/release.json)。

## 部署与更新

日常发布与整机初始化已分离。`deploy-tencent-cloud.sh` 现在只转入项目级发行脚本；不安装软件，
不重启 Docker，不改全局网络、Swap 或其他项目。详见 [安全发布与验收](safe-release.md)。

先准备验收通过、带有完整性校验的发行目录，再在服务器执行（用实际发行目录替换占位路径）：

```bash
sudo bash /opt/fincore-reliability-lab/scripts/deploy/deploy-tencent-cloud.sh \
  /opt/fincore-releases/实际发行编号
```

`provision-tencent-host.sh` 只用于首次整机初始化，默认拒绝执行。它会安装软件、配置镜像加速、
修改系统参数并重启 Docker，必须单独安排获准维护窗口，不能用于已有共享服务器的普通更新。

查看服务与日志：

```bash
sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml ps
sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml logs --tail=200 app
```
