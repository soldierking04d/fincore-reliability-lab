# FinCore Coding Agent Evaluation Kit

This public kit defines five production-shaped repair tasks for coding agents. Each task starts from a deliberately defective branch and is scored with the same 100-point rubric. Public checks describe the contract; hidden tests belong in a separate private grader repository so an agent cannot optimize against their implementation.

## Task branches

| Task | Defect branch | Core risk |
|---|---|---|
| Duplicate settlement | `benchmark/duplicate-settlement` | Repeated financial effect |
| Illegal status overwrite | `benchmark/illegal-status-overwrite` | Terminal state corruption |
| Fee hot account | `benchmark/fee-hot-account` | Lock contention and unsafe aggregation |
| Scale-down takeover | `benchmark/scale-down-takeover` | Stale worker writes |
| Duplicate compensation | `benchmark/duplicate-compensation` | Repeated reversal |

## Evaluation protocol

1. Reset a clean worktree to the selected defect branch.
2. Give the agent only that task's `agentPrompt`, repository instructions, and public tests.
3. Record model/version, tool configuration, start/end timestamps, token or monetary cost, prompts, patches, test output, and repair iterations.
4. Run public tests, then the separately stored hidden grader.
5. Apply `rubric.json` and record veto rules before calculating a numeric score.
6. Preserve the full evidence bundle. Do not score from a prose answer alone.

Validate the public kit with:

```bash
./scripts/eval/validate-eval-kit.sh
```

After the verified `main` commit exists and the worktree is clean, create all five branches reproducibly with:

```bash
./scripts/eval/create-defect-branches.sh
```

The source-controlled patches under `evals/defects/` are the canonical defect definitions. The generator applies one patch per branch, creates a clearly labeled intentional-defect commit, and returns to `main`.

`scorecard.schema.json` defines the portable result format for later comparisons across Codex, Claude Code, Gemini, Cursor, and other coding agents.
