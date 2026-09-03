#!/usr/bin/env bash
set -euo pipefail

# 一次性整机初始化会重启 Docker 并修改系统参数；日常发布禁止调用。
if [[ "${FINCORE_ALLOW_HOST_PROVISION:-}" != "true" ]]; then
  echo "此脚本会影响同机所有容器，仅限获准维护窗口。日常发布请使用 deploy-tencent-cloud.sh。" >&2
  exit 2
fi

project_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$project_dir"

if ! sudo -n true 2>/dev/null; then
  echo "需要当前 Ubuntu 用户具备免密码 sudo。" >&2
  exit 1
fi

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
  ca-certificates curl git jq docker.io docker-compose-v2
sudo systemctl enable --now docker

# 腾讯云中国大陆地域访问 Docker Hub 可能超时。轻量应用服务器可通过腾讯云内网使用
# 官方镜像加速地址；保留 daemon.json 中已有的其他配置，避免覆盖用户设置。
docker_daemon_config=/etc/docker/daemon.json
docker_daemon_tmp="$(mktemp)"
trap 'rm -f "$docker_daemon_tmp"' EXIT
if sudo test -s "$docker_daemon_config"; then
  sudo cat "$docker_daemon_config" \
    | jq '. + {"registry-mirrors": (((.["registry-mirrors"] // []) + ["https://mirror.ccs.tencentyun.com"]) | unique)}' \
    >"$docker_daemon_tmp"
else
  printf '%s\n' \
    '{' \
    '  "registry-mirrors": ["https://mirror.ccs.tencentyun.com"]' \
    '}' \
    >"$docker_daemon_tmp"
fi
sudo install -m 0644 "$docker_daemon_tmp" "$docker_daemon_config"
sudo systemctl restart docker

# 4 GB 实例在首次拉取镜像和构建 Java 应用时需要交换空间兜底。
if ! swapon --show=NAME --noheadings | grep -q .; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
  if ! grep -q '^/swapfile ' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
  fi
fi

sudo install -m 0644 /dev/null /etc/sysctl.d/99-fincore-demo.conf
printf '%s\n' \
  'vm.swappiness=10' \
  'vm.vfs_cache_pressure=100' \
  | sudo tee /etc/sysctl.d/99-fincore-demo.conf >/dev/null
sudo sysctl --system >/dev/null

export FINCORE_BIND_ADDRESS=127.0.0.1
export FINCORE_PUBLIC_BASE_URL="${FINCORE_PUBLIC_BASE_URL:-https://124.223.164.254}"

sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml \
  up -d --build postgres kafka app prometheus grafana nginx

# 应用容器重建后地址可能变化；重启 Nginx 让上游名称立即重新解析，避免保留旧容器地址。
sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml restart nginx

deadline=$((SECONDS + 360))
until curl -fsS http://127.0.0.1/health | jq -e '.status == "UP"' >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "FinCore 在 360 秒内没有通过健康检查。" >&2
    sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml ps >&2
    sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml \
      logs --no-color --tail=250 app postgres kafka nginx >&2
    exit 1
  fi
  sleep 4
done

sudo docker compose -f docker-compose.yml -f docker-compose.cloud.yml ps
curl -fsS http://127.0.0.1/health
echo
echo "FinCore 腾讯云演示环境部署完成：${FINCORE_PUBLIC_BASE_URL}/"
