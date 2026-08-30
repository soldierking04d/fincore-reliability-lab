# Three-run repeatability report

The first FinCore publication showed one run per agent/task. This report adds two fresh runs for every combination: **45 controlled attempts in total**, with no human repair round.

## Result in one sentence

There is no single winner. Codex had the highest end-to-end clean-run rate, Claude passed the most planned hidden scenarios, and Antigravity remained the fastest but produced two non-compiling patches.

## Three-run totals

| Agent / model | Clean runs | Hidden scenarios, planned basis | Median agent time | Build failures |
|---|---:|---:|---:|---:|
| Codex CLI · gpt-5.6-sol | 12/15 (80.0%) | 66/75 (88.0%) | 369 s | 0 |
| Claude Code · sonnet | 11/15 (73.3%) | 70/75 (93.3%) | 447 s | 0 |
| Antigravity · Gemini 3.7 Flash (High) | 10/15 (66.7%) | 61/75 (81.3%) | 188 s | 2 |

A **clean run** means the agent exited, the complete public suite passed, and all five hidden scenarios passed. Build failures remain model failures. They are not converted into zero-test successes or removed from the denominator.

## What the numbers say

- **Codex: strongest end-to-end completion.** It produced 12 clean runs out of 15. Its weak repeats were one FC-002 state-machine repair that failed all five hidden cases and one FC-005 compensation repair that passed two of five.
- **Claude: strongest hidden-case correctness.** It passed 70 of 75 planned hidden cases. Two patches still failed their own public suites, including a compensation concurrency test that exposed a primary-key conflict, so hidden correctness alone would overstate readiness.
- **Antigravity: fastest, with more delivery risk.** Its median agent time was 188 seconds, roughly half of Codex and well below Claude. It also produced two patches that did not compile and finished with 10 clean runs out of 15.

## Stability by task

| Task | Clean runs | Codex | Claude | Antigravity |
|---|---:|---:|---:|---:|
| FC-001 | 2/9 | 2/3 | 0/3 | 0/3 |
| FC-002 | 7/9 | 2/3 | 3/3 | 2/3 |
| FC-003 | 9/9 | 3/3 | 3/3 | 3/3 |
| FC-004 | 9/9 | 3/3 | 3/3 | 3/3 |
| FC-005 | 6/9 | 2/3 | 2/3 | 2/3 |

FC-003 fee-account sharding and FC-004 scale-down fencing were the most stable: every agent passed every run. FC-001 duplicate settlement was the hardest: only two of nine attempts were completely clean, both from Codex. The persistent separation was not basic duplicate suppression; it was replay-contract stability and database-owned uniqueness under changed payloads.

## Why the first-run ranking changed

The original scores were 495, 490 and 485 out of 500, close enough to suggest all three agents were nearly equivalent. Repetition exposed differences the first table could not show:

- the same model could move from four hidden passes to five on FC-001;
- a public suite could pass while all hidden state-machine cases failed;
- hidden cases could pass while an agent-authored public concurrency test still found a defect;
- a fast agent could return a patch that did not compile.

The first-run scores remain historical evidence. They are no longer treated as the primary ranking.

## Scope and limits

This remains a narrow Java, Spring Boot and PostgreSQL financial-reliability benchmark. Three runs reveal meaningful variance, but they do not justify a broad claim about general software engineering or a population confidence interval. Token telemetry is vendor-specific. Only Claude exposed comparable CLI cost for every run; its reported total across 15 attempts was **$16.58**.

Repeat patches have deterministic test and timing results, but not yet a full subjective 100-point rubric review. This report therefore ranks repeatability using clean runs, planned hidden scenarios and elapsed time rather than inventing precise numeric scores.

## Evidence

- [Sanitized 30-run index](repeat-progress.json)
- [Machine-readable aggregate](repeatability-results.json)
- [Repeatability protocol](repeatability-protocol.md)
- [Public task kit](../../evals/README.md)
