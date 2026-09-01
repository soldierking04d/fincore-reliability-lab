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
    (.modelContext.providers | length > 0) and
    (.modelContext.modelVersions | length > 0) and
    (.modelContext.promptVersion | length > 0) and
    (.modelContext.retrievalVersion | length > 0) and
    (.modelContext.toolPolicyVersion | length > 0) and
    (.modelContext.dataResidency | length > 0) and
    (.modelContext.retention | length > 0) and
    (.modelContext.trainingUse == "not_authorized") and
    (.valueBaseline.currentProcess | length > 0) and
    (.valueBaseline.targetOutcome | length > 0) and
    (.valueBaseline.unitCostCeiling | length > 0) and
    (.valueBaseline.measurementWindow | length > 0) and
    (.releaseGate.minimumThresholds | length > 0) and
    (.releaseGate.lastReviewed | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.releaseGate.nextReview | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.releaseGate.expiresAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.releaseGate.nextReview > .releaseGate.lastReviewed) and
    (.releaseGate.expiresAt >= .releaseGate.nextReview) and
    (.toolAccess.allowedTools | length > 0) and
    (.toolAccess.deniedScopes | length > 0) and
    (.toolAccess.killSwitchOwner | length > 0) and
    (.governance.legalPrivacyReview | length > 0) and
    (.governance.securityReview | length > 0) and
    (.governance.exitOwner | length > 0) and
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
    if .status == "landed" then
      all(.modelContext.modelVersions[]; test("must_be|not_selected") | not) and
      (.modelContext.promptVersion | test("must_be|not_selected") | not) and
      (.governance.legalPrivacyReview | test("required_before") | not) and
      (.governance.securityReview | test("required_before") | not)
    else true end
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

today="$(date +%F)"
jq -e --arg today "$today" '
  all(.useCases[]; .releaseGate.expiresAt >= $today)
' "$registry" >/dev/null || {
    echo "[FAIL] AI 用例发布门禁已经过期，必须复查后更新 expiresAt" >&2
    exit 1
}

while IFS= read -r evidence; do
    if [[ ! -f "$project_dir/$evidence" ]]; then
        echo "[FAIL] AI 已落地用例缺少证据文件：$evidence" >&2
        exit 1
    fi
done < <(jq -r '.useCases[] | select(.status == "landed") | .evaluation.evidence[]' "$registry")

echo "[PASS] AI 用例登记、模型上下文、发布阈值、关闭开关和公开证据校验通过"
