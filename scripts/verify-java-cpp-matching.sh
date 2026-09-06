#!/usr/bin/env bash
set -euo pipefail
# 独立实验：不启动或修改数据库，不触碰生产撮合路径，产物全部写入已忽略的 target。
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
experiment_dir="$project_dir/experiments/java-cpp-matching"
build_dir="$project_dir/target/java-cpp-matching"
mkdir -p "$build_dir/classes"

jdk_home="${MATCHING_JDK_HOME:-}"
if [[ -z "$jdk_home" && -x /usr/libexec/java_home ]]; then
  jdk_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [[ -n "$jdk_home" ]]; then
  java_bin="$jdk_home/bin/java"
  javac_bin="$jdk_home/bin/javac"
else
  java_bin="$(command -v java)"
  javac_bin="$(command -v javac)"
fi
java_version="$($java_bin -version 2>&1)"
if [[ "$java_version" != *'version "21.'* ]]; then
  echo "This comparison requires Java 21 exactly; set MATCHING_JDK_HOME. Found: $java_version" >&2
  exit 1
fi
command -v node >/dev/null
node -e 'if(Number(process.versions.node.split(".")[0])<18)process.exit(1)'
cpp_compiler="${CXX:-clang++}"
command -v "$cpp_compiler" >/dev/null
cpp_flags=(-std=c++20 -O3 -DNDEBUG -Wall -Wextra -Werror)
java_arch="$($java_bin -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*os.arch = //p')"
translation="native-or-unknown"
if [[ "$(uname -s)" == Darwin ]]; then
  if [[ "$java_arch" == x86_64 || "$java_arch" == amd64 ]]; then
    cpp_flags+=(-arch x86_64)
    if [[ "$(node -p process.arch)" == arm64 ]]; then translation="Both timed Java21 and C++ are x86_64 under Rosetta on Apple Silicon; native sanitizer build is correctness-only"; fi
  elif [[ "$java_arch" == aarch64 ]]; then cpp_flags+=(-arch arm64); fi
fi
"$javac_bin" --release 21 -d "$build_dir/classes" "$experiment_dir/java/MatchingLab.java"
"$cpp_compiler" "${cpp_flags[@]}" "$experiment_dir/cpp/matching_lab.cpp" -o "$build_dir/matching-cpp"
# ASan/UBSan 使用宿主原生架构验证；不把插桩延迟混入语言性能对比。
sanitizer_flags=(-std=c++20 -O1 -g -Wall -Wextra -Werror -fno-omit-frame-pointer -fsanitize=address,undefined -fno-sanitize-recover=all)
"$cpp_compiler" "${sanitizer_flags[@]}" "$experiment_dir/cpp/matching_lab.cpp" -o "$build_dir/matching-cpp-sanitized"
"$cpp_compiler" "${sanitizer_flags[@]}" "$experiment_dir/fixtures/sanitizer-probe.cpp" -o "$build_dir/sanitizer-probe"
export MATCHING_JAVA="$java_bin"
export MATCHING_CPP_FLAGS="${cpp_flags[*]}"
export MATCHING_CPP_COMPILER="$cpp_compiler"
export MATCHING_PROCESS_ARCH="$java_arch"
export MATCHING_TRANSLATION="$translation"
node "$experiment_dir/harness.mjs" "$@"
