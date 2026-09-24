# 网站访问记录（最小实现）

仅为本站了解访问情况，不用于访客身份识别或交易风控。不开公开查询接口，也不将真实 IP、访问记录提交 GitHub。

## 记录范围

- 数据表：`website_page_visit`。每次浏览/公开章节跳转独立 event_id；刷新会产生新事件，不是独立访客人数。
- 服务端接收时间、连接 IP、公开页面/章节、来源域名、浏览器 User-Agent、语言。
- 不保存 Cookie、钱包、账户、请求正文、查询参数、来源完整路径或设备指纹；不调用第三方 IP 地理位置服务。
- 默认保留 30 天，定期有界清理。浏览器禁用脚本、隐私信号、用户关闭记录、请求限流、队列满或数据库故障均可能少计；不能宣称精确 UV。

## 网络与故障边界

浏览器只向同源 `/api/analytics/page-view` 提交；Nginx 为精确路径限流并覆盖访客 IP 头。HTTPS 链路为 Caddy → Nginx → 应用：Nginx 只信任指定 Caddy IP，应用只信任 `FINCORE_ANALYTICS_TRUSTED_PROXIES` 中的 Nginx IP。直接公网 HTTP 的伪造 XFF 不可信。重建任一代理后需重新核对 IP；不因方便扩大为任意网段。

202 表示进入有界队列，不代表已落库。统计没有资金事务承诺，故障时丢弃并计数，不能阻塞主业务；event_id 唯一约束避免同一事件重复入库。上报不自动重试、不携带 Cookie。数据库只有已有管理通道可查，没有访客列表 API。

## 管理查询（仅经 SSH / 数据库管理通道）

```sql
SELECT received_at, client_ip, page_path, referrer_origin, user_agent, language
FROM website_page_visit ORDER BY received_at DESC LIMIT 100;

SELECT date_trunc('day', received_at) AS day, count(*) AS page_views,
       count(DISTINCT client_ip) AS distinct_network_ips
FROM website_page_visit
WHERE received_at >= now() - interval '7 days'
GROUP BY 1 ORDER BY 1;
```

IP 可能代表 VPN、运营商 NAT、公司代理或共享出口；不能据此确定个人身份、实际人数或真实地理位置。只在管理设备查看，不导出为公开展示数据。应用表 30 天保留不自动等于历史数据库备份已清除，备份需按独立保留策略管理。

代理配置参考：[Nginx realip 官方文档](https://nginx.org/en/docs/http/ngx_http_realip_module.html)。配置样例位于 `infra/nginx/visitor-analytics.conf.example`。
