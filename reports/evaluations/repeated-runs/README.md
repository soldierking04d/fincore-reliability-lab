# Repeated-run evidence

The repeat phase is complete: 30 fresh runs were added to the original 15, producing three attempts for every agent/task pair and 45 controlled attempts overall. Runs executed sequentially on the same Ubuntu ARM64 laboratory VM to limit CPU, Docker and database contention.

## Outcome

Of the 45 total attempts, 33 were clean. Four failed only hidden scenarios, three failed a public suite, and two produced patches that did not compile. The public-suite count is three rather than two when the original Antigravity FC-005 run is included.

Repetition changed the interpretation of the benchmark. Codex had the highest clean-run rate, Claude passed the most planned hidden scenarios, and Antigravity was fastest but less reliable end to end. FC-003 and FC-004 were clean in all nine attempts; FC-001 was clean in only two.

Numeric 100-point Rubric scores remain limited to the first publication. The repeat ranking uses deterministic clean-run, hidden-scenario and timing evidence because the 30 captured patches have not received a full subjective rubric review.

## Public and private evidence

The public index records source commit, agent/model identity, elapsed time, available usage, test outcomes and SHA-256 digests. Active-task patches, transcripts and hidden tests remain private so a later candidate cannot retrieve a solution.

- [Three-run report](../repeatability-results.md)
- [Machine-readable aggregate](../repeatability-results.json)
- [Sanitized 30-run index](../repeat-progress.json)
- [Run-index schema](../run-index.schema.json)
- [Full repeatability protocol](../repeatability-protocol.md)
