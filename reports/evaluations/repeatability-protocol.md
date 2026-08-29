# Repeatability protocol

The first FinCore comparison froze one valid run for each of three agents across five tasks. The next phase measures whether those outcomes repeat.

## Run matrix

- Agents: Codex CLI, Claude Code and Google Antigravity CLI.
- Tasks: FC-001 through FC-005.
- Valid runs: three per agent/task combination.
- Source: the same frozen intentional-defect commit for every repeat.
- Interaction: one prompt, no human repair round and no second attempt after grading.

Runs are sequential on the same Ubuntu ARM64 laboratory VM. This avoids CPU, Docker and database contention changing elapsed-time comparisons. Model, CLI, reasoning setting, container image and source commit are recorded per run.

## Validity rules

A run is valid only when:

1. the candidate repository contains one commit, no remotes and no reference patches;
2. the hidden grader is absent until the agent process has exited;
3. the patch and runtime metadata are captured before private tests begin;
4. the runner exits normally or records a model failure as an observed result;
5. no human changes the candidate between agent exit and grading.

Infrastructure failures are retained for runner debugging but excluded from model pass-rate calculations. Exclusions must state the reason; they are never silently replaced.

## Reported measures

| Measure | Calculation |
|---|---|
| Valid-run completion | Completed valid runs / planned valid runs |
| Exact hidden pass rate | Private scenarios passed / private scenarios executed |
| First-attempt task success | Runs meeting the task's passing threshold without a veto |
| Score distribution | Minimum, median and maximum rubric score |
| Elapsed time | Minimum, median and maximum agent wall time |
| Usage | Input, cached input, output and reasoning tokens when exposed |
| Cost | Tool-reported currency cost only; no invented conversion for subscription sessions |
| Safety veto rate | Runs triggering at least one financial-safety veto / valid runs |

With three runs, these statistics describe observed variance but still do not justify broad population claims or a confidence interval. The report will say that explicitly.

## Publication boundary

The public run index will include run identity, frozen source commit, runner/model settings, timing, usage totals, public-test totals, sanitized private-scenario counts, score and vetoes. It will not publish active-task answer patches, raw transcripts, hidden test code or authentication material.

Cryptographic digests may be published for retained private artifacts. They prove that a later file matches the retained evidence; they do not make the hidden content independently auditable.
