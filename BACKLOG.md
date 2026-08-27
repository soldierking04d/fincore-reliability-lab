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
- [ ] Add a static comparison page generated from scorecards.

## P1 — Reliability experiments

- Kafka Dead Letter Topic and reviewed replay workflow
- Compensation concurrency integration suite
- Reconciliation batches, cursoring, partitions, and issue lifecycle
- Toxiproxy latency, disconnect, and connection-pool exhaustion
- Kafka rebalance storm and offset-commit failure
- PostgreSQL failover simulation
- Single-hot-account versus sharded-account capacity baseline

## P2 — Production-readiness research

- Authentication and role-based authorization
- Secrets management and encryption
- Disaster-recovery runbook and restore verification
- Multi-instance deployment topology
- Capacity certification and service-level objectives
- Independent security and financial-control review
