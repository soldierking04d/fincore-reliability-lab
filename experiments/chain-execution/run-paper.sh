#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd "$(dirname "$0")" && pwd)"
paper_maven="${FINCORE_PAPER_MAVEN:-$module_dir/../../mvnw}"
if [[ $# -gt 1 ]]; then
  echo "仅接受一个新的本地输出目录；不支持主网模式" >&2
  exit 2
fi
if [[ -n "${JAVA_HOME:-}" ]]; then paper_java="$JAVA_HOME/bin/java"; else paper_java="$(command -v java)"; fi
if ! "$paper_java" -version 2>&1 | head -n 1 | grep -Eq 'version "21[."]'; then
  echo "请先选择 JDK 21；本模块不使用系统 Java 8" >&2
  exit 2
fi
"$paper_maven" -B -q -f "$module_dir/pom.xml" test dependency:copy-dependencies -DincludeScope=runtime
paper_output="${1:-$(mktemp -d "${TMPDIR:-/tmp}/fincore-paper.XXXXXX")}"
"$paper_java" -cp "$module_dir/target/classes:$module_dir/target/dependency/*" dev.fincore.chain.PaperScenarioMain "$paper_output"
