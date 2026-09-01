#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
registry="$project_dir/ai/use-cases.json"

if ! command -v jq >/dev/null 2>&1; then
    echo "[FAIL] AI 用例登记校验需要 jq" >&2
    exit 1
fi

jq empty "$registry"

jq -e '
  .policy.defaultDeny == true and
  .policy.autonomousFinancialWritesAllowed == false and
  .policy.landedRequiresPublicEvidence == true and
  .policy.highRiskRequiresHumanApproval == true and
  .policy.rawProductionDataAllowed == false
' "$registry" >/dev/null

jq -e '
  (.useCases | length) > 0 and
  ([.useCases[].id] | length) == ([.useCases[].id] | unique | length) and
  all(.useCases[];
    (.id | test("^[a-z0-9_]+$")) and
    (.title | length > 0) and
    (.owner | length > 0) and
    (.businessOutcome | length > 0) and
    (.status | IN("proposed", "planned", "shadow", "assisted", "landed", "retired")) and
    (.riskTier | IN("low", "medium", "high", "prohibited")) and
    (.authority | IN("read_only", "isolated_candidate_only", "bounded_tool")) and
    (.allowedInputs | length > 0) and
    (.prohibitedActions | length > 0) and
    (.evaluation.hardVetoes | length > 0) and
    (.fallback | length > 0) and
    (.monitoring | length > 0)
  )
' "$registry" >/dev/null

jq -e '
  all(.useCases[];
    if .riskTier == "high" then .humanApproval.required == true else true end
  ) and
  all(.useCases[];
    if .status == "landed" then (.evaluation.evidence | length > 0) else true end
  ) and
  all(.useCases[];
    .authority != "autonomous_financial_write"
  ) and
  all(.useCases[];
    if (.dataClass == "restricted_financial" or .dataClass == "sensitive_customer")
    then .humanApproval.required == true and .authority == "read_only"
    else true end
  )
' "$registry" >/dev/null

while IFS= read -r evidence; do
    if [[ ! -f "$project_dir/$evidence" ]]; then
        echo "[FAIL] AI 已落地用例缺少证据文件：$evidence" >&2
        exit 1
    fi
done < <(jq -r '.useCases[] | select(.status == "landed") | .evaluation.evidence[]' "$registry")

echo "[PASS] AI 用例登记、资金权限硬边界和公开证据校验通过"
