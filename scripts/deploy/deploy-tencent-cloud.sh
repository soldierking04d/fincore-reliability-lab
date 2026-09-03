#!/usr/bin/env bash
set -euo pipefail
# 旧入口改为项目级发布，不再安装软件或重启整机 Docker。
exec bash "$(dirname "$0")/deploy-app-release.sh" "$@"
