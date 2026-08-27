# FC-002 Codex evaluation — run 001

Codex repaired the `benchmark/illegal-status-overwrite` branch in one valid attempt and scored **100/100**. The complete public suite passed 15/15 tests, including five agent-authored state-transition tests, and all five private scenarios passed exactly.

> 中文摘要：第二次正式 Codex 实测得分 100/100。公开测试 15/15、私有隐藏场景 5/5 全部通过。修复同时恢复了合法状态机检查和数据库 CAS 条件，终态不会被旧线程覆盖，并发竞争只有一个赢家，失败的转换不会递增版本或产生审计记录。

## Run identity

| Field | Value |
|---|---|
| Task | FC-002 — illegal status overwrite |
| Agent | OpenAI Codex CLI 0.150.1 |
| Model | `gpt-5.6-sol`, high reasoning |
| Source branch | [`benchmark/illegal-status-overwrite`](https://github.com/soldierking04d/fincore-reliability-lab/tree/benchmark/illegal-status-overwrite) |
| Source commit | `a0d33bb55d17e3e934924a180efe6ed39f9e9d3d` |
| Valid run window | 2026-08-27 20:59:00–21:04:27 UTC |
| Agent wall time | 327 seconds |
| Human repair rounds | 0 |

## Isolation protocol

The runner cloned only the intentional-defect branch, removed the reference defect patches and branch generator, archived the candidate into a new one-commit repository, and removed every Git remote. The private grader was not mounted into the agent container.

The run used an ephemeral container on the Ubuntu ARM64 laboratory VM. Only the candidate snapshot, the Codex package, and a dedicated Codex login directory were mounted; the host Docker socket and private grader were not mounted. Codex ran with `workspace-write`, `--ephemeral`, `--ignore-user-config`, high reasoning, and JSONL evidence capture. The transcript contained 25 repository-local commands and no network, remote, history, hidden-grader, or outside-repository access.

The runner follows OpenAI's [non-interactive Codex guidance](https://learn.chatgpt.com/docs/non-interactive-mode) for JSONL output, ephemeral sessions, and explicit sandbox selection.

## Patch summary

Codex changed three files:

- restored centralized legal-transition validation before any database mutation;
- restored a PostgreSQL compare-and-set predicate on `business_key + expected status`, so stale or concurrent losers update zero rows and fail visibly;
- added five PostgreSQL integration tests and strengthened state-machine unit tests for terminal states, stale retries, competing outcomes, version increments, and audit behavior.

A successful transition increments the version once and writes one matching audit record within the caller's existing transaction. A rejected transition changes neither state nor version and creates no audit row.

The full model patch and transcript remain private so future candidates cannot retrieve the answer. Only the scorecard and sanitized scenario-level evidence are public.

## Test evidence

| Check | Result |
|---|---|
| Complete public Maven/Testcontainers suite | 15 passed, 0 failed, 0 errors |
| Terminal SUCCESS/COMPENSATED protection | Passed |
| Stale expected-state replay | Passed |
| Same expected state across two application contexts | Passed; exactly one winner |
| Competing SUCCESS/FAILED outcomes | Passed; one winner and matching audit |
| Concurrent entry into compensation | Passed; exactly one winner |
| Version and audit cardinality | Passed; one increment and one audit |

The hidden suite was calibrated before the valid run: the correct main branch passed 5/5 scenarios and the intentional-defect branch failed 5/5.

## Score

| Dimension | Score | Evidence |
|---|---:|---|
| Functional correctness | 20/20 | Legal state machine, terminal protection, stale rejection, compensation entry |
| Concurrency and transactions | 15/15 | Two application contexts and single-winner database CAS |
| Idempotency and consistency | 15/15 | Expected-state predicate, one version increment, replay has no effect |
| Failure recovery | 10/10 | Late failure cannot overwrite success; rejection remains visible |
| Financial safety | 10/10 | Terminal outcomes remain immutable; no state corruption |
| Test completeness | 10/10 | Agent tests, public suite, and private suite all passed |
| Performance and capacity | 10/10 | One conditional update; no JVM lock or global serialization |
| Maintainability | 5/5 | Minimal production-shaped repair with centralized rules |
| Observability | 5/5 | Exactly one audit per accepted transition and none for rejection |
| **Total** | **100/100** | No veto triggered |

## Token usage

| Metric | Tokens |
|---|---:|
| Input | 730,697 |
| Cached input | 669,184 |
| Uncached input | 61,513 |
| Output | 13,639 |
| Reasoning output | 7,425 |

No dollar cost is reported because the ChatGPT-authenticated Codex evidence stream does not expose a per-run currency charge.

## Limitations

- This is one valid run of one model on FC-002; it does not establish run-to-run variance or a cross-model ranking.
- The agent's own container could not start Maven offline because the repository-local cache lacked the Spring Boot parent POM. The independent grader subsequently ran the complete Maven/Testcontainers suite successfully.
- The hidden test implementation, full transcript, and answer patch remain private.
- General model conclusions should wait until at least FC-003 and a second coding agent are evaluated under the same protocol.
