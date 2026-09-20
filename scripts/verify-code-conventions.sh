#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$script_dir/.."

status=0

# CI 镜像不保证安装 rg；Perl 保留本脚本逐行检查及负向前瞻的正则语义。
if command -v rg >/dev/null 2>&1; then
    search_backend=rg
elif command -v perl >/dev/null 2>&1; then
    search_backend=perl
else
    echo "[FAIL] Java 规范检查需要 rg 或 Perl，禁止跳过搜索后宣称通过" >&2
    exit 1
fi

search_source() {
    local mode="$1"
    local pattern="$2"
    shift 2
    if [[ "$search_backend" == rg ]]; then
        rg "$mode" --pcre2 -- "$pattern" "$@"
    else
        perl -e '
            use strict;
            use warnings;
            use File::Find;
            $SIG{__WARN__} = sub { die @_ };
            my ($mode, $pattern, @paths) = @ARGV;
            my $expression = eval { qr/$pattern/ };
            die "invalid search expression: $@" if $@;
            my @files;
            for my $path (@paths) {
                die "source path does not exist: $path\n" unless -e $path;
                if (-d $path) {
                    find({no_chdir => 1, wanted => sub {
                        push @files, $File::Find::name if -f $File::Find::name;
                    }}, $path);
                } else {
                    push @files, $path;
                }
            }
            my $found = 0;
            for my $file (sort @files) {
                open my $source, "<", $file or die "cannot read $file: $!\n";
                my $line = 0;
                while (my $text = <$source>) {
                    ++$line;
                    next unless $text =~ $expression;
                    exit 0 if $mode eq "-q";
                    print "$file:$line:$text";
                    $found = 1;
                }
                close $source or die "cannot close $file: $!\n";
            }
            exit($found ? 0 : 1);
        ' -- "$mode" "$pattern" "$@"
    fi
}

report_matches() {
    local title="$1"
    local pattern="$2"
    shift 2
    local result
    if result="$(search_source -n "$pattern" "$@")"; then
        echo "[FAIL] $title"
        echo "$result"
        status=1
    else
        local search_status=$?
        if [[ "$search_status" -ne 1 ]]; then
            echo "[FAIL] ${title}：搜索执行失败（退出码 ${search_status}）" >&2
            status=1
        fi
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
report_matches "金融事务必须显式声明 rollbackFor" \
    '@Transactional\b(?!\s*\([^)]*\brollbackFor\s*=)' src/main/java

# 先验证枚举结果，不能让进程替换的失败被当成空集合而误报通过。
if ! source_files="$(find src/main/java -type f -name '*.java' ! -name package-info.java | sort)"; then
    echo "[FAIL] 无法枚举生产 Java 源码" >&2
    exit 1
fi
if [[ -z "$source_files" ]]; then
    echo "[FAIL] 未找到生产 Java 源码" >&2
    exit 1
fi

while IFS= read -r source_file; do
    if ! search_source -q '/\*\*' "$source_file"; then
        echo "[FAIL] 缺少 Javadoc：$source_file"
        status=1
    fi
    if ! search_source -q '@author' "$source_file"; then
        echo "[FAIL] 缺少 @author：$source_file"
        status=1
    fi
    if ! search_source -q '@since' "$source_file"; then
        echo "[FAIL] 缺少 @since：$source_file"
        status=1
    fi
done <<< "$source_files"

for package_dir in src/main/java/dev/fincore \
                   src/main/java/dev/fincore/application \
                   src/main/java/dev/fincore/domain \
                   src/main/java/dev/fincore/infrastructure \
                   src/main/java/dev/fincore/infrastructure/concurrent \
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

echo "[PASS] Java 代码规范与金融事务基础检查通过"
