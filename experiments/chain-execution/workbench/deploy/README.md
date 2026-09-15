# 腾讯云独立只读工作台：部署与访问

更新时间：2026-09-15。**服务与页面已部署；云端 Solana 官方 RPC 连接失败，真实预检仍被阻断。** 不代表完成实盘交易，也不宣称云端可用性验收完成。详细结果见 [验收记录](../VERIFICATION.md)。

## 两个入口不能混淆

| 地址 | 实际运行位置 | 当前状态 |
| --- | --- | --- |
| `http://127.0.0.1:4398/` | 本人的 Mac | BUY / SELL 再次真实未签名模拟通过；依赖本机服务运行 |
| `http://127.0.0.1:4399/` | SSH 转发至腾讯 `124.223.164.254` | 云端页面正常；RPC 不通，预检返回 BLOCKED；依赖 SSH 隧道 |

4399 不是公开网站地址，也不能替换为 `http://124.223.164.254:4399`：服务器只绑定回环地址。原公开作品集入口不变，本模块不复用它的钱包授权、会话或数据库。钱包连接需使用安装了 MetaMask 等兼容扩展的浏览器；内置预览浏览器没有扩展时，不能发现钱包是预期状态。

访问关系：本机浏览器 → 本机回环 4399 → SSH 加密连接 → 腾讯回环 4399 → 固定官方 Solana RPC。最后一段目前未通；前几段通过不是链上成功。

## 安装落点与边界

- 主机：`ubuntu@124.223.164.254`，SSH 22；x86_64。
- 发布目录：`/opt/fincore-chain-workbench/releases/20260915-r1`。
- 当前链接：`/opt/fincore-chain-workbench/current`。
- 私有运行时：`/opt/fincore-chain-workbench/runtime`，官方 Node 24.20.0；不替换系统 Node。
- 服务：`fincore-chain-workbench.service`，开机启动、失败限次重启；运行于专用 DynamicUser。
- 内存硬上限 512 MiB、V8 堆上限 256 MiB、CPU 配额为一个核心的时间、最多 64 个任务。
- 发布文件 root 持有，服务只读；ProtectHome、PrivateTmp、NoNewPrivileges 生效。不读取旧应用目录或 Docker 套接字。
- 不接入已有 Docker 网络、不重启 Docker、不改全局 DNS、路由、VPN、防火墙、Caddy 或数据库。
- 共享主机及其网络栈；这不是专用虚拟机或独立网络命名空间。SSH 访问也不改变上游节点的可用性。
- 没有私钥、助记词、签名与广播接口。预检不创建账户、不扣除模拟费用。

## 重新建立私有访问

本次使用用户已有 SSH 密钥及此前保存的服务器主机记录，未创建新密钥。已核实 ED25519 主机指纹：

`SHA256:LFqAkb1DzcQm8OOeGw4rtRy9+otRzUEVjdPd1G5G54E`

在本人电脑上，把以下两个变量填为**现有私钥文件**和**已可信保存的 known_hosts 文件**的绝对路径；不要把私钥内容放入命令或聊天。该命令不下载主机密钥，也不会自动信任未知主机。

```sh
CHAIN_SSH_KEY='/absolute/path/to/fincore_tencent.pem'
CHAIN_KNOWN_HOSTS='/absolute/path/to/trusted/known_hosts'
ssh -F /dev/null -N \
  -i "$CHAIN_SSH_KEY" \
  -o IdentitiesOnly=yes -o BatchMode=yes \
  -o StrictHostKeyChecking=yes -o UpdateHostKeys=no \
  -o UserKnownHostsFile="$CHAIN_KNOWN_HOSTS" \
  -o GlobalKnownHostsFile=/dev/null \
  -o ExitOnForwardFailure=yes -o ConnectTimeout=10 \
  -o ServerAliveInterval=30 -o ServerAliveCountMax=3 \
  -L 127.0.0.1:4399:127.0.0.1:4399 \
  ubuntu@124.223.164.254
```

保持该终端运行，再打开 `http://127.0.0.1:4399/`。两端端口必须一致，服务校验精确 Host / Origin；不要改成 `localhost` 或随意反代。如果 4399 已占用，先确认是不是本次已有隧道，不能杀掉未知进程。

停止本人建立的隧道可在该终端按 Ctrl+C；只关闭本机入口，不停止腾讯服务。电脑休眠或断网后隧道可能退出，重新运行即可。不要因此开放公网端口。

## 核验和停止

登录该主机后只检查本服务：

```sh
sudo systemctl status fincore-chain-workbench.service --no-pager
sudo systemctl show fincore-chain-workbench.service -p ActiveState -p SubState -p NRestarts -p MemoryCurrent
ss -lnt '( sport = :4399 )'
curl --fail http://127.0.0.1:4399/
sudo journalctl -u fincore-chain-workbench.service -n 30 --no-pager
```

还必须另行调用 `/api/preview` 并核对业务状态。HTTP 200 下的 `BLOCKED` 是明确阻断，不能视为模拟通过；`SIMULATION_PASSED` 也不是实际交易。

如需撤下本模块，使用 `sudo systemctl disable --now fincore-chain-workbench.service`，只停止本服务，保留发布目录便于复核。不要停止 Docker 或删除整个 `/opt`。修复或升级前先保存明确版本和原服务状态。

## 本次安装可复核性

`install-private-20260915.sh` 是本次**一次性首发记录**，固定了临时目录和制品校验值；已安装时会拒绝覆盖，不能直接当作升级脚本重跑。

| 制品 | SHA-256 |
| --- | --- |
| 官方 Node `node-v24.20.0-linux-x64.tar.xz` | `2f2c0da162318f0de47665410c7c8c2ed3d36c8f3105de4bbc61176c70a7cbf2` |
| `workbench-20260915-r1.tgz` | `f85a3f281cbab8a87c32d1986df9f5eb949f9d664a574039b573825128948fde` |

应用制品仅包含锁文件、三个服务端模块和三个构建后的静态文件，不包含钱包状态、密钥、其他项目、测试数据或 node_modules。生产依赖以普通用户通过锁文件安装，并禁用第三方生命周期脚本。旧有中危依赖告警及其边界仍保留在验收记录，不因部署而视为消除。

下一步需选定且核准一个该服务器实际可访问的 Solana RPC 或独立出口，再做限定应用范围的配置与回归；不关闭 TLS 校验、不伪造节点数据、不自动复用其他项目 VPN，也不把本机持续转发充当稳定云端部署。
