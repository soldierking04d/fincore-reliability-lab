# Controlled coding-agent evaluations

Three coding agents completed the same five transaction-safety repairs under matched source commits, prompts, hidden graders, and scoring rules. This is a narrow case study, not a universal leaderboard: five tasks, one valid run per agent/task, no human baseline, and no confidence interval.

> 中文摘要：Codex、Claude Code 与 Google Antigravity 均完成五个金融可靠性修复任务。总分依次为 495、490、485；Antigravity 用时最短。三者均未触发资金安全一票否决。

## Agent totals

| Agent | Score | Perfect runs | Public tests | Private scenarios | Agent time | Vetoes |
|---|---:|---:|---:|---:|---:|---:|
| OpenAI Codex CLI · gpt-5.6-sol | 495/500 | 4/5 | 70/70 | 24/25 | 1,762 s | 0 |
| Anthropic Claude Code · sonnet | 490/500 | 4/5 | 83/83 | 23/25 | 2,335 s | 0 |
| Google Antigravity CLI · Gemini 3.7 Flash (High) | 485/500 | 3/5 | 80/81 | 23/25 | 1,004 s | 0 |

Public test counts include agent-authored tests and are therefore not a direct cross-agent quality metric.

## Task scores

| Task | Failure mode | Codex | Claude | Antigravity |
|---|---|---:|---:|---:|
| FC-001 | Duplicate settlement | 95 | 90 | 90 |
| FC-002 | Illegal terminal overwrite | 100 | 100 | 100 |
| FC-003 | Fee hot account | 100 | 100 | 100 |
| FC-004 | Scale-down takeover | 100 | 100 | 100 |
| FC-005 | Duplicate compensation | 100 | 100 | 95 |

## Reports and data

- [Bilingual evaluation note](https://fincore-agent-benchmark.soldierking04d.chatgpt.site)
- [Evidence-backed three-agent comparison, design references, and limitations](coding-agent-comparison.md)
- [Machine-readable comparison](comparison.json)
- [Codex five-task summary](summary.json)
- [Claude five-task summary](claude-summary.json)
- [Antigravity five-task summary](antigravity-summary.json)
- [Frozen Claude vs Codex snapshot](claude-vs-codex.md)
- [FC-001 Codex evidence](FC-001/codex-gpt-5.6-sol-run-001/README.md)
- [FC-002 Codex evidence](FC-002/codex-gpt-5.6-sol-run-001/README.md)
- [FC-003 Codex evidence](FC-003/codex-gpt-5.6-sol-run-001/README.md)
- [FC-004 Codex evidence](FC-004/codex-gpt-5.6-sol-run-001/README.md)
- [FC-005 Codex evidence](FC-005/codex-gpt-5.6-sol-run-001/README.md)

## Interpretation boundary

This is one valid run per model and task, not a universal leaderboard. Cross-agent conclusions are limited to this Java/PostgreSQL financial-reliability benchmark. Additional valid runs are needed to measure variance.

Raw transcripts, hidden test implementations, answer patches, and authentication material remain private. Public evidence is intentionally limited to scorecards, runtime metadata, token/cost summaries, timing corrections, and sanitized scenario outcomes.
