# FC-004 Codex evaluation — run 001

Codex repaired the `benchmark/scale-down-takeover` branch in one valid attempt and scored **100/100**. The complete public suite passed 14/14 tests and all five private scenarios passed exactly.

> 中文摘要：第四次正式 Codex 实测得分 100/100。公开测试 14/14、私有隐藏场景 5/5 全部通过。修复恢复了资金事务内的 owner/epoch/state/expiry fencing，并让结算共享锁与 drain/takeover 更新锁形成明确提交边界；数据库时钟、首次 claim 竞争、双接管竞争和旧 Epoch 重放也通过独立验证。

## Run identity

| Field | Value |
|---|---|
| Task | FC-004 — scale-down takeover |
| Agent | OpenAI Codex CLI 0.150.1 |
| Model | `gpt-5.6-sol`, high reasoning |
| Source branch | [`benchmark/scale-down-takeover`](https://github.com/soldierking04d/fincore-reliability-lab/tree/benchmark/scale-down-takeover) |
| Source commit | `fc21ebe0ddf711d3447dbbbb5c959de9488a9be5` |
| Valid run window | 2026-08-27 21:59:49–22:07:09 UTC |
| Agent wall time | 440 seconds |
| Human repair rounds | 0 |

## Isolation protocol

The runner cloned only the intentional-defect branch, removed the reference defect patches and branch generator, archived the candidate into a new one-commit repository, and removed every Git remote. The private grader was not mounted into the agent container.

The run used an ephemeral container on the Ubuntu ARM64 laboratory VM. Only the candidate snapshot, the Codex package, and a dedicated Codex login directory were mounted; the host Docker socket and private grader were not mounted. Codex ran with `workspace-write`, `--ephemeral`, `--ignore-user-config`, high reasoning, and JSONL evidence capture. The transcript contained 13 repository-local commands and no network, remote, history, hidden-grader, or outside-repository access.

The runner follows OpenAI's [non-interactive Codex guidance](https://learn.chatgpt.com/docs/non-interactive-mode) for JSONL output, ephemeral sessions, and explicit sandbox selection.

## Patch summary

Codex changed three files:

- replaced lease read/update branching with one PostgreSQL upsert using the database clock, so initial claim and expired takeover serialize on the shard primary key;
- restored owner, exact epoch, `RUNNING`, and unexpired validation with `SELECT … FOR SHARE` and required an existing transaction;
- moved fence validation before Inbox insertion, guaranteeing rejected work leaves no persistent effect;
- added PostgreSQL integration coverage for drain, expiry, wrong owner/epoch, takeover retry, concurrent contenders, and in-flight drain ordering.

A financial transaction holds a shared lock on its shard lease row until commit. Drain, renewal, or takeover requires a conflicting row update lock. If financial work is admitted first, drain/takeover waits; if drain/takeover wins first, the fence query rechecks the row and rejects the stale token. Shards remain independent.

The full model patch and transcript remain private so future candidates cannot retrieve the answer. Only the scorecard and sanitized scenario-level evidence are public.

## Test evidence

| Check | Result |
|---|---|
| Complete public Maven/Testcontainers suite | 14 passed, 0 failed, 0 errors |
| Expired and DRAINING leases | Rejected before Inbox or financial effects |
| Old epoch after takeover | Rejected; new owner retried the same command successfully |
| Two takeover contenders across application contexts | Exactly one winner; epoch incremented once |
| In-flight financial transaction versus drain | Drain waited for commit; later old-token write rejected |
| Ledger, balance, audit, and Outbox cardinality | Exactly one accepted financial effect |

The final hidden suite was calibrated before the valid run: two fresh correct-main runs passed 5/5 scenarios. The intentional-defect branch failed four targeted fencing scenarios and passed only the unaffected single-winner takeover case.

## Score

| Dimension | Score | Evidence |
|---|---:|---|
| Functional correctness | 20/20 | Owner, epoch, state, expiry, and takeover behavior enforced |
| Concurrency and transactions | 15/15 | Shared fence lock and conflicting drain/takeover lock define commit boundary |
| Idempotency and consistency | 15/15 | Rejection has zero effect; new owner safely retries same command |
| Failure recovery | 10/10 | Expired/stale failures remain visible with no orphan state |
| Financial safety | 10/10 | No post-drain stale write; exact balanced settlement remains intact |
| Test completeness | 10/10 | Four agent-authored integration tests plus public and private suites |
| Performance and capacity | 10/10 | Per-shard row locking; no global or JVM lock |
| Maintainability | 5/5 | Focused lease service repair with explicit transaction contract |
| Observability | 5/5 | Current owner/epoch remains queryable; rejection and financial audit visible |
| **Total** | **100/100** | No veto triggered |

## Token usage

| Metric | Tokens |
|---|---:|
| Input | 968,010 |
| Cached input | 880,128 |
| Uncached input | 87,882 |
| Output | 20,303 |
| Reasoning output | 10,354 |

No dollar cost is reported because the ChatGPT-authenticated Codex evidence stream does not expose a per-run currency charge.

## Limitations

- This is one valid run of one model on FC-004; it does not establish run-to-run variance or a cross-model ranking.
- The agent's own container could not run Maven/Testcontainers because dependencies were unavailable under its repository-only sandbox and Docker was intentionally absent. The independent grader subsequently ran the complete suite successfully.
- The public suite emitted non-failing scheduled-task shutdown noise after per-class Testcontainers stopped; Surefire still reported all 14 tests passed and `BUILD SUCCESS`.
- The hidden test implementation, full transcript, and answer patch remain private.
- General model conclusions should wait until FC-005 and a second coding agent are evaluated under the same protocol.
