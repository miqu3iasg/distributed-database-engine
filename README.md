# GeoDB

A distributed, transactional, ordered key-value database written in Java, built to keep working **and stay correct** when machines, disks, and networks fail, and to **prove it** with reproducible tests.

## What it is

GeoDB spreads data across several machines and keeps replicated copies in sync by majority vote, so clients see one database. Underneath it is an ordered key-value store (`byte[] -> byte[]`), the same base model as CockroachDB, TiKV, and Spanner, so richer layers such as SQL can be built on top later.

Why it exists: to build real depth in consensus, replication, MVCC, distributed transactions, and fault tolerance, and to produce **measured evidence** of correctness instead of claims.

## How it works, in short

- **Replication and consensus.** Each piece of data lives on three nodes by default. A custom **Raft** implementation keeps the copies from diverging; a write commits only when a majority has made it durable.
- **Sharding.** The key space is split into contiguous **ranges**, each its own Raft group. Ranges split automatically as they grow, without copying data, and are rebalanced across nodes.
- **Storage.** A custom **LSM-tree** engine per node, plus a separate shared Raft log engine with group commit.
- **Versions and time.** **MVCC** with a **Hybrid Logical Clock**, so readers never block writers.
- **Transactions.** Multi-key, multi-range, atomic. The replicated transaction record is the single commit point. Snapshot Isolation first, then Serializable.
- **Correctness first.** The core is deterministic, so the whole cluster can run in a simulator on virtual time with injected crashes, partitions, and disk faults, reproducible from a seed.

## What it promises (and does not)

| Promises                                                            | Does not promise                                                                                    |
| ------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| Linearizable single-key operations                                  | Working without a majority of replicas (it stops accepting writes instead of risking inconsistency) |
| Durability of acknowledged writes                                   | Being faster than a single-machine database on simple operations                                    |
| Atomic transactions                                                 | A full SQL database                                                                                 |
| Availability while a majority is up (3 replicas tolerate 1 failure) | Byzantine fault tolerance                                                                           |

## Roadmap

| Phase | Goal                                                         |
| ----- | ------------------------------------------------------------ |
| 0     | Skeleton, CI, determinism enforcement, simulated environment |
| 1     | Raft: elections and replication on a simulated network       |
| 2     | Replicated KV with linearizability checking                  |
| 3     | Own storage engine (WAL, LSM)                                |
| 4     | MVCC and transactions in one range (Snapshot Isolation)      |
| 5     | Sharding and Multi-Raft                                      |
| 6     | Distributed transactions and Serializable                    |
| 7     | Membership changes, rebalancing, lease reads                 |
| 8     | Observability, benchmarks, polish                            |

A phase ends only when all its acceptance criteria pass. Details are in the technical specification.

## How correctness is checked

Deterministic simulation with fault injection, a linearizability checker, an isolation-anomaly checker, a TLA+ model of Raft, fuzzing, mutation testing, and chaos tests with real processes. Measured results will be recorded in `docs/EVIDENCE.md` as they exist.

## Tech stack

Java (JDK 25 LTS toolchain, language level 21 for core modules), Maven multi-module, gRPC and Protocol Buffers, JUnit 5 / jqwik / JCStress / Jazzer / PIT, TLA+, JMH, Docker.

## Documentation

| Where                                                  | What                                              |
| ------------------------------------------------------ | ------------------------------------------------- |
| [`docs/README.md`](./docs/README.md)                   | Map of all documentation                          |
| [`docs/overview/`](./docs/overview)                    | General description and technical specification   |
| [`docs/adr/`](./docs/adr)                              | Architecture decision records                     |
| [`docs/guides/`](./docs/guides)                        | Commit, PR, documentation, and testing guidelines |
| [`GLOSSARY.md`](./GLOSSARY.md)                         | Shared vocabulary                                 |
| [`AGENTS.md`](./AGENTS.md), [`CLAUDE.md`](./CLAUDE.md) | Rules for AI coding agents                        |

## Contributing

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for how to contribute and where each kind of request goes.

## License

MIT — see [`LICENSE`](./LICENSE).
