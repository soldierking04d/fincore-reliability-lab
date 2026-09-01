# Backlog

## Completed in v0.3

- [x] Shard owner/epoch validation inside financial writes
- [x] Outbox abandoned-claim recovery
- [x] Fee sharding and idempotent aggregation
- [x] Complete automated reliability scenario
- [x] Pure-JDK concurrency simulation
- [x] Ubuntu ARM64 VM deployment and verification
- [x] Prometheus and provisioned Grafana dashboard
- [x] Bilingual public documentation and demonstration script
- [x] Portable coding-agent task, rubric, and scorecard definitions
- [x] Reproducible intentional-defect patches and benchmark branch generator

## P0 — Benchmark release

- [x] Publish five reproducible `benchmark/*` defect branches.
- [x] Create the separate private hidden-grader repository.
- [x] Add a runner that captures prompts, patches, test output, duration, iterations, and available usage data.
- [x] Execute the first controlled FC-001 Codex run and publish an evidence-backed report.
- [x] Execute FC-002 with calibrated hidden tests and publish the second evidence-backed report.
- [x] Execute FC-003 with calibrated hidden tests and publish the third evidence-backed report.
- [x] Execute FC-004 with calibrated hidden tests and publish the fourth evidence-backed report.
- [x] Execute FC-005 with calibrated hidden tests and publish the fifth evidence-backed report.
- [x] Add a static comparison page derived from the five scorecards.

## P1 — Reliability experiments

- Kafka Dead Letter Topic and reviewed replay workflow
- Compensation concurrency integration suite
- Reconciliation batches, cursoring, partitions, and issue lifecycle
- Toxiproxy latency, disconnect, and connection-pool exhaustion
- Kafka rebalance storm and offset-commit failure
- PostgreSQL failover simulation
- Single-hot-account versus sharded-account capacity baseline

## P2 — Production-readiness research

- [x] Publish the technical-lead responsibility map and management playbooks
- [x] Add machine-readable service, risk, metric, technology-radar, and audit-evidence registers
- [x] Enforce governance cross-references, review dates, AI release gates, and evidence paths in Maven/CI
- Authentication and role-based authorization
- Secrets management and encryption
- Disaster-recovery runbook and restore verification
- Multi-instance deployment topology
- Capacity certification and service-level objectives
- Independent security and financial-control review
- Software bill of materials, dependency/secret scanning, artifact provenance, and staged security gates
- Data classification, retention, access review, and audit-control evidence
- FinOps baseline, unit economics, vendor scorecards, and tested exit plans
- Read-only AI workflow prototypes for incident triage, reconciliation explanation, support summaries, and capacity analysis
- Independent legal review of open-source licenses, notices, intellectual property, and release artifacts
- Measured AI unit economics and resource/sustainability baselines using auditable provider data
- EVM/Bitcoin chain indexer, confirmation policy, canonical-chain replay, and reorganization fault laboratory
- Digital-asset deposit/withdrawal state machines with Nonce/UTXO coordination and transaction replacement tracking
- HSM/MPC signing boundary, hot/cold-wallet controls, key-lifecycle exercises, and independent custody security review
- On-chain, wallet-inventory, internal-ledger, and customer-projection reconciliation with auditable adjustment workflows

The [management handbook](docs/management/README.md) defines how these controls should be owned and verified;
the [governance registers](governance/README.md) record current owners, review dates, limitations, and evidence.
Items without executable controls, target-environment tests, or independent review remain research backlog and
must not be described as production certification.
