#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$script_dir/.."

status=0

report_matches() {
    local title="$1"
    local pattern="$2"
    shift 2
    local result
    result="$(rg -n "$pattern" "$@" || true)"
    if [[ -n "$result" ]]; then
        echo "[FAIL] $title"
        echo "$result"
        status=1
    fi
}

report_matches "禁止 Java 通配符导入" \
    '^import .+\.\*;' src/main/java src/test/java
report_matches "禁止 Java 源码包含 Tab" \
    $'\t' src/main/java src/test/java
report_matches "控制语句必须使用大括号" \
    '^\s*(if|for|while).*\)\s+(break|continue|return|throw|[A-Za-z_][A-Za-z0-9_]*\+\+|[A-Za-z_][A-Za-z0-9_]*--)' \
    src/main/java
report_matches "生产代码禁止直接使用 Spring JDBC" \
    'JdbcTemplate|NamedParameterJdbcTemplate' src/main/java
report_matches "MyBatis Mapper 禁止字符串直替参数" \
    '\$\{' src/main/java/dev/fincore/infrastructure/persistence/mapper

while IFS= read -r source_file; do
    if ! rg -q '/\*\*' "$source_file"; then
        echo "[FAIL] 缺少 Javadoc：$source_file"
        status=1
    fi
    if ! rg -q '@author' "$source_file"; then
        echo "[FAIL] 缺少 @author：$source_file"
        status=1
    fi
    if ! rg -q '@since' "$source_file"; then
        echo "[FAIL] 缺少 @since：$source_file"
        status=1
    fi
done < <(rg --files src/main/java -g '*.java' -g '!package-info.java' | sort)

for package_dir in src/main/java/dev/fincore \
                   src/main/java/dev/fincore/application \
                   src/main/java/dev/fincore/domain \
                   src/main/java/dev/fincore/infrastructure \
                   src/main/java/dev/fincore/infrastructure/persistence \
                   src/main/java/dev/fincore/infrastructure/persistence/mapper \
                   src/main/java/dev/fincore/messaging \
                   src/main/java/dev/fincore/simulation \
                   src/main/java/dev/fincore/web; do
    if [[ ! -f "$package_dir/package-info.java" ]]; then
        echo "[FAIL] 缺少包说明：$package_dir/package-info.java"
        status=1
    fi
done

if [[ "$status" -ne 0 ]]; then
    exit "$status"
fi

echo "[PASS] Java 代码规范基础检查通过"
