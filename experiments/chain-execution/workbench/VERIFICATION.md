# 第三阶段实际验收记录

首次本地验收：2026-09-14；腾讯部署及再次验收：2026-09-15（北京时间）。开发基线为 `b29dbff`，下文保留这两日的实际验收证据；提交状态以仓库历史为准。

2026-09-16 入库前再次运行：工作台 107 项自动测试全部通过，前端构建通过；Java 独立模块 121 项测试通过，0 失败、0 错误、0 跳过。此次仅复验代码，不发起钱包连接或链上交易，也没有重新部署腾讯服务；云端 RPC 阻塞仍未解决。

## 已通过的本地检查

| 项目 | 真实结果 |
| --- | --- |
| Java PAPER / READ_ONLY 回归 | 121 项通过，0 失败、0 跳过；Java 21，Maven 离线运行 |
| 工作台 Node 自动测试 | 2026-09-15 全套复跑 107 项通过，0 失败、0 跳过（原有 106 项 + 部署启动回归 1 项） |
| 分类 | DEX 41；RPC 21；HTTP 9；钱包状态与金额转换 35；真实子进程部署启动 1 |
| 前端构建 | esbuild 构建成功；钱包扩展在 Chrome 中真实发现 |
| 钱包连接 | 用户批准 MetaMask 连接后，页面收到原 Solana 公开地址；未签名 |
| 浏览器 BUY | 实际点击预检，页面显示 `SIMULATION_PASSED`，槽位 446999807 |
| 浏览器 SELL | 实际点击预检，页面显示 `SIMULATION_PASSED`，槽位 447000326；易读数量与低收入费用警告正确展示 |
| 断开回归 | 点击断开后公开地址与旧结果清空，预检按钮禁用，无自动恢复 |
| 独立代码审查 | RPC / HTTP / DEX 各自复核，未发现可证实 P1/P2；不等于安全审计认证 |

测试并非全部一次通过。记录的实际修复包括：

1. 钱包监听注册过程中同步触发事件、旧回包读取过程中同步切换账户，两项新增测试先失败，再修复，不删除断言。
2. 真实 RPC `rentEpoch=18446744073709551615` 超出 Number 安全范围。RPC 解析器改为无损整数字符串，HTTP 输入仍拒绝不安全数字。
3. Orca 正常池子的默认奖励管理者可非零，不能误判为已启用奖励扩展；保留其他控制扩展检查和模拟前后字节一致检查。
4. 真实模拟曾返回 `-32602: Too many accounts provided; max 13`。后账户请求改为编译消息实际账户列表的准确顺序；未在消息中的只读快照账户不可被该交易访问，不伪造其模拟后像。
5. HTTP 错误 Host 测试改用原生 HTTP 真正发出目标 Host，避免 fetch 自动规范化请求后误测正常请求。403 断言保留。

## 真实主网模拟数据

固定 FCLAB / WSOL 池：`HUeniRZwa8nSXimfMreuv7MidAoVGMesxLPDssLSyj6c`。

以下为服务内核直接调用官方主网 RPC 的两个独立预检，不是先实际买入再实际卖出。没有使用测试替身代替主网结果。

| 方向 | 输入 | 模拟到账 | 最低到账 | 模拟网络费 | CU | 槽位 |
| --- | --- | --- | --- | --- | --- | --- |
| BUY | 0.0001 SOL | 4,874.895900 FCLAB | 4,850.521420 FCLAB | 0.000005 SOL | 31,743 | 446999613 |
| SELL | 100 FCLAB | 0.000002000 WSOL | 0.000001990 WSOL | 0.000005 SOL | 26,600 | 446999650 |

两次均为 `SIMULATION_PASSED`、`executionAllowed=false`、`evidence=SAME_SIMULATION_PRE_POST`。
网络费、原生余额和四个 Token 账户变化取自同一次模拟响应；输入、输出及池费用等约束通过后才呈现成功。

- BUY 消息 SHA-256：`05bb4a5ffffb2a2bfb5e078003af154bada8d987a213bce7bf99ea3c67e04761`。
- SELL 消息 SHA-256：`052dde0520cf988995670cf41358f95ca05ca7e682e013c892cf6cf11b67e044`。
- SELL 输出低于其网络费，工作台已增加明确警告；模拟可通过不代表有经济收益。
- 本次费用均为**模拟字段**，没有实际扣除。没有广播交易，因此没有交易哈希或链上成交记录可提供。

## 依赖与发布限制

已移除不需要的 SPL Token 依赖及其 `bigint-buffer` 高危依赖链，并将实际使用的 Orca client/core 直接固定版本。

