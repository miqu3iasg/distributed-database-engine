---
title: "ReadIndex for linearizable reads, with lease reads as a later optimization"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0011-readindex-for-linearizable-reads-with-lease-reads-as-a-later-optimizat.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: ad5523f3-305d-4d2f-bf7d-9fcd9984c9cb
---

# ADR-011 ReadIndex for linearizable reads, with lease reads as a later optimization

## Context

Reads are the majority of most workloads. A naive implementation sends every read through the Raft log, which makes reads as expensive as writes (a disk `fsync` on a majority). A naive optimization, letting the leader answer reads from local state, is **unsafe**: a leader that has been deposed by a partition may not know it, and could serve stale data, violating linearizability.

Safe alternatives exist, with different trade-offs between latency and assumptions about clocks.

## Decision

Implement read paths in this order, each gated by the linearizability checker (ADR-0012):

1. **Baseline: `ReadIndex`** (Raft thesis §6.4).
    - The leader must have committed an entry in its current term (the no-op appended at election time) before it may serve reads.
    - On a read, it records the current `commitIndex` as the `readIndex`, confirms it is still leader by exchanging heartbeats with a majority, waits until `appliedIndex >= readIndex`, and then serves the read locally.
    - No log write and no dependency on clock accuracy; one network round trip to a majority.
2. **Optimization: lease reads.**
    - The leader holds a lease, renewed through acknowledged heartbeats, whose duration is strictly shorter than `electionTimeout - max_clock_offset`.
    - While the lease is valid, reads are served locally with no network round trip.
    - Safety depends on the clock-offset bound from ADR-0007. The simulator will inject skew both below and above the bound to demonstrate the exact boundary where linearizability breaks, and to confirm the node's self-protection behavior.
    - Lease reads are configurable and may be disabled per cluster.
3. **Stretch: follower reads** at a _closed timestamp_, returning snapshot data with bounded and explicitly declared staleness; not linearizable, clearly labeled in the API (`STALE_BOUNDED`).

Writes always go through the Raft log.

## Alternatives Considered

### Read through the log

Always correct and trivial to implement, but costs a full consensus round with disk `fsync` per read. Acceptable only as an initial test oracle, not as the default.

### Leader serves reads without confirmation

Fast but unsafe, as explained above. Rejected.

### Lease reads only

Fastest, but makes correctness depend on clocks from the start and removes a clock-independent baseline for comparison. Rejected as the first step.

### Follower reads by default

Offloads leaders but returns stale data. Rejected as a default; provided as an explicit opt-in.

## Consequences

### Positive

- Linearizable reads without log writes from day one, with no clock dependency.
- Lease reads give a measurable latency and throughput improvement, with a documented safety condition.
- The three modes produce an interesting, honest benchmark comparison.

### Negative

- `ReadIndex` still needs a majority round trip, so read latency is network-bound.
- Lease reads reintroduce a clock assumption; mis-configuration of `max_clock_offset` can silently cause stale reads.
- Several read paths increase the test matrix, and the leader-with-no-committed-entry-in-term edge case needs explicit coverage.

## References

- Ongaro, [Consensus: Bridging Theory and Practice](https://web.stanford.edu/~ouster/cgi-bin/papers/OngaroPhD.pdf), Section 6.4 (processing read-only queries more efficiently)
- Ongaro & Ousterhout, [In Search of an Understandable Consensus Algorithm (Extended Version)](https://raft.github.io/raft.pdf), Section 8 (client interaction)
- [etcd-io/raft](https://github.com/etcd-io/raft) and [tikv/raft-rs](https://github.com/tikv/raft-rs) (reference implementations of `ReadIndex`; raft-rs exposes the choice as [`ReadOnlyOption`](https://docs.rs/crate/raft/latest/source/src/read_only.rs), with `Safe` meaning ReadIndex and `LeaseBased` meaning lease reads)
- [[adr-0007-hybrid-logical-clock-for-transaction-timestamps]]
- [[adr-0010-multi-raft-ranges-with-one-data-engine-per-store-and-splits-without-da]]
- [[adr-0012-verification-strategy-deterministic-simulation-linearizability-and-iso]]
