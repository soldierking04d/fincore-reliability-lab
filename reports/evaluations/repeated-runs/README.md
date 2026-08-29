# Repeated-run evidence

This directory tracks the second and third valid runs for every agent/task combination. The first fifteen runs remain in the existing agent summaries and scorecards.

The repeat phase contains 30 planned runs. They execute sequentially on the same Ubuntu ARM64 laboratory VM so that CPU, Docker and database contention do not distort elapsed-time comparisons.

## Current observation

The first completed repeat is Codex on FC-001. Run 002 passed all five hidden scenarios in 390 seconds. Run 001 passed four of five scenarios safely. That difference is the first direct evidence that a single result should not be treated as a stable model property.

The run is not assigned a numeric rubric score until the captured patch and sanitized outcome receive the same review used for the first publication. Test success and rubric scoring are deliberately separate fields.

## Public and private evidence

The public index contains the frozen source commit, CLI/model identity, elapsed time, available usage totals, test totals, score status and SHA-256 digests. Active-task patches, transcripts and hidden tests remain private so a later candidate cannot retrieve the solution.

- [Current machine-readable progress](../repeat-progress.json)
- [Run-index schema](../run-index.schema.json)
- [Full repeatability protocol](../repeatability-protocol.md)
