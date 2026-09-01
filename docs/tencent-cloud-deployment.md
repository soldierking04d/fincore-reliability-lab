# 腾讯云公网演示环境

## 公网地址

本项目的腾讯云轻量应用服务器位于上海，使用 4 核 CPU、4 GB 内存和 40 GB SSD。
服务器实际系统为 Ubuntu 22.04 LTS，项目运行目录为 `/opt/fincore-reliability-lab`。

| 服务 | 公网地址 |
|---|---|
| FinCore 云端入口与实时实验 | <https://124.223.164.254/> |
| Spring Boot 健康检查 | <https://124.223.164.254/actuator/health> |
| Grafana 实时监控面板 | <https://124.223.164.254/grafana/d/fincore-overview/fincore-reliability-overview?orgId=1&from=now-15m&to=now&refresh=5s> |
| Prometheus 查询界面 | <https://124.223.164.254/prometheus/> |
| 完整项目讲解网站 | <https://fincore-reliability-demo.soldierking04d.chatgpt.site/> |
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
- `/api/` 和 `/actuator/` 的公网访问仅允许读取；
- `/lab/faults/**` 等任意故障注入入口禁止公网访问；
- 仅公开“完整实验”和“市场暴跌日”两个固定场景，且全局限制为每分钟最多启动一次；
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
- 六个容器均无 OOM，宿主机稳定状态仍有约 2.1 GB 可用内存，Swap 基本未使用。

该记录证明本次部署的链路和观测面可以工作，不应解释为生产容量或可用性认证。

## 部署与更新

部署脚本会配置腾讯云官方 Docker 镜像加速地址
`https://mirror.ccs.tencentyun.com`，解决中国大陆地域访问 Docker Hub 容易超时的问题；如果
`/etc/docker/daemon.json` 已有其他配置，脚本会合并保留。

云端镜像构建还会使用腾讯云内网 Maven 仓库下载 Java 依赖；普通本地构建仍使用 Maven
默认仓库，不受云端配置影响。

在服务器项目目录执行：

```bash
cd /opt/fincore-reliability-lab
git pull --ff-only
FINCORE_PUBLIC_BASE_URL=https://124.223.164.254 \
  ./scripts/deploy/deploy-tencent-cloud.sh
```

查看服务与日志：

```bash
sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml ps
sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml logs --tail=200 app
```
