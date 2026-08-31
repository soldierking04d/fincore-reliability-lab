#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
rubric="${project_root}/evals/rubric.json"
tasks_dir="${project_root}/evals/tasks"
defects_dir="${project_root}/evals/defects"

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

jq -e '.totalPoints == 100' "$rubric" >/dev/null
jq -e '([.dimensions[].points] | add) == .totalPoints' "$rubric" >/dev/null
jq -e '.vetoRules | length >= 5' "$rubric" >/dev/null

# 使用 Bash 3.2 兼容写法，保证 macOS 默认环境也能执行评测包校验。
tasks=()
while IFS= read -r task; do
  tasks+=("$task")
done < <(find "$tasks_dir" -maxdepth 1 -name 'FC-*.json' -type f | sort)
[[ "${#tasks[@]}" -eq 8 ]] || { echo "expected 8 tasks, found ${#tasks[@]}" >&2; exit 1; }

for task in "${tasks[@]}"; do
  jq -e '
    (.id | test("^FC-[0-9]{3}$")) and
    (.title | length > 0) and
    (.defectBranch | startswith("benchmark/")) and
    (.agentPrompt | length > 40) and
    (.publicChecks | length >= 3) and
    (.hiddenTestThemes | length >= 3) and
    (.requiredEvidence | length >= 3)
  ' "$task" >/dev/null
done

duplicate_ids="$(jq -r '.id' "${tasks[@]}" | sort | uniq -d)"
[[ -z "$duplicate_ids" ]] || { echo "duplicate task IDs: ${duplicate_ids}" >&2; exit 1; }

patches=()
while IFS= read -r patch; do
  patches+=("$patch")
done < <(find "$defects_dir" -maxdepth 1 -name 'FC-*.patch' -type f | sort)
[[ "${#patches[@]}" -eq 8 ]] || { echo "expected 8 defect patches, found ${#patches[@]}" >&2; exit 1; }

if git -C "$project_root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  current_branch="$(git -C "$project_root" rev-parse --abbrev-ref HEAD 2>/dev/null ||
    git -C "$project_root" symbolic-ref --short HEAD 2>/dev/null || echo unknown)"
  for patch in "${patches[@]}"; do
    if [[ "$current_branch" == "main" || "$current_branch" == "master" ]]; then
      git -C "$project_root" apply --check "$patch"
    else
      git -C "$project_root" apply --numstat "$patch" >/dev/null
    fi
  done
fi

echo "Evaluation kit valid: 8 tasks, 8 applicable defects, 100-point rubric, unique IDs."
