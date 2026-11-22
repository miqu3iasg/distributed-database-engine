---
title: I/O-free Raft node driven by a Ready/Advance contract
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0004-io-free-raft-note-driven-by-a-ready-advance-contract.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: dc75930c-d646-4e5f-9d24-17ab12273025
---

# ADR-004 I/O-free Raft node driven by a Ready/Advance contract

## Context

Given ADR-0002 (own Raft) and ADR-0003 (deterministic core), the Raft module needs an API that exposes no I/O. It must also support Multi-Raft efficiently: a node hosts many Raft groups, and persistence must be batchable across all of them (see ADR-0005).

There are two broad API styles. A _callback/service_ style, where the Raft object calls the storage and transport itself, hides the protocol's ordering requirements and ties it to threads. A _state-machine_ style, where the object only transforms inputs into a description of required effects, keeps the protocol testable and lets the host control batching and scheduling.

Raft correctness depends on ordering: for example, a follower must persist entries before acknowledging them, and a vote must be durable before the vote response is sent.

## Decision

`RaftNode` is a single-threaded, I/O-free state machine in the style of `etcd/raft`:

```java
void tick();
void step(RaftMessage msg);
ProposeResult propose(byte[] data);
ProposeResult proposeConfChange(ConfChange change);
void readIndex(ReadCtx ctx);
void transferLeadership(NodeId target);
boolean hasReady();
Ready ready();
void advance(Ready rd);
```

`Ready` carries the soft state, the hard state (term, vote, commit), entries to persist, committed entries to apply, messages to send, an optional snapshot to install, and completed read states.

The host (the `Replica` in `geodb-range`) must honor this ordering contract:

1. Persist `hardState` and `entriesToPersist` with `fsync`.
2. Send messages that depend on that persistence (follower acknowledgements, vote grants). A leader may send `AppendEntries` in parallel with its own persistence.
3. Apply `committedEntries` to the state machine.
4. Call `advance(rd)`.

Raft message types are plain Java records inside `geodb-raft`. The mapping to Protobuf lives at the transport boundary (ADR-0013), so the core has no dependency on gRPC or Protobuf.

## Alternatives Considered

### Callback-driven Raft with injected `Storage` and `Transport`

Simple to use, but hides the ordering contract, encourages internal threading, and makes cross-group batching awkward. Rejected.

### Actor/message-passing framework (Akka-style)

Natural for isolating state, but introduces a heavy runtime and non-deterministic scheduling that conflicts with ADR-0003. Rejected.

### A synchronous API that blocks on persistence

Easiest to reason about locally, but it prevents group commit across ranges and wastes the fsync budget. Rejected.

## Consequences

### Positive

- The protocol logic is fully testable in isolation and in simulation.
- The host can batch the `Ready` of many ranges into a single `fsync` (group commit).
- The ordering requirements are explicit and checkable by simulation invariants.
- Proven in production by etcd and TiKV, which lowers design risk.

### Negative

- More ceremony for callers: the host loop must be written carefully, and violating the ordering contract creates safety bugs that are subtle.
- The `Ready`/`advance` pattern is unfamiliar to many Java developers and needs good documentation and tests.

## References

- [etcd-io/raft](https://github.com/etcd-io/raft) (origin of the `Ready`/`Advance` API style)
- [tikv/raft-rs](https://github.com/tikv/raft-rs)
- Ongaro, [Consensus: Bridging Theory and Practice](https://web.stanford.edu/~ouster/cgi-bin/papers/OngaroPhD.pdf), Chapters 3 and 10
- Ongaro & Ousterhout, [In Search of an Understandable Consensus Algorithm (Extended Version)](https://raft.github.io/raft.pdf)
- [Akka](https://akka.io/) (the actor framework considered and rejected)
- [[adr-0002-implement-raft-from-scratch-instead-of-adopting-a-library]]
- [[adr-0003-deterministic-core-and-environment-spi]]
- [[adr-0005-share-raft-log-engine-separate-from-the-lsm-data-engine]]
- [[adr-0013-grpc-and-protobuf-for-transport-with-translation-at-the-module-boundar]]
