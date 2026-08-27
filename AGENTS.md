# AI Coding Guardrails

This repository models financial state. Any coding agent working here must obey these rules:

1. Never use `float` or `double` for money.
2. Never treat Redis, a JVM map, or a Kafka offset as the final financial ledger.
3. Never update or delete historical ledger entries; corrections are reverse journals.
4. Preserve database unique constraints for idempotency.
5. Keep balance changes, journal postings, state transition, inbox and outbox writes inside an explicit transaction boundary.
6. Lock accounts in deterministic UUID order.
7. A `SUCCESS` settlement is terminal. Reversal is represented by a separate compensation order.
8. Do not catch an exception and return success.
9. Do not weaken or delete tests to make a build pass.
10. Fault injection must remain behind the `lab` profile.
11. A reconciliation difference is frozen for review by default; do not silently repair it.
12. Explain concurrency, retry and partial-failure behavior in every material change.
13. Do not expose a public HTTP endpoint that bypasses Kafka worker fencing and calls settlement directly.

Before changing financial code, state the invariant being preserved and add a test that fails without the change.
