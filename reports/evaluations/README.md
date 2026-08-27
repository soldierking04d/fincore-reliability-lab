# Controlled coding-agent evaluation summary

This page aggregates the machine-readable scorecards for the first complete five-task benchmark pass. Every row is one isolated `gpt-5.6-sol` run with high reasoning; the candidate had one source snapshot, no Git remote, no private grader, and no answer patch.

> 中文摘要：首轮五任务基准已经完整跑通，5/5 任务完成，4 次满分，总分 495/500，平均 99/100；公开测试合计 70/70，通过 24/25 个私有场景，全部运行均未触发资金安全一票否决。

## Results

| Task | Failure mode | Score | Public tests | Private scenarios | Agent time | Report |
|---|---|---:|---:|---:|---:|---|
| FC-001 | Duplicate settlement | 95/100 | 11/11 | 4/5 | 341 s | [Evidence](FC-001/codex-gpt-5.6-sol-run-001/README.md) |
| FC-002 | Illegal terminal overwrite | 100/100 | 15/15 | 5/5 | 327 s | [Evidence](FC-002/codex-gpt-5.6-sol-run-001/README.md) |
| FC-003 | Fee hot account | 100/100 | 15/15 | 5/5 | 310 s | [Evidence](FC-003/codex-gpt-5.6-sol-run-001/README.md) |
| FC-004 | Scale-down takeover | 100/100 | 14/14 | 5/5 | 440 s | [Evidence](FC-004/codex-gpt-5.6-sol-run-001/README.md) |
| FC-005 | Duplicate compensation | 100/100 | 15/15 | 5/5 | 344 s | [Evidence](FC-005/codex-gpt-5.6-sol-run-001/README.md) |
| **Total / average** | **Five tasks** | **495/500 · 99.0 avg** | **70/70** | **24/25** | **1,762 s · 352.4 avg** | |

## What this first pass shows

- The model completed all five valid runs without human repair and triggered no financial-safety veto.
- Four tasks achieved full public and private coverage. FC-001 lost five points because a reused message ID with conflicting payload was explicitly rejected instead of returning the first settlement outcome; no duplicate posting occurred.
- Database-backed idempotency, legal state transitions, deterministic sharding, transaction-held fencing, and unique compensation effects were independently exercised.
- Public test totals include agent-authored tests and therefore describe these exact candidate runs, not a fixed cross-model test count.

## Interpretation boundary

This is a benchmark completion summary, not a general leaderboard. It covers one model, one valid run per task, one repository, and one runtime protocol. Cross-model claims require the same source commits, prompts, isolation, graders, and scoring review for every additional agent.

Raw transcripts, hidden test implementations, and answer patches remain private. Public evidence is intentionally limited to scorecards, runtime metadata, token totals, calibration summaries, and scenario-level outcomes.

The same data is available as [machine-readable JSON](summary.json).
