#!/usr/bin/env bash
set -euo pipefail

base_url="${FINCORE_BASE_URL:-http://127.0.0.1:8080}"
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
report_path="${FINCORE_REPORT_PATH:-${project_root}/reports/latest-scenario.json}"
temp_report="${report_path}.tmp"

command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

echo "Waiting for FinCore at ${base_url} ..."
until curl -fsS "${base_url}/actuator/health" | jq -e '.status == "UP"' >/dev/null; do
  sleep 2
done

curl -fsS -X POST "${base_url}/lab/scenarios/full" -o "$temp_report"
jq -e '[.checks[] | startswith("PASS")] | all' "$temp_report" >/dev/null
mv "$temp_report" "$report_path"

echo "All reliability checks passed."
jq '{runId, completedAt, checks, totalOpenDifferences}' "$report_path"
echo "Report: ${report_path}"

