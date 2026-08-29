# FinCore benchmark introduction

FinCore is a small Java repair benchmark built around failures that can move money twice, corrupt a terminal state, or allow a stale worker to write after ownership has changed. It does not ask whether an agent can produce plausible code. It asks whether the financial result remains unique, balanced and auditable under concurrency, retry and partial failure.

## Part I — What the benchmark protects

### Overview

The five tasks start from code that compiles. Each defect is easy to miss in a happy-path review and expensive to discover in production. An agent receives the same broken source, task brief and public tests. It must repair the system without deleting tests, weakening database constraints or moving final consistency into memory or Redis.

### Business and engineering views

| Failure | Business consequence | Engineering boundary |
|---|---|---|
| Duplicate settlement | One customer action creates two balance movements | Database-owned idempotency for message and business keys |
| Terminal overwrite | A completed settlement later appears failed | Legal state transitions, CAS and immutable audit history |
| Fee hot account | A shared account limits throughput during a traffic spike | Deterministic sharding without losing exact aggregation |
| Scale-down takeover | Old and new workers both believe they own a range | Lease epoch and fencing checked at financial commit time |
| Duplicate compensation | A retry reverses the same posting twice | One compensation order and an immutable reverse journal |

The business statement defines the damage. The engineering statement defines the invariant that the grader must observe after competing requests, process restarts, rollback and retry.

### One concrete example

FC-001 replays a message after an uncertain response. The minimum business requirement is simple: do not post twice. The technical requirement is stricter. A second process, a different message carrying the same business key, and a conflicting payload must all reach a stable result through database constraints and one transaction boundary. Returning `200 OK` is not enough evidence.

## Part II — How the evaluation works

### Controlled repair

Each run uses a one-commit candidate repository with no remote, reference patch or hidden grader. The agent sees the task brief and public tests. When it stops, the runner records its patch, runtime, model settings, elapsed time and available token or cost data. Only then are five private scenarios introduced.

### Scoring and vetoes

The shared rubric covers functional correctness, concurrency, transactions, idempotency, recovery, financial safety, tests, performance, maintainability and observability. A numeric score cannot rescue a financially unsafe solution. Repeated posting, floating-point money, illegal terminal transitions, mutable ledger history, swallowed failures, deleted tests, or memory/Redis as the final ledger trigger an immediate veto.

### Evidence boundary

Public reports contain source commits, model and runner versions, scorecards, timing, usage summaries and sanitized scenario outcomes. Hidden tests, answer patches, raw transcripts and credentials remain private while the tasks are active so later agents cannot retrieve a reference solution.

### Current limitation and next release

The first publication contains five tasks and one valid run per agent/task. It is a controlled case study, not a statistically significant leaderboard. The next evidence release repeats every agent/task combination three times and reports run-to-run variance, first-attempt success, elapsed-time distribution and comparable usage data where the tools expose it.
