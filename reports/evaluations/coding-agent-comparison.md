# Codex vs Claude Code vs Antigravity — controlled five-task comparison

OpenAI Codex CLI, Anthropic Claude Code, and Google Antigravity CLI each completed one isolated first-pass run of all five FinCore Reliability Lab tasks without human repair. Codex scored **495/500**, Claude scored **490/500**, and Antigravity scored **485/500**. No agent triggered a financial-safety veto.

> 中文摘要：三套 Coding Agent 使用相同缺陷提交、任务提示、隐藏测试和 100 分 Rubric 完成五题首轮实测。Codex 495/500，Claude 490/500，Antigravity 485/500。Antigravity 总耗时仅 1,004 秒，是本轮最快；三者均未触发资金安全一票否决。

## Results

| Task | Failure mode | Codex | Claude | Antigravity | Codex private | Claude private | Antigravity private |
|---|---|---:|---:|---:|---:|---:|---:|
| FC-001 | Duplicate settlement | 95 | 90 | 90 | 4/5 | 3/5 | 3/5 |
| FC-002 | Illegal terminal overwrite | 100 | 100 | 100 | 5/5 | 5/5 | 5/5 |
| FC-003 | Fee hot account | 100 | 100 | 100 | 5/5 | 5/5 | 5/5 |
| FC-004 | Scale-down takeover | 100 | 100 | 100 | 5/5 | 5/5 | 5/5 |
| FC-005 | Duplicate compensation | 100 | 100 | 95 | 5/5 | 5/5 | 5/5 |
| **Total** | **Five tasks** | **495/500** | **490/500** | **485/500** | **24/25** | **23/25** | **23/25** |

| Agent | Perfect runs | Public tests | Agent time | Safety vetoes |
|---|---:|---:|---:|---:|
| OpenAI Codex CLI · gpt-5.6-sol | 4/5 | 70/70 | 1,762 s | 0 |
| Anthropic Claude Code · sonnet | 4/5 | 83/83 | 2,335 s | 0 |
| Google Antigravity CLI · Gemini 3.7 Flash (High) | 3/5 | 80/81 | 1,004 s | 0 |

Public counts include agent-authored tests and are not a direct quality comparison.

## What separated the runs

FC-001 remained the hardest task. Codex covered four of five private scenarios. Claude and Antigravity each covered three. Antigravity prevented repeated posting, but a conflicting replay did not preserve the original payload contract and the settlement message ID lacked a second database uniqueness boundary. These were correctness and defense-in-depth gaps, not duplicate-funds events.

Antigravity passed all public and private scenarios on FC-002 through FC-004. On FC-005, its compensation implementation passed all five private scenarios, including cross-context concurrency, rollback and retry, and single reverse-ledger effect. One additional test authored by the agent asserted that the existing reconciliation service must return zero after compensation even though that reconciliation scope was not part of the task contract. The implementation therefore received 95/100 for a first-pass test-quality failure, without a financial-safety veto.

## Runtime, tokens, and cost

Antigravity completed the five runs in 1,004 seconds. It was 758 seconds (43.0%) faster than Codex and 1,331 seconds (57.0%) faster than Claude in this pass.

Antigravity reported 2,164,222 total model tokens: 1,924,971 input and 239,251 output, with 177,191 thinking tokens and 14,247,164 cache-read tokens reported separately. Vendor token accounting differs, so these values are preserved as telemetry rather than used for cross-vendor efficiency ranking.

Claude Code reported an estimated total of $5.3438274. Codex and the Google AI Pro-authenticated Antigravity session did not expose per-run dollar charges, so this benchmark does not rank cost.

## Controlled protocol

All three agents received:

- the same five intentional-defect source commits;
- the same task prompts and financial guardrails;
- a sanitized one-commit repository without a Git remote or reference defect patch;
- the same public suite, five-scenario private grader, and 100-point Rubric;
- one valid run per task with no human repair.

The private grader, hidden test implementations, raw transcript, model patch, and authentication material were never mounted into the candidate workspace. CLI-specific runners differed only where required by each product's isolation and authentication interface.

Antigravity CLI 1.1.22 ran with the exact recorded model name `Gemini 3.7 Flash (High)`, accept-edits mode, an unprivileged container, a read-only root filesystem, dropped Linux capabilities, no-new-privileges, explicit workspace-only file permissions, and authentication-only network access. The report does not infer an unverified underlying model snapshot.

## Interpretation

Codex led this single pass by ten points and covered one more hidden scenario. Claude finished second. Antigravity finished third on score but first on elapsed time, while still producing production-shaped solutions for state-machine CAS, deterministic fee sharding, fencing, and compensation uniqueness.

This is not a universal model ranking. One run per model and task does not measure variance, and the benchmark covers Java/PostgreSQL financial reliability rather than general software engineering.

Machine-readable files:

- [Codex summary](summary.json)
- [Claude summary](claude-summary.json)
- [Antigravity summary](antigravity-summary.json)
- [Cross-agent comparison](comparison.json)

The earlier [Claude Code vs Codex report](claude-vs-codex.md) is retained as a frozen two-agent snapshot.
