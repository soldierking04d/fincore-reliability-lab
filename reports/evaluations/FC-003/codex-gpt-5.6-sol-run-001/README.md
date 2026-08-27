# FC-003 Codex evaluation — run 001

Codex repaired the `benchmark/fee-hot-account` branch in one valid attempt and scored **100/100**. The complete public suite passed 15/15 tests and all five private scenarios passed exactly.

> 中文摘要：第三次正式 Codex 实测得分 100/100。公开测试 15/15、私有隐藏场景 5/5 全部通过。修复把所有手续费写入 shard 0 的热点恢复为跨 16 个分片的确定性路由；并发建账、重复归集、失败回滚、精确金额和单一账本效果均通过独立验证。

## Run identity

| Field | Value |
|---|---|
| Task | FC-003 — fee hot account |
| Agent | OpenAI Codex CLI 0.150.1 |
| Model | `gpt-5.6-sol`, high reasoning |
| Source branch | [`benchmark/fee-hot-account`](https://github.com/soldierking04d/fincore-reliability-lab/tree/benchmark/fee-hot-account) |
| Source commit | `a6875d6982453f1b42cebd739b934d48fea903de` |
| Valid run window | 2026-08-27 21:27:57–21:33:07 UTC |
| Agent wall time | 310 seconds |
| Human repair rounds | 0 |

## Isolation protocol

The runner cloned only the intentional-defect branch, removed the reference defect patches and branch generator, archived the candidate into a new one-commit repository, and removed every Git remote. The private grader was not mounted into the agent container.

The run used an ephemeral container on the Ubuntu ARM64 laboratory VM. Only the candidate snapshot, the Codex package, and a dedicated Codex login directory were mounted; the host Docker socket and private grader were not mounted. Codex ran with `workspace-write`, `--ephemeral`, `--ignore-user-config`, high reasoning, and JSONL evidence capture. The transcript contained 14 repository-local commands and no network, remote, history, hidden-grader, or outside-repository access.

The runner follows OpenAI's [non-interactive Codex guidance](https://learn.chatgpt.com/docs/non-interactive-mode) for JSONL output, ephemeral sessions, and explicit sandbox selection.

## Calibration note

Before the formal run, calibration exposed a separate concurrent-provisioning defect in the verified baseline: a targeted PostgreSQL `ON CONFLICT` clause could still lose a race on the deterministic account primary key. The baseline was hardened to use `ON CONFLICT DO NOTHING`, a public race test was added, and that change was merged into the benchmark source before Codex ran.

This pre-run correction is not part of the agent patch or score. It ensures FC-003 measures only the intended defect—collapsing every fee write onto shard 0—without rewarding or penalizing the agent for an unrelated baseline flaw.

## Patch summary

Codex changed four files:

- replaced the constant shard-0 result with a deterministic UTF-8 business-key hash folded into the configured power-of-two shard range;
- added distribution, determinism, bounds, and invalid-key unit coverage;
- added exact `BigDecimal` aggregation, duplicate-key, concurrency, rollback, and retry integration coverage;
- extended the pure-JDK verification path with an all-shards-used assertion.

The production repair is stateless and O(1). It does not introduce a JVM lock, external cache, new money representation, or alternative consistency boundary. Existing database transactions, unique aggregation keys, account locks, balanced journal postings, and retry semantics remain intact.

The full model patch and transcript remain private so future candidates cannot retrieve the answer. Only the scorecard and sanitized scenario-level evidence are public.

## Test evidence

| Check | Result |
|---|---|
| Complete public Maven/Testcontainers suite | 15 passed, 0 failed, 0 errors |
| Deterministic bounded routing | Passed; every configured shard used |
| Concurrent provisioning across two application contexts | Passed; exactly 16 accounts |
| Concurrent duplicate aggregation across two contexts | Passed; one financial effect |
| Failure after aggregation claim | Passed; full rollback and safe same-key retry |
| Exact shard sum, balanced ledger, and zeroed shards | Passed |

The hidden suite was calibrated before the valid run: the correct main branch passed 5/5 scenarios. The intentional-defect branch failed the targeted distribution scenario while the four unaffected safety scenarios remained green.

## Score

| Dimension | Score | Evidence |
|---|---:|---|
| Functional correctness | 20/20 | Deterministic bounded routing, all shards used, exact aggregate |
| Concurrency and transactions | 15/15 | Cross-context provisioning and aggregation; locked snapshot |
| Idempotency and consistency | 15/15 | One aggregation, one ledger transaction, one treasury effect |
| Failure recovery | 10/10 | Failed claim rolls back completely and retry succeeds |
| Financial safety | 10/10 | Exact `BigDecimal`, balanced journal, reconciled balances |
| Test completeness | 10/10 | Five agent-authored JUnit cases plus public and private suites |
| Performance and capacity | 10/10 | Hot writes distributed with stateless O(1) routing |
| Maintainability | 5/5 | One focused production change and clear regression tests |
| Observability | 5/5 | Failures remain visible; outcome and ledger evidence retained |
| **Total** | **100/100** | No veto triggered |

## Token usage

| Metric | Tokens |
|---|---:|
| Input | 906,493 |
| Cached input | 834,048 |
| Uncached input | 72,445 |
| Output | 13,392 |
| Reasoning output | 6,643 |

No dollar cost is reported because the ChatGPT-authenticated Codex evidence stream does not expose a per-run currency charge.

## Limitations

- This is one valid run of one model on FC-003; it does not establish run-to-run variance or a cross-model ranking.
- The agent's own container could not run the full Maven suite because dependencies were unavailable offline and Docker was intentionally absent. The independent grader subsequently ran the complete Maven/Testcontainers suite successfully.
- The intentional defect is narrow, so four hidden safety scenarios also pass on the defect branch; calibration proves those cases guard against collateral regressions while the distribution case detects the target defect.
- The hidden test implementation, full transcript, and answer patch remain private.
- General model conclusions should wait until FC-004/FC-005 and a second coding agent are evaluated under the same protocol.
