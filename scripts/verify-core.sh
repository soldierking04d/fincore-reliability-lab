#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="$project_dir/target/core-verification"
mkdir -p "$build_dir"

compiler="$(command -v javac || true)"
compiler_version="$($compiler -version 2>&1 || true)"
compiler_major="$(sed -E 's/^javac (1\.)?([0-9]+).*/\2/' <<< "$compiler_version")"

# macOS 机器可能仍把系统默认 Java 指向 8；自动寻找项目要求的 JDK 21。
if [[ ! "$compiler_major" =~ ^[0-9]+$ || "$compiler_major" -lt 21 ]]; then
  if [[ -x /usr/libexec/java_home ]]; then
    jdk_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$jdk_home" && -x "$jdk_home/bin/javac" ]]; then
      compiler="$jdk_home/bin/javac"
    fi
  fi
fi

compiler_version="$($compiler -version 2>&1 || true)"
compiler_major="$(sed -E 's/^javac (1\.)?([0-9]+).*/\2/' <<< "$compiler_version")"
if [[ ! "$compiler_major" =~ ^[0-9]+$ || "$compiler_major" -lt 21 ]]; then
  echo "JDK 21 or newer is required; found: ${compiler_version:-missing javac}" >&2
  exit 1
fi

"$compiler" --release 21 -d "$build_dir" \
  "$project_dir/src/main/java/dev/fincore/domain/Money.java" \
  "$project_dir/src/main/java/dev/fincore/domain/SettlementStatus.java" \
  "$project_dir/src/main/java/dev/fincore/domain/LedgerDirection.java" \
  "$project_dir/src/main/java/dev/fincore/domain/LedgerPosting.java" \
  "$project_dir/src/main/java/dev/fincore/domain/BalancedJournal.java" \
  "$project_dir/src/main/java/dev/fincore/domain/SettlementCommand.java" \
  "$project_dir/src/main/java/dev/fincore/domain/SettlementOutcome.java" \
  "$project_dir/src/main/java/dev/fincore/domain/FeeShardRouter.java" \
  "$project_dir/src/main/java/dev/fincore/domain/FenceToken.java" \
  "$project_dir/src/main/java/dev/fincore/domain/ShardRouter.java" \
  "$project_dir/src/main/java/dev/fincore/domain/OrderSide.java" \
  "$project_dir/src/main/java/dev/fincore/domain/OrderType.java" \
  "$project_dir/src/main/java/dev/fincore/domain/OrderStatus.java" \
  "$project_dir/src/main/java/dev/fincore/domain/MatchingPolicy.java" \
  "$project_dir/src/main/java/dev/fincore/domain/TradingIdentifiers.java" \
  "$project_dir/src/main/java/dev/fincore/domain/PlaceOrderCommand.java" \
  "$project_dir/src/main/java/dev/fincore/domain/TradeView.java" \
  "$project_dir/src/main/java/dev/fincore/domain/TradeSyncCommand.java" \
  "$project_dir/src/main/java/dev/fincore/infrastructure/concurrent/VirtualTaskExecutors.java" \
  "$project_dir/src/main/java/dev/fincore/simulation/ReliabilitySimulation.java" \
  "$project_dir/scripts/CoreVerification.java"
java_bin="$(dirname "$compiler")/java"
"$java_bin" -cp "$build_dir" CoreVerification
"$java_bin" -cp "$build_dir" dev.fincore.simulation.ReliabilitySimulation
