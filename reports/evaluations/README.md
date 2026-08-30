# Controlled coding-agent evaluations

Three coding agents completed the same five Java/PostgreSQL transaction-safety repairs under matched source commits, prompts, hidden graders, and scoring rules. The first publication used one run per agent/task. The repeatability release now covers **45 controlled attempts**: three runs for every agent/task pair, with no human repair round.

> 中文摘要：45 次重复评测没有产生一个“全能冠军”。Codex 的端到端完整通过率最高（12/15），Claude 的计划隐藏场景通过率最高（70/75），Antigravity 中位耗时最短（188 秒），但出现两次无法编译的补丁。

## Repeatability totals

| Agent | Clean runs | Planned hidden scenarios | Median agent time | Build failures |
|---|---:|---:|---:|---:|
| OpenAI Codex CLI · gpt-5.6-sol | 12/15 (80.0%) | 66/75 (88.0%) | 369 s | 0 |
| Anthropic Claude Code · sonnet | 11/15 (73.3%) | 70/75 (93.3%) | 447 s | 0 |
| Google Antigravity CLI · Gemini 3.7 Flash (High) | 10/15 (66.7%) | 61/75 (81.3%) | 188 s | 2 |

A clean run requires a successful agent exit, a passing complete public suite, and all five hidden scenarios. Build failures count against the planned hidden-case denominator. Public test counts are not compared across agents because agents may add tests.

## First-run scorecard

The original subjective Rubric scores remain historical evidence: Codex 495/500, Claude 490/500, and Antigravity 485/500. They are no longer the primary ranking because the 30 repeat patches have deterministic test and timing results but have not received a full subjective 100-point review.

## Reports and data

- [Bilingual benchmark site](https://fincore-agent-benchmark.soldierking04d.chatgpt.site)
- [Three-run repeatability report](repeatability-results.md)
- [Machine-readable repeatability aggregate](repeatability-results.json)
- [Sanitized 30-run index](repeat-progress.json)
- [Repeatability protocol](repeatability-protocol.md)
- [Repeated-run evidence notes](repeated-runs/README.md)
- [Sanitized index schema](run-index.schema.json)
- [First-run three-agent comparison](coding-agent-comparison.md)
- [Business and engineering benchmark introduction](../../docs/agent-evaluations/benchmark-introduction.md)
- [First-run machine comparison](comparison.json)
- [Codex first-run summary](summary.json)
- [Claude first-run summary](claude-summary.json)
- [Antigravity first-run summary](antigravity-summary.json)

## Interpretation boundary

This is a narrow case study in Java, Spring Boot, PostgreSQL and financial reliability, not a universal coding-agent leaderboard. Three attempts per agent/task expose meaningful run-to-run variance but do not establish a population confidence interval or a general software-engineering ranking.

Raw transcripts, hidden test implementations, answer patches, and authentication material remain private. Public evidence is intentionally limited to scorecards, runtime metadata, available usage/cost summaries, deterministic scenario outcomes, hashes and methodology.
