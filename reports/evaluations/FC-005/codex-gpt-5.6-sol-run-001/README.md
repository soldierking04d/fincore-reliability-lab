# FC-005 Codex evaluation — run 001

Codex repaired the `benchmark/duplicate-compensation` branch in one valid attempt and scored **100/100**. The complete public suite passed 15/15 tests and all five private scenarios passed exactly.

> 中文摘要：第五次正式 Codex 实测得分 100/100。公开测试 15/15、私有隐藏场景 5/5 全部通过。修复把补偿幂等边界下沉到 PostgreSQL 唯一约束和原子 claim，并验证了跨应用实例并发、重复失败结果、反向账本唯一性、事务回滚后重试和原始成功流水不可变。

## Run identity

| Field | Value |
|---|---|
| Task | FC-005 — duplicate compensation |
| Agent | OpenAI Codex CLI 0.150.1 |
| Model | `gpt-5.6-sol`, high reasoning |
| Source branch | [`benchmark/duplicate-compensation`](https://github.com/soldierking04d/fincore-reliability-lab/tree/benchmark/duplicate-compensation) |
| Source commit | `2d74bebc8e20d94e8b0f0417620a93b6f2127a35` |
| Valid run window | 2026-08-27 22:26:05–22:31:49 UTC |
| Agent wall time | 344 seconds |
| Human repair rounds | 0 |

## Isolation protocol

The runner cloned only the intentional-defect branch, removed the reference defect patches and branch generator, archived the candidate into a new one-commit repository, and removed every Git remote. The private grader was not mounted into the agent container.

The run used an ephemeral container on the Ubuntu ARM64 laboratory VM. Only the candidate snapshot, the Codex package, and a dedicated Codex login directory were mounted; the host Docker socket and private grader were not mounted. Codex ran with `workspace-write`, `--ephemeral`, `--ignore-user-config`, high reasoning, and JSONL evidence capture. The transcript contained eight repository-local commands and no network, remote, history, hidden-grader, or outside-repository access.

The runner follows OpenAI's [non-interactive Codex guidance](https://learn.chatgpt.com/docs/non-interactive-mode) for JSONL output, ephemeral sessions, and explicit sandbox selection.

## Patch summary

Codex changed three files:

- added database uniqueness for the original settlement key, deterministic compensation business key, and compensation Outbox effect;
- replaced random compensation claims with a deterministic ID plus PostgreSQL `INSERT … ON CONFLICT`;
- aggregated required debits and locked distinct accounts in deterministic UUID order;
- persisted a single `FAILED` compensation with a manual-review reason when funds had moved;
- validated the successful original journal and left both the original order and journal unchanged;
- added PostgreSQL integration coverage for sequential and concurrent duplicates, rollback/retry, exact reversal, invalid sources, and insufficient funds.

The successful reversal, balanced journal, balance changes, terminal compensation status, and Outbox event commit in one database transaction. Unexpected failures roll the claim and all partial effects back, so a later retry can safely claim the operation. Known insufficient funds commit one stable failure outcome without a journal, balance, or Outbox side effect.

The migration intentionally fails when historical duplicates already exist, requiring reviewed remediation instead of silently deleting financial evidence.

The full model patch and transcript remain private so future candidates cannot retrieve the answer. Only the scorecard and sanitized scenario-level evidence are public.

## Test evidence

| Check | Result |
|---|---|
| Complete public Maven/Testcontainers suite | 15 passed, 0 failed, 0 errors |
| 24 concurrent requests across two application contexts | One compensation ID, row, reverse journal, and Outbox event |
| Sequential duplicate after success | Existing outcome returned; no second financial effect |
| Injected ledger-key conflict | Transaction rolled back completely; retry succeeded after blocker removal |
| Recipient balance changed before reversal | One persistent FAILED outcome; no reverse effect |
| Non-success original settlement | Rejected with no claim or financial effect |
| Original order and journal | Remained unchanged; balances restored only by exact reverse postings |

The final hidden suite was calibrated before the valid run: two fresh correct-main runs passed 5/5 scenarios. The intentional-defect branch failed four targeted compensation scenarios and passed only the unaffected non-success rejection case.

## Score

| Dimension | Score | Evidence |
|---|---:|---|
| Functional correctness | 20/20 | Exactly one stable success or failure outcome per original settlement |
| Concurrency and transactions | 15/15 | PostgreSQL claim uniqueness, row locks, and atomic reversal boundary |
| Idempotency and consistency | 15/15 | Cross-context retries return one compensation and one effect |
| Failure recovery | 10/10 | Unexpected failure rolls back claim; reviewed failure remains stable |
| Financial safety | 10/10 | Exact balanced reverse; original journal is immutable |
| Test completeness | 10/10 | Five agent-authored integration tests plus public and private suites |
| Performance and capacity | 10/10 | Contention is scoped to one original key and involved accounts |
| Maintainability | 5/5 | Focused service change with explicit schema-enforced invariants |
| Observability | 5/5 | Persistent terminal status and failure reason support review |
| **Total** | **100/100** | No veto triggered |

## Token usage

| Metric | Tokens |
|---|---:|
| Input | 471,205 |
| Cached input | 420,352 |
| Uncached input | 50,853 |
| Output | 17,222 |
| Reasoning output | 8,799 |

No dollar cost is reported because the ChatGPT-authenticated Codex evidence stream does not expose a per-run currency charge.

## Limitations

- This is one valid run of one model on FC-005; it does not establish run-to-run variance or a cross-model ranking.
- The agent's own container could not run Maven/Testcontainers because dependencies were unavailable under its repository-only sandbox and Docker was intentionally absent. The independent grader subsequently ran the complete suite successfully.
- The public suite emitted non-failing scheduled-task shutdown noise after per-class Testcontainers stopped; Surefire still reported all 15 tests passed and `BUILD SUCCESS`.
- A production rollout of the new unique constraints requires a reviewed pre-migration duplicate audit; the migration intentionally refuses to conceal existing conflicts.
- The hidden test implementation, full transcript, and answer patch remain private.
- General model conclusions should wait until a second coding agent is evaluated under the same protocol.
