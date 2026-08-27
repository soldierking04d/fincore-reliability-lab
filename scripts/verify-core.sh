#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="$project_dir/target/core-verification"
mkdir -p "$build_dir"
if command -v javac >/dev/null 2>&1; then
  compiler=(javac)
else
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
"${compiler[@]}" -d "$build_dir" \
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
  "$project_dir/src/main/java/dev/fincore/simulation/ReliabilitySimulation.java" \
  "$project_dir/scripts/CoreVerification.java"
java -cp "$build_dir" CoreVerification
java -cp "$build_dir" dev.fincore.simulation.ReliabilitySimulation
