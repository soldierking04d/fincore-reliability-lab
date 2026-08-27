# FC-001 Codex evaluation — run 001

This is the first controlled coding-agent run published by FinCore Reliability Lab. Codex repaired the `benchmark/duplicate-settlement` branch in one valid attempt and scored **95/100**. The public suite passed 11/11 tests. Four of five private scenarios passed exactly; the remaining scenario rejected a conflicting replay safely but returned an exception where the grader expected the original duplicate result.

> 中文摘要：第一次有效 Codex 实测得分 95/100。公开测试 11/11 通过，私有场景 4/5 按预期通过。唯一差异是同一 `messageId` 携带不同载荷时，模型选择明确拒绝，而隐藏评分器原本要求返回第一次结算的重复结果；没有发生第二次入账，因此不触发资金安全否决。

## Run identity

| Field | Value |
|---|---|
| Task | FC-001 — duplicate settlement |
| Agent | OpenAI Codex CLI 0.150.1 |
| Model | `gpt-5.6-sol`, high reasoning |
| Source branch | [`benchmark/duplicate-settlement`](https://github.com/soldierking04d/fincore-reliability-lab/tree/benchmark/duplicate-settlement) |
| Source commit | `587a591869497a90f0d8628239d3fc48521d09eb` |
| Valid run window | 2026-08-27 20:08:36–20:14:17 UTC |
| Agent wall time | 341 seconds |
| Human repair rounds | 0 |

## Isolation protocol

The runner cloned only the defect branch, removed reference defect patches and the branch generator, archived the remaining tree into a new one-commit repository, and removed every Git remote. The private grader was never mounted into the agent container.

The valid run used an ephemeral container because the laboratory VM's AppArmor policy blocks nested Bubblewrap user namespaces. The container mounted only the candidate snapshot, the signed Codex package, and a dedicated Codex login directory. It did not mount the host Docker socket. Codex still ran with `workspace-write`, `--ephemeral`, `--ignore-user-config`, and JSONL output. The command transcript contained 13 repository-local commands and no network, remote, history, or outside-repository access. This is strong benchmark isolation, not a certified hostile-code security boundary.

The runner follows OpenAI's [non-interactive Codex guidance](https://learn.chatgpt.com/docs/non-interactive-mode) for JSONL output, ephemeral sessions, and explicit sandbox selection.

## Patch summary

Codex changed three files:

- added a Flyway migration with durable uniqueness for inbox messages, settlement business keys, settlement message IDs, and settlement ledger transactions;
- replaced unconditional inserts with PostgreSQL `INSERT … ON CONFLICT DO NOTHING` claims inside the existing transaction;
- added 100-delivery concurrency tests, different-message/same-business-key tests, exact financial-effect assertions, and rollback-then-retry coverage.

The complete model patch and transcript remain private so future candidates cannot retrieve the answer. Only the scorecard and sanitized evidence are public.

## Test evidence

| Check | Result |
|---|---|
| Public Maven/Testcontainers suite | 11 passed, 0 failed, 0 errors |
| Same message across two application instances | Passed |
| Different messages racing for one business key | Passed |
| Unexpected failure rollback and safe retry | Passed |
| Database-owned final uniqueness boundaries | Passed |
| Conflicting payload replay contract | Safe rejection; grader expected duplicate result |

The conflicting replay created no second order, ledger transaction, outbox event, or balance movement. It is scored as a functional/API-contract deduction, not as a repeated-posting or hidden-error veto.

## Score

| Dimension | Score | Evidence |
|---|---:|---|
| Functional correctness | 18/20 | Public suite passed; one private response-contract mismatch |
| Concurrency and transactions | 15/15 | Two application contexts; database conflict serialization; one transaction |
| Idempotency and consistency | 15/15 | Durable message, business, and ledger uniqueness |
| Failure recovery | 10/10 | Failed transaction left no completed claim; retry succeeded |
| Financial safety | 10/10 | Exact balances and one immutable ledger effect |
| Test completeness | 9/10 | Strong agent-authored regression suite; agent sandbox could not run PostgreSQL tests itself |
| Performance and capacity | 9/10 | No JVM/global lock; bounded database claims; no separate capacity measurement |
| Maintainability | 5/5 | Three-file, production-shaped patch with reviewed migration behavior |
| Observability | 4/5 | Duplicate counter and visible conflict; no dedicated payload-conflict metric/audit |
| **Total** | **95/100** | No veto triggered |

## Token usage

| Metric | Tokens |
|---|---:|
| Input | 756,956 |
| Cached input | 702,208 |
| Uncached input | 54,748 |
| Output | 15,615 |
| Reasoning output | 7,720 |

No dollar cost is reported because ChatGPT-authenticated Codex sessions do not expose a per-run currency charge in this evidence stream.

## Limitations

- This is one model, one task, and one valid run; it is not yet a cross-model ranking.
- Infrastructure-only attempts made before the valid container protocol were excluded from model scoring and retained privately for runner debugging.
- Hidden test implementation remains private; this report exposes only scenario-level outcomes.
- A future comparison should repeat FC-001 and add the other four benchmark tasks before making general model claims.