移除 SPL 后 npm 生产依赖审计为：0 critical、0 high、4 moderate（web3.js → jayson → stream-json / uuid 的依赖链）。没有使用 `audit fix --force` 降级 SDK，也没有把审计告警隐藏为通过。当前请求走本模块原生 HTTPS 和严格 JSON 解析器，不使用 web3.js Connection/Jayson 的 RPC 客户端；钱包端 bundle 不包含这些服务端 SDK。公开发布或增加功能前仍须复核可达性及上游修复。

**腾讯云独立服务已安装并运行，但云端真实链上预检尚未通过。** 2026-09-15 找到同机其他已部署项目原有的可信 SSH 主机记录，严格主机校验和用户已有密钥登录成功；之前的浏览器 MFA 阻塞不再是当前问题。没有跳过主机校验或关闭 MFA。

部署目录为 `/opt/fincore-chain-workbench/releases/20260915-r1`，服务为 `fincore-chain-workbench.service`，仅监听 `127.0.0.1:4399`。使用独立 Node 24.20.0、DynamicUser、只读发布目录、512 MiB 内存上限和一个 CPU 核心的时间配额；未改旧站点、数据库、全局网络或开放新公网端口。[部署与私有访问说明](deploy/README.md)。

| 2026-09-15 新验收项 | 实测结果 |
| --- | --- |
| 本地再次 BUY / SELL | 均为 `SIMULATION_PASSED`；槽位分别为 447287085、447287102；`executionAllowed=false` |
| 腾讯服务 | `active/running`；修正启动路径后 `NRestarts=0`；空闲及预检后内存约 35 MiB |
| 腾讯网页、静态脚本、配置 | HTTP 200；通过 SSH 隧道在浏览器确认页面及限额载入，无钱包自动连接 |
| 腾讯错误 Host / 无会话请求 | 分别 HTTP 403 / 403 |
| 腾讯广播路径探测 | `/api/sendTransaction` 返回 404，该接口不存在 |
| 腾讯 BUY / SELL | HTTP 200 携带业务状态 `BLOCKED`，`reasonCode=RPC_UNAVAILABLE`；报价、模拟、余额均为空，执行权限关闭 |
| 旧应用核对 | 部署前后 16 个容器的名称、完整 ID、StartedAt 逐项一致；原网站 HTTP 200 |

云端阻塞的实际证据：服务器系统解析 `api.mainnet.solana.com` 为 `75.126.115.192`，而本机解析和 Google HTTPS DNS 均返回 `216.144.245.51`；服务器正常连接超时，临时使用后者 IP 且保留原 hostname、SNI 与证书校验的只读诊断仍出现 TLS 连接重置。Google / Cloudflare HTTPS DNS 从服务器访问也失败。因此不能归结为只改 DNS 就能修好，具体网络阻断位置尚未确定。

未更换第三方 RPC、未改系统 DNS / 路由、未借用已有量化或行情容器的 VPN 与凭据。HTTP 服务可访问不等于云端交易模拟可用；本次没有把本地模拟证据冒充腾讯云证据。后续需要明确选定可从该主机访问的 RPC 或独立网络出口，再重新验证主网身份、BUY / SELL、请求边界和原有服务。

首次通过 `current/src/server.mjs` 绝对符号链接启动时，Node 的 `argv` 与 ESM 真实路径不一致，入口判断未执行，进程以 0 退出。已将 unit 改为从 `WorkingDirectory` 启动相对入口 `src/server.mjs`，实测服务保持运行；没有把一次 `systemctl start` 成功当作进程持续健康。新增 `test/deployment.test.mjs` 从真实 unit 读取入口，通过临时 current 链接启动子进程并检查 HTTP 配置；旧绝对路径会提前退出，不能靠伪造配置响应通过。测试在子进程拦截 Node HTTP / HTTPS / fetch 出站调用，结束后清理自建进程和临时目录，不访问链上。

## 未执行 / 不宣称完成

- 没有请求钱包签名、广播、转账、买卖、建池、增发或关闭账户。
- 没有读取助记词、私钥、钱包存储或旧辅助页的运行状态。
- 没有真实买卖闭环、自动交易、生产账务接入或资金托管。
- 浏览器账户切换/晚到事件的复杂时序已做离线测试；本轮实际浏览器完成连接、BUY、断开、重连和 SELL，不把离线测试冒充所有钱包品牌实测。
- 本次验收保留原公网首页和其他项目；验收结果与 Git 发布状态分别记录，不因源码入库就视为云端 RPC 已恢复。

## 复验命令

本目录：

```sh
node --test test/*.test.mjs
npm run build
npm start
```

项目根目录（明确使用 Java 21）：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home \
  ../derivatives-test-tools/apache-maven-3.9.11/bin/mvn -o \
  -f experiments/chain-execution/pom.xml test -q
```

本地入口：`http://127.0.0.1:4398/`。程序停止后需要在本目录重新启动，不承诺临时本地进程永久在线。
