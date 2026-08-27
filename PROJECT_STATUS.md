# Project Status — v0.3.0

## Completed

- [x] Docker Compose infrastructure with loopback-only port binding
- [x] PostgreSQL Flyway V1/V2 schema
- [x] Account, balance, and append-only balanced journal
- [x] Kafka settlement command and consumer
- [x] Transactional Inbox/Outbox with abandoned-claim recovery
- [x] Message-ID and business-key idempotency
- [x] Conditional debit and deterministic account locking
- [x] CAS settlement state transitions and audit
- [x] Separate idempotent compensation order and reverse journal
- [x] Balance-to-ledger reconciliation and high-risk issue workflow
- [x] Shard lease, drain, epoch, and data-plane fencing
- [x] Idempotent fee-shard provisioning and aggregation
- [x] Prometheus scrape configuration
- [x] Provisioned Grafana reliability dashboard
- [x] Lab-only duplicate and corruption fault injection
- [x] One-command complete laboratory scenario
- [x] Pure-JDK concurrency and recovery simulation
- [x] JUnit and PostgreSQL Testcontainers integration tests
- [x] k6 load-test scenario
- [x] Bilingual public documentation and demo walkthrough
- [x] Five-task public coding-agent evaluation kit and 100-point rubric
- [x] Five applicable intentional-defect patches and reproducible branch generator
- [x] Five public `benchmark/*` branches and v0.3.0 release
- [x] Separate private hidden grader and isolated Codex runner
- [x] First controlled FC-001 Codex report and machine-readable scorecard

## Verified on the Ubuntu 24.04 ARM64 laboratory VM

- Java 17 compilation and pure-domain verification passed.
- Maven/JUnit suite passed.
- PostgreSQL Testcontainers started and Flyway V1/V2 migrations passed.
- Docker Compose application, PostgreSQL, Kafka, Prometheus, and Grafana started.
- PostgreSQL and Kafka health checks passed.
- Prometheus successfully scraped the FinCore application.
- The automated scenario passed duplicate settlement, reverse-journal idempotency, fee aggregation, stale-epoch fencing, and reconciliation-corruption detection.
- The generated scenario report was persisted outside the runner container.
- The VM used private QEMU NAT; legacy DHCP and split-gateway services remained inactive.
- FC-001 Codex run 001 passed 11/11 public tests and 4/5 private scenarios exactly, with a 95/100 reviewed score and no financial-safety veto.

## Public-release boundary

The repository contains fictional data and generalized parameters. It excludes former-employer source code, production incidents, non-public capacity figures, customer information, credentials, private keys, and machine-specific deployment paths.

## Next work

- Repeat FC-001 to measure run-to-run variance.
- Execute FC-002 through FC-005 under the same isolation protocol.
- Run controlled comparisons across multiple coding agents.
- Add Kafka Testcontainers to listener-level integration tests.
- Add DLT/replay, richer reconciliation batches, and failure-injection infrastructure.
- Add authentication and authorization before any non-lab exposure.
