---
title: "Transaction record as the commit point, with Snapshot Isolation before Serializable"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0009-transaction-record-as-the-commit-point-with-snapshot-isolation-before.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: e0305256-0b27-4712-b83c-e974426ffb8f
---

# ADR-009 Transaction record as the commit point, with Snapshot Isolation before Serializable

## Context

GeoDB must support transactions that touch keys in multiple ranges, each a separate Raft group on different nodes. The transaction must be atomic (all writes become visible or none do) even if the coordinator or any participant crashes midway, and it must offer a well-defined isolation level.

Classic two-phase commit needs a durable, highly available coordinator; otherwise a coordinator crash blocks participants indefinitely. Since every range is already replicated with Raft, the transaction state can itself be stored in a replicated range, which removes the single point of failure.

Isolation is a second axis. Serializable isolation in a distributed MVCC system requires extra machinery (read tracking, timestamp pushing, read refresh, uncertainty handling). Building it first would obscure whether the underlying transaction layer is correct.

## Decision

### Commit protocol

1. A transaction has a `txnId`, a `readTs`, a provisional `writeTs`, a priority, and an **anchor key** (its first written key).
2. Each write lays down a **write intent** (provisional MVCC version carrying `TxnMeta`) through Raft in the key's range.
3. A **transaction record** (`PENDING`, `COMMITTED`, `ABORTED`) lives in the range of the anchor key and is replicated by that range's Raft group.
4. **Commit point:** a transaction is committed at the instant its record is written as `COMMITTED` in one atomic Raft proposal. Intent resolution in other ranges happens asynchronously afterward.
5. A reader or writer that meets an intent consults the transaction record, then resolves it as committed, removes it as aborted, or pushes or waits if the owner is alive. Expired heartbeats allow any party to abort an abandoned transaction.
6. The coordinator runs at the gateway node, heartbeating the record. A client that loses contact during commit receives an `AmbiguousResult` and can resolve it by querying the record by `txnId`.
7. Deadlocks are initially handled by timeouts plus priorities; a distributed wait-for graph is a later refinement.

### Isolation roadmap

- **Phase 4:** **Snapshot Isolation** within a single range (reads at a fixed `readTs`, first-committer-wins on write-write conflicts). The _write skew_ anomaly is explicitly tested and documented.
- **Phase 6:** distributed transactions and **Serializable** isolation, adding a per-range **timestamp cache**, **read refresh**, and **uncertainty intervals** (ADR-0007). The timestamp cache's low-water mark is raised after leadership changes to preserve safety.
- Correctness is checked by an Elle-style cycle checker (ADR-0012), which must detect write skew under Snapshot Isolation and find no cycles under Serializable.

## Alternatives Considered

### Classic 2PC with a dedicated coordinator service

Simple to explain, but the coordinator is a single point of failure and blocking. Rejected in favor of storing coordination state in a replicated range.

### Percolator-style primary/secondary locks

Equivalent in spirit (the primary lock acts as the commit point) and valid. Not chosen because the explicit transaction record gives simpler heartbeats, priorities, and push semantics, and aligns with the CockroachDB architecture used as reference.

### Calvin-style deterministic transactions

Elegant (pre-ordered transactions, no 2PC) but requires knowing read/write sets up front, which conflicts with interactive transactions.

### Serializable from the start

Fewer isolation levels to explain, but it would make the first transactional milestone much larger and conflate bugs in the commit protocol with bugs in the isolation machinery.

### Parallel commits (single consensus round)

An optimization that adds a `STAGING` state. Deferred as a stretch goal until the baseline protocol is verified.

## Consequences

### Positive

- No separate coordinator availability problem; transaction state has the same fault tolerance as a range.
- Atomicity reduces to one Raft write, which is easy to reason about and to test.
- The staged isolation plan gives a verifiable intermediate milestone and a concrete demonstration of write skew appearing and then disappearing.

### Negative

- Commit latency is at least one consensus round at the anchor range plus the writes of intents, and reads may pay for intent resolution.
- Abandoned transactions leave intents that require liveness timeouts and cleanup jobs.
- Serializable support (timestamp cache, refresh, uncertainty) adds significant complexity and tuning, and relies on the clock-offset assumption of ADR-0007.
- Timeout-based deadlock handling can abort transactions unnecessarily until a wait-for graph is implemented.

## References

- Taft et al., [CockroachDB: The Resilient Geo-Distributed SQL Database (SIGMOD 2020)](https://doi.org/10.1145/3318464.3386134)
- Cockroach Labs, [Parallel Commits: An Atomic Commit Protocol for Globally Distributed Transactions](https://www.cockroachlabs.com/blog/parallel-commits/)
- Peng & Dabek, [Large-scale Incremental Processing Using Distributed Transactions and Notifications (Percolator)](https://www.usenix.org/legacy/event/osdi10/tech/full_papers/Peng.pdf)
- Thomson et al., [Calvin: Fast Distributed Transactions for Partitioned Database Systems (SIGMOD 2012)](https://doi.org/10.1145/2213836.2213838)
- Berenson et al., [A Critique of ANSI SQL Isolation Levels](https://arxiv.org/abs/cs/0701157)
- Cahill et al., [Serializable Isolation for Snapshot Databases](https://doi.org/10.1145/1620585.1620587)
- Kingsbury & Alvaro, [Elle: Inferring Isolation Anomalies from Experimental Observations](https://arxiv.org/abs/2003.10554) and the [Elle implementation](https://github.com/jepsen-io/elle)
- [[adr-0007-hybrid-logical-clock-for-transaction-timestamps]]
- [[adr-0008-mvcc-key-encoding-with-inverted-timestamps]]
- [[adr-0012-verification-strategy-deterministic-simulation-linearizability-and-iso]]
