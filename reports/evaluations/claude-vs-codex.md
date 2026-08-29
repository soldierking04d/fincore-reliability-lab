# Claude Code vs Codex — controlled five-task comparison

OpenAI Codex CLI and Anthropic Claude Code each completed one isolated first-pass run of all five FinCore Reliability Lab tasks without human repair. Codex scored **495/500** and Claude scored **490/500**. Both produced four perfect runs and neither triggered a financial-safety veto.

> 中文摘要：两套 Coding Agent 使用相同缺陷提交、任务提示、隐藏测试和 100 分 Rubric 完成五题首轮实测。Codex 为 495/500，Claude 为 490/500；差异集中在 FC-001。其余四题两者均为满分，且都没有触发资金安全一票否决。

## Results

| Task | Failure mode | Codex | Claude | Codex private | Claude private | Codex time | Claude time |
|---|---|---:|---:|---:|---:|---:|---:|
| FC-001 | Duplicate settlement | 95 | 90 | 4/5 | 3/5 | 341 s | 408 s |
| FC-002 | Illegal terminal overwrite | 100 | 100 | 5/5 | 5/5 | 327 s | 241 s |
| FC-003 | Fee hot account | 100 | 100 | 5/5 | 5/5 | 310 s | 357 s |
| FC-004 | Scale-down takeover | 100 | 100 | 5/5 | 5/5 | 440 s | 707 s |
| FC-005 | Duplicate compensation | 100 | 100 | 5/5 | 5/5 | 344 s | 622 s |
| **Total** | **Five tasks** | **495/500** | **490/500** | **24/25** | **23/25** | **1,762 s** | **2,335 s** |

Public suites passed completely for both agents: Codex 70/70 and Claude 83/83. Those counts are not directly comparable because agents were allowed to add regression tests.

## What separated the runs
FC-001 was the only non-perfect task for either agent.

- Codex safely rejected a reused message ID carrying conflicting payload instead of returning the original settlement outcome. It still prevented a second financial effect and passed 4/5 private scenarios.
- Claude returned the stored outcome by the incoming business key after an inbox conflict. When the conflicting replay changed that key, the lookup returned no row. Its migration also omitted a ledger-level business-key uniqueness boundary. It passed 3/5 private scenarios.
- Neither implementation produced a repeated posting, so neither triggered the duplicate-funds veto.

Claude's FC-001 dimension score was 90/100: functional correctness 16/20, concurrency and transactions 15/15, idempotency and consistency 12/15, failure recovery 10/10, financial safety 10/10, test completeness 9/10, performance and capacity 9/10, maintainability 5/5, and observability 4/5.

## Runtime and cost

Claude's corrected agent time was 2,335 seconds versus Codex's 1,762 seconds, making this Claude pass 573 seconds, or 32.5%, slower overall. Claude was faster on FC-002 and slower on the other four tasks.

Claude Code reported an estimated total of $5.3438274 across the five runs. This is a CLI-reported estimate, not necessarily an incremental subscription charge. Codex's ChatGPT-authenticated evidence did not expose a per-run dollar amount, so this benchmark does not rank cost.

FC-005's VM wall clock stepped forward after the host resumed. The raw duration field recorded 80,901 seconds, while the outer controller observed 622 seconds. The raw evidence is retained privately, the published total uses 622 seconds, and the runner now measures elapsed time monotonically.

## Controlled protocol
Both agents received:

- the same five intentional-defect source commits;
- the same task prompts and financial guardrails;
- a sanitized one-commit repository with no Git remote or reference defect patch;
- the same public suite, five-scenario private grader, and 100-point Rubric;
- one valid run per task with no human repair.

The private grader, hidden test implementations, raw transcript, model patch, and authentication material were never mounted into the candidate workspace. CLI-specific runners differed only where required by each product's isolation interface.

Claude Code 2.1.250 ran with the recorded sonnet alias, high effort, restricted/safe mode, an ephemeral container, disabled WebFetch/WebSearch/MCP tools, and a strict empty network allowlist for Bash subprocesses. The report does not infer an unverified underlying Sonnet snapshot.

## Interpretation

This comparison shows that both agents handled state-machine CAS, deterministic fee sharding, fencing during shard takeover, and compensation uniqueness at a production-shaped level in this repository. Codex had a narrow five-point lead because its FC-001 repair covered one additional private scenario.

It does not establish a universal model ranking. One run per model and task does not measure variance, and the benchmark covers Java/PostgreSQL financial reliability rather than general software engineering.

Machine-readable files:

- [Codex summary](summary.json)
- [Claude summary](claude-summary.json)
- [Cross-agent comparison](comparison.json)
