#!/usr/bin/env bash
# 此脚本仅用于本次已核验的独立首发，任何已有目录/服务/占用端口都拒绝覆盖。
# 下载阶段和 SSH 主机校验由操作者先完成；失败不影响旧站点，也不自动删除现场。
set -euo pipefail
CHAIN_STAGE=/tmp/fincore-workbench-20260915-AeCBsC
CHAIN_ROOT=/opt/fincore-chain-workbench
CHAIN_RELEASE=/opt/fincore-chain-workbench/releases/20260915-r1
CHAIN_UNIT=/etc/systemd/system/fincore-chain-workbench.service
test "$(id -u)" = 0
test "$(uname -m)" = x86_64
test ! -e "$CHAIN_ROOT" && test ! -L "$CHAIN_ROOT"
test ! -e "$CHAIN_UNIT" && test ! -L "$CHAIN_UNIT"
test -z "$(ss -lntH '( sport = :4399 )')"
printf '%s  %s\n' \
  2f2c0da162318f0de47665410c7c8c2ed3d36c8f3105de4bbc61176c70a7cbf2 "$CHAIN_STAGE/node-v24.20.0-linux-x64.tar.xz" \
  f85a3f281cbab8a87c32d1986df9f5eb949f9d664a574039b573825128948fde "$CHAIN_STAGE/workbench-20260915-r1.tgz" | sha256sum -c -

install -d -m 0755 "$CHAIN_ROOT/runtime" "$CHAIN_ROOT/releases"
tar -xJf "$CHAIN_STAGE/node-v24.20.0-linux-x64.tar.xz" -C "$CHAIN_ROOT/runtime" --strip-components=1 --no-same-owner
"$CHAIN_ROOT/runtime/bin/node" --version
install -d -m 0755 -o ubuntu -g "$(id -gn ubuntu)" "$CHAIN_RELEASE"
install -d -m 0700 -o ubuntu -g "$(id -gn ubuntu)" "$CHAIN_STAGE/npm-cache"
runuser -u ubuntu -- tar -xzf "$CHAIN_STAGE/workbench-20260915-r1.tgz" -C "$CHAIN_RELEASE" --no-same-owner

# 生产依赖按锁文件安装；禁止第三方生命周期脚本，不以 root 运行 npm。
cd "$CHAIN_RELEASE"
runuser -u ubuntu -- env PATH="$CHAIN_ROOT/runtime/bin:/usr/bin:/bin" \
  npm ci --omit=dev --ignore-scripts --no-audit --no-fund --cache "$CHAIN_STAGE/npm-cache"
chown -R root:root "$CHAIN_RELEASE"
chmod -R go-w "$CHAIN_RELEASE"
ln -s "$CHAIN_RELEASE" "$CHAIN_ROOT/current"
install -m 0644 "$CHAIN_STAGE/fincore-chain-workbench.service" "$CHAIN_UNIT"
systemd-analyze verify "$CHAIN_UNIT"

# daemon-reload 只重新读取单元定义；不重启 Docker 或其他正在运行的服务。
systemctl daemon-reload
systemctl enable --now fincore-chain-workbench.service
systemctl show fincore-chain-workbench.service -p ActiveState -p SubState -p MainPID -p User -p MemoryMax -p CPUQuotaPerSecUSec
