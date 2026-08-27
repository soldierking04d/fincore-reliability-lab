#!/usr/bin/env bash
set -euo pipefail

# Run inside the Ubuntu VM after its UTM network has been isolated from the
# physical LAN. This script deliberately refuses to continue while a DHCP
# server is still listening on UDP/67.

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "This script must run inside the Ubuntu VM." >&2
  exit 1
fi

if ! sudo -n true 2>/dev/null; then
  echo "Passwordless sudo is required." >&2
  exit 1
fi

state_dir="${HOME}/fincore-vm-preflight"
mkdir -p "$state_dir"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"

{
  echo "captured_at=$timestamp"
  uname -a
  systemctl --no-pager --full status dnsmasq.service split-gateway.service 2>&1 || true
  ip -brief address
  ip route
  sudo ss -ulpn
} >"$state_dir/before-$timestamp.txt"

for unit in dnsmasq.service split-gateway.service; do
  if systemctl list-unit-files --no-legend "$unit" 2>/dev/null | grep -q "^$unit"; then
    sudo systemctl disable --now "$unit"
  fi
done

if sudo ss -H -ulpn | awk '{print $5}' | grep -Eq '(^|:)(67)$'; then
  echo "A DHCP server is still listening on UDP/67; refusing to install or deploy." >&2
  sudo ss -ulpn >&2
  exit 2
fi

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
  ca-certificates curl jq git openjdk-17-jdk maven docker.io docker-compose-v2
sudo systemctl enable --now docker
sudo usermod -aG docker "$(id -un)"

{
  echo "captured_at=$timestamp"
  systemctl is-enabled dnsmasq.service split-gateway.service 2>&1 || true
  systemctl is-active dnsmasq.service split-gateway.service 2>&1 || true
  sudo ss -ulpn
  sudo docker version
  sudo docker compose version
  java -version
  mvn -version
} >"$state_dir/after-$timestamp.txt" 2>&1

echo "VM safety preparation completed. Evidence: $state_dir"
