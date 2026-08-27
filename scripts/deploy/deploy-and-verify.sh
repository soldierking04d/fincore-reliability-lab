#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$project_dir"

if docker info >/dev/null 2>&1; then
  docker_cmd=(docker)
elif sudo -n docker info >/dev/null 2>&1; then
  docker_cmd=(sudo docker)
else
  echo "Docker is unavailable or not running." >&2
  exit 1
fi

compose=("${docker_cmd[@]}" compose)
report="reports/vm-deployment-scenario.json"
mkdir -p reports

./scripts/verify-core.sh
java scripts/ValidateProject.java .

"${compose[@]}" --profile test run --rm app-test
"${compose[@]}" up -d --build postgres kafka app prometheus

deadline=$((SECONDS + 240))
until curl -fsS http://127.0.0.1:8080/actuator/health | jq -e '.status == "UP"' >/dev/null; do
  if (( SECONDS >= deadline )); then
    echo "FinCore did not become healthy within 240 seconds." >&2
    "${compose[@]}" ps >&2
    "${compose[@]}" logs --no-color --tail=250 app postgres kafka >&2
    exit 1
  fi
  sleep 3
done

curl -fsS -X POST http://127.0.0.1:8080/lab/scenarios/full >"$report"
jq -e '
  (.checks | length >= 5) and
  (all(.checks[]; startswith("PASS"))) and
  (.totalOpenDifferences >= 1)
' "$report" >/dev/null

"${compose[@]}" ps
echo "FinCore VM deployment and verification passed. Report: $report"
