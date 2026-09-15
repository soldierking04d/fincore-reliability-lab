#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd "$(dirname "$0")" && pwd)"
paper_maven="${FINCORE_PAPER_MAVEN:-$module_dir/../../mvnw}"
if [[ $# -lt 1 || $# -gt 2 || ( "$1" != "mainnet-readonly" && "$1" != "devnet-probe" ) ]]; then
  echo "用法：bash run-readonly.sh mainnet-readonly|devnet-probe [全新空输出目录]；不支持签名或广播" >&2
  exit 2
fi
if [[ -n "${JAVA_HOME:-}" ]]; then paper_java="$JAVA_HOME/bin/java"; else paper_java="$(command -v java)"; fi
if ! "$paper_java" -version 2>&1 | head -n 1 | grep -Eq 'version "21[."]'; then
  echo "请先选择 JDK 21" >&2
  exit 2
fi
"$paper_maven" -B -q -f "$module_dir/pom.xml" test dependency:copy-dependencies -DincludeScope=runtime
readonly_output="${2:-$(mktemp -d "${TMPDIR:-/tmp}/fincore-readonly.XXXXXX")}"
"$paper_java" -Djdk.httpclient.disableRetryConnect=true -Djdk.httpclient.enableAllMethodRetry=false \
  -Djdk.httpclient.redirects.retrylimit=1 \
  -cp "$module_dir/target/classes:$module_dir/target/dependency/*" \
  dev.fincore.chain.readonly.ReadOnlyScenarioMain "$1" "$readonly_output"
