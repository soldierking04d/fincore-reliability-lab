# Security Policy

FinCore Reliability Lab is an educational and evaluation project, not a production custody, payment, or trading system.

## Supported version

Only the latest commit on `main` is maintained.

## Reporting a vulnerability

Use GitHub's private vulnerability reporting feature when it is available for this repository. Do not open a public issue for a suspected vulnerability involving credential exposure, unauthorized access, or a financial-integrity bypass.

Include the affected commit, reproduction steps, expected invariant, observed result, and whether the issue can produce an incorrect financial effect.

## Non-production controls

- Services bind to loopback by default.
- Fault-injection endpoints require the `lab` Spring profile.
- Compose credentials are local demonstration defaults, not production secrets.
- No real customer, account, transaction, employer, or infrastructure data belongs in this repository.
- Never expose the lab profile directly to an untrusted network.

