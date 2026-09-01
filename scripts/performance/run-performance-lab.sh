#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$project_dir"

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "docker daemon is not running" >&2; exit 1; }

mkdir -p reports/performance reports/runtime
docker compose up -d --build postgres kafka app prometheus grafana

deadline=$((SECONDS + 240))
until curl -fsS http://127.0.0.1:8080/actuator/health | jq -e '.status == "UP"' >/dev/null; do
  if (( SECONDS >= deadline )); then
    docker compose logs --no-color --tail=200 app postgres kafka >&2
    exit 1
  fi
  sleep 2
done

docker compose --profile performance run --rm performance-runner

metrics=(
  process.cpu.usage
  system.cpu.usage
  jvm.memory.used
  jvm.gc.pause
  hikaricp.connections.active
  hikaricp.connections.pending
  fincore.matching.queue.depth.total
  fincore.matching.queue.rejected
  fincore.outbox.ready.backlog
)
snapshot="reports/performance/latest-actuator-snapshot.json"
snapshot_next="${snapshot}.next"
printf '{}\n' >"$snapshot"
for metric in "${metrics[@]}"; do
  curl -fsS "http://127.0.0.1:8080/actuator/metrics/${metric}" |
    jq --arg name "$metric" --slurpfile existing "$snapshot" \
      '$existing[0] + {($name): .}' >"$snapshot_next"
  mv "$snapshot_next" "$snapshot"
done

echo "Performance lab passed. Reports: reports/performance/"
echo "Grafana: http://127.0.0.1:3000"
