# Generated reports

`docker compose up --build` automatically executes the full laboratory scenario after the application becomes healthy. The JSON result is written to `latest-scenario.json` in this directory.

The generated report is intentionally not committed as a golden result because every run creates isolated account IDs and timestamps.

## Coding-agent evaluations

Controlled, sanitized benchmark reports are versioned under `evaluations/`. They publish scorecards, run metadata, token usage, and scenario-level outcomes without exposing hidden test code, full agent transcripts, or answer patches.

- [FC-001 · Codex gpt-5.6-sol · run 001](evaluations/FC-001/codex-gpt-5.6-sol-run-001/README.md) — 95/100
- [FC-002 · Codex gpt-5.6-sol · run 001](evaluations/FC-002/codex-gpt-5.6-sol-run-001/README.md) — 100/100
- [FC-003 · Codex gpt-5.6-sol · run 001](evaluations/FC-003/codex-gpt-5.6-sol-run-001/README.md) — 100/100
- [FC-004 · Codex gpt-5.6-sol · run 001](evaluations/FC-004/codex-gpt-5.6-sol-run-001/README.md) — 100/100

