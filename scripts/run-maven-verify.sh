#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

build_log="$(mktemp)"
trap 'rm -f "$build_log"' EXIT

set +e
./mvnw --batch-mode "$@" 2>&1 | tee "$build_log"
maven_status=${PIPESTATUS[0]}
set -e

# clean 会删除 target，因此只在 Maven 完成后归档完整日志。
mkdir -p target
cp "$build_log" target/maven-verify.log

if grep -Eq 'aktStatus is NULL|maximum Iterations exceeded|Error while processing .*\.java' "$build_log"; then
  echo "静态分析器出现内部处理错误；即使 Maven 返回成功也拒绝本次构建。" >&2
  exit 1
fi

exit "$maven_status"
