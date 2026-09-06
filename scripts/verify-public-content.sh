#!/usr/bin/env bash
# 公开发布内容门禁：扫描所有已跟踪文本，个人准备材料不进入公开分支。
# 仅三个经过审查的团队管理文档可保留招聘流程用语；强限制仍覆盖这些文件。
set -euo pipefail

common_pattern='面试|追问|备考|背题|答题卡|终面|interview|个人叙事|可信表达|可以这样描述|我已有|我认为|我负责'
strict_pattern='面试追问|面试准备|面试回答|面试高频|面试时|面试官视角|备考|背题|答题卡|终面速记|个人叙事|可信表达|可以这样描述|我已有|我认为|我负责|interview preparation|interview answer'

if [[ "${1:-}" == "--self-test" ]]; then
  for sample in '面试追问与参考答案' '可以这样描述：我已有相关经验' 'Interview preparation guide'; do
    if ! printf '%s\n' "$sample" | grep -Ei "$strict_pattern" >/dev/null; then
      echo "FAIL: private-content fixture was not rejected"
      exit 1
    fi
  done
  for sample in '行情缺口恢复与资金协议' '结构化面试使用相同评分维度' '团队招聘流程与入职计划'; do
    if printf '%s\n' "$sample" | grep -Ei "$strict_pattern" >/dev/null; then
      echo "FAIL: technical or management fixture was incorrectly rejected"
      exit 1
    fi
  done
  echo "PASS: public-content boundary fixtures"
  exit 0
fi

if [[ "$#" -ne 0 ]]; then
  echo "Usage: bash scripts/verify-public-content.sh [--self-test]" >&2
  exit 2
fi

cd "$(git rev-parse --show-toplevel)"

# 路径也属于发布面；即使正文没有关键词，个人材料文件仍不可进入公开分支。
while IFS= read -r -d '' tracked_path; do
  if [[ -f "$tracked_path" ]] && printf '%s\n' "$tracked_path" | grep -Ei 'interview|private-notes|personal-preparation|面试|备考' >/dev/null; then
    printf 'FAIL: private material path: %s\n' "$tracked_path"
    exit 1
  fi
done < <(git ls-files -z)

scan() {
  local pattern="$1"
  shift
  local matches
  local result
  if matches=$(git grep -l -I -i -E "$pattern" -- . \
      ':(exclude)scripts/verify-public-content.sh' "$@"); then
    echo "FAIL: private preparation content detected in public files"
    # 仅输出路径，不将待移除原文重复写入公开 CI 日志。
    printf '%s\n' "$matches"
    return 1
  else
    result=$?
    if [[ "$result" -ne 1 ]]; then
      echo "FAIL: public-content scan could not complete" >&2
      return "$result"
    fi
  fi
}

scan "$strict_pattern"
scan "$common_pattern" \
  ':(exclude)docs/management/07-talent-lifecycle-team-health.md' \
  ':(exclude)docs/management/08-delivery-quality-governance.md' \
  ':(exclude)docs/management/detailed/08-talent-lifecycle-performance-playbook.md'

echo "PASS: public tracked text contains no personal preparation material"
