#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
governance_dir="$project_dir/governance"
services="$governance_dir/services.yaml"
risks="$governance_dir/risks.yaml"
metrics="$governance_dir/metrics.yaml"
radar="$governance_dir/technology-radar.yaml"
audit="$governance_dir/audit-evidence.yaml"

fail() {
    echo "[FAIL] $1" >&2
    exit 1
}

if ! command -v jq >/dev/null 2>&1; then
    fail "治理台账校验需要 jq"
fi

for file in "$services" "$risks" "$metrics" "$radar" "$audit" "$governance_dir"/schemas/*.json; do
    [[ -f "$file" ]] || fail "缺少治理文件：$file"
    jq empty "$file" || fail "治理文件不是有效的 JSON 兼容 YAML：$file"
done

jq -e '
  .registryType == "service_catalog" and
  (.services | length) > 0 and
  ([.services[].serviceId] | length) == ([.services[].serviceId] | unique | length) and
  all(.services[];
    (.serviceId | test("^[a-z0-9-]+$")) and
    (.name | length > 0) and (.description | length > 0) and
    (.owner | length > 0) and (.deputy | length > 0) and (.owner != .deputy) and
    (.criticality | IN("low", "medium", "high", "critical")) and
    (.lifecycle | IN("experimental", "operational", "deprecated", "retired")) and
    (.capabilities | length > 0) and (.repoPaths | length > 0) and
    (.dataClasses | length > 0) and (.runbooks | length > 0) and
    (.slo.correctnessObjectives | length > 0) and
    (.lastReviewed | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview > .lastReviewed)
  ) as $shape |
  ([.services[].serviceId] as $ids |
    all(.services[].dependencies[]?; IN($ids[]))) as $references |
  $shape and $references
' "$services" >/dev/null || fail "服务目录字段、日期、Owner/Deputy 或依赖引用不合法"

jq -e --slurpfile serviceRegistry "$services" --slurpfile auditRegistry "$audit" '
  ($serviceRegistry[0].services | map(.serviceId)) as $serviceIds |
  ($auditRegistry[0].controls | map(.controlId)) as $controlIds |
  .registryType == "enterprise_risk_register" and
  (.risks | length) > 0 and
  ([.risks[].riskId] | length) == ([.risks[].riskId] | unique | length) and
  all(.risks[];
    (.riskId | test("^RISK-[0-9]{3}$")) and
    (.title | length > 0) and (.scenario | length > 0) and (.owner | length > 0) and
    (.status | IN("active", "monitored", "accepted", "closed")) and
    (.probability >= 1 and .probability <= 5) and
    (.impact >= 1 and .impact <= 5) and (.score == (.probability * .impact)) and
    (.linkedServices | length > 0) and all(.linkedServices[]; IN($serviceIds[])) and
    (.controls | length > 0) and all(.controls[]; IN($controlIds[])) and
    (.evidence | length > 0) and (.treatment | length > 0) and
    (.dueDate | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.lastReviewed | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview > .lastReviewed) and
    (if .status == "accepted"
      then ((.acceptedBy // "") | length > 0) and
           ((.acceptanceExpiresAt // "") | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
      else .acceptedBy == null and .acceptanceExpiresAt == null
     end)
  )
' "$risks" >/dev/null || fail "风险登记字段、评分、接受期限或跨台账引用不合法"

jq -e '
  .registryType == "metric_catalog" and
  (.metrics | length) > 0 and
  ([.metrics[].metricId] | length) == ([.metrics[].metricId] | unique | length) and
  all(.metrics[];
    (.metricId | test("^[a-z0-9_]+$")) and
    (.version | test("^v[0-9]+$")) and
    (.name | length > 0) and (.purpose | length > 0) and
    (.owner | length > 0) and (.steward | length > 0) and
    (.definition | length > 0) and (.source | length > 0) and
    (.authority | IN("authoritative", "derived", "demonstration")) and
    (.qualityRules | length > 0) and (.thresholds | length > 0) and
    (.dimensions | length > 0) and (.consumers | length > 0) and
    (.lastReviewed | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview > .lastReviewed) and
    (if .demoOnly then .authority == "demonstration" and .source == "website_replay_telemetry"
     else .authority != "demonstration" end)
  )
' "$metrics" >/dev/null || fail "指标目录字段、口径、日期或演示数据边界不合法"

jq -e '
  .registryType == "technology_radar" and
  (.technologies | length) > 0 and
  ([.technologies[].technologyId] | length) == ([.technologies[].technologyId] | unique | length) and
  all(.technologies[];
    (.technologyId | test("^TECH-[0-9]{3}$")) and
    (.name | length > 0) and (.owner | length > 0) and
    (.ring | IN("adopt", "trial", "assess", "hold")) and
    (.problem | length > 0) and (.decision | length > 0) and
    (.evidence | length > 0) and (.risks | length > 0) and
    (.constraints | length > 0) and (.successCriteria | length > 0) and
    (.exitCriteria | length > 0) and
    (.lastReviewed | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview > .lastReviewed) and
    (if .ring == "hold" then (.constraints | length > 0) and (.exitCriteria | length > 0) else true end)
  )
' "$radar" >/dev/null || fail "技术雷达字段、证据、退出条件或日期不合法"

jq -e --slurpfile serviceRegistry "$services" --slurpfile riskRegistry "$risks" '
  ($serviceRegistry[0].services | map(.serviceId)) as $serviceIds |
  ($riskRegistry[0].risks | map(.riskId)) as $riskIds |
  .registryType == "audit_evidence_catalog" and
  (.controls | length) > 0 and
  ([.controls[].controlId] | length) == ([.controls[].controlId] | unique | length) and
  all(.controls[];
    (.controlId | test("^CTRL-[A-Z]+-[0-9]{3}$")) and
    (.objective | length > 0) and (.owner | length > 0) and
    (.frequency | length > 0) and (.testMethod | length > 0) and
    (.status | IN("planned", "implemented", "demonstrated", "independently_verified")) and
    (.linkedServices | length > 0) and all(.linkedServices[]; IN($serviceIds[])) and
    all(.linkedRisks[]?; IN($riskIds[])) and
    (.evidence | length > 0) and (.retention | length > 0) and (.limitations | length > 0) and
    (.lastVerified | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")) and
    (.nextReview > .lastVerified)
  )
' "$audit" >/dev/null || fail "审计证据字段、日期或跨台账引用不合法"

while IFS= read -r evidence; do
    [[ -e "$project_dir/$evidence" ]] || fail "风险登记引用的证据不存在：$evidence"
done < <(jq -r '.risks[].evidence[]' "$risks")

while IFS= read -r evidence; do
    [[ -e "$project_dir/$evidence" ]] || fail "Adopt 技术引用的证据不存在：$evidence"
done < <(jq -r '.technologies[] | select(.ring == "adopt") | .evidence[]' "$radar")

while IFS= read -r evidence; do
    [[ -e "$project_dir/$evidence" ]] || fail "已证明控制引用的证据不存在：$evidence"
done < <(jq -r '.controls[] | select(.status == "demonstrated" or .status == "independently_verified") | .evidence[]' "$audit")

echo "[PASS] 五份治理台账、跨文件引用、复查日期和证据边界校验通过"
