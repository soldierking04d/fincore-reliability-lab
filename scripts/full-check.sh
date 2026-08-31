#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

./scripts/verify-code-conventions.sh
./scripts/verify-core.sh
java scripts/ValidateProject.java .

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is unavailable; JDK verification passed, container verification skipped." >&2
  exit 2
fi

docker compose --profile test run --rm app-test
docker compose up --build --abort-on-container-exit lab-runner
test -s reports/latest-scenario.json
echo "Full FinCore verification passed"
