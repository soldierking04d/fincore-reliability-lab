#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$project_root"

git rev-parse --verify main >/dev/null 2>&1 || {
  echo "main branch does not exist; create and verify the main commit first" >&2
  exit 1
}

[[ -z "$(git status --porcelain)" ]] || {
  echo "working tree must be clean before creating benchmark branches" >&2
  exit 1
}

tasks=(
  "FC-001:benchmark/duplicate-settlement"
  "FC-002:benchmark/illegal-status-overwrite"
  "FC-003:benchmark/fee-hot-account"
  "FC-004:benchmark/scale-down-takeover"
  "FC-005:benchmark/duplicate-compensation"
)

for item in "${tasks[@]}"; do
  task_id="${item%%:*}"
  branch="${item#*:}"

  if git show-ref --verify --quiet "refs/heads/${branch}"; then
    echo "branch already exists: ${branch}" >&2
    exit 1
  fi

  git switch main
  git switch -c "$branch"
  git apply "evals/defects/${task_id}.patch"
  git add src
  git commit -m "benchmark(${task_id}): INTENTIONAL BENCHMARK DEFECT"
done

git switch main
echo "Created 5 intentional benchmark branches and returned to main."
