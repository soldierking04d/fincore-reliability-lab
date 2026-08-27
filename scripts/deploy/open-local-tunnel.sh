#!/usr/bin/env bash
set -euo pipefail

key_path="${1:?usage: open-local-tunnel.sh SSH_KEY [SSH_USER] [VM_HOST] [SSH_PORT]}"
ssh_user="${2:-ubuntu}"
vm_host="${3:-127.0.0.1}"
ssh_port="${4:-22}"

exec ssh \
  -i "$key_path" \
  -p "$ssh_port" \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -N \
  -L 127.0.0.1:8080:127.0.0.1:8080 \
  -L 127.0.0.1:9090:127.0.0.1:9090 \
  -L 127.0.0.1:3000:127.0.0.1:3000 \
  "${ssh_user}@${vm_host}"
