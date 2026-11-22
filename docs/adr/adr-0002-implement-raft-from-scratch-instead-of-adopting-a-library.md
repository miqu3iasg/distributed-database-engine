---
title: Implement Raft from scratch instead of adopting a library
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0002-implement-raft-from-scratch-instead-of-adopting-a-library.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: 6efdd0b3-98a6-42f7-b35b-7e276ed9d97c
---

# ADR-002 Implement Raft from scratch instead of adopting a library

## Context

Every replicated range in GeoDB is a Raft group, so consensus sits at the center of correctness. There are mature Java options (Apache Ratis, SOFAJRaft) and mature implementations in other languages (etcd/raft, tikv/raft-rs, hashicorp/raft).

Two project requirements constrain the choice:

1. **Learning objective.** The project exists to build real understanding of consensus, including the parts that tutorials skip: snapshots, membership changes, PreVote, CheckQuorum, read linearizability, and recovery from torn writes.
2. **Deterministic simulation.** The strongest verification strategy available (ADR-0012) requires running the whole protocol on virtual time, a simulated network, and a simulated disk. That requires a consensus core that performs no I/O, owns no threads, and reads no clocks.

Existing Java libraries own their threads, timers, transport, and storage, and are designed to be embedded as a running service. Making them deterministic would mean forking and reshaping them.

## Decision

Implement Raft **from scratch** in the `geodb-raft` module, following the Raft paper (extended version) and Diego Ongaro's thesis, with the API shape described in ADR-0004.

The implementation will be cross-checked against `etcd-io/raft` and `tikv/raft-rs` as design references (not as code to translate), and against a TLA+ specification (ADR-0012).

## Alternatives Considered

### Apache Ratis

A production-grade Java Raft library. Rejected because it owns I/O and threading, which prevents deterministic simulation without invasive changes, and because adopting it removes most of the learning value.

### SOFAJRaft (Ant Group)

Battle-tested in production and feature-rich, including multi-group support. Rejected for the same reasons: tightly coupled to its own threading, RPC, and storage stack.

### Wrapping `etcd/raft` or `raft-rs` through JNI or a sidecar

These libraries are I/O-free and would fit the simulation model. Rejected because crossing a language boundary on every message complicates simulation and debugging, and it again removes the learning objective.

### A Paxos or Multi-Paxos variant

Equivalent safety guarantees. Rejected because Raft has far better documentation, reference implementations, a public TLA+ specification, and tooling for verification.

## Consequences

### Positive

- Complete ownership of the protocol and its failure handling, which is the main source of learning and interview material.
- The core can be designed for determinism and simulation from day one.
- No hidden threading or I/O assumptions imposed by a third party.

### Negative

- High risk of subtle bugs in areas such as snapshots, membership changes, and leader transfer. These are mitigated by invariant checks inside the simulator, a TLA+ model, and mutation testing, but the risk remains real.
- Significant up-front cost before the first end-to-end feature works.
- Performance tuning features (pipelining, batching, flow control) must be built rather than inherited.

## References

- Ongaro & Ousterhout, [In Search of an Understandable Consensus Algorithm (Extended Version)](https://raft.github.io/raft.pdf) ([USENIX ATC '14 page](https://www.usenix.org/conference/atc14/technical-sessions/presentation/ongaro))
- Ongaro, [Consensus: Bridging Theory and Practice (PhD thesis)](https://web.stanford.edu/~ouster/cgi-bin/papers/OngaroPhD.pdf) ([Stanford repository](https://purl.stanford.edu/qr033xr6097))
- Lamport, [Paxos Made Simple](https://lamport.azurewebsites.net/pubs/paxos-simple.pdf)
- [Raft TLA+ specification (ongardie/raft.tla)](https://github.com/ongardie/raft.tla) and [TLA+ home page](https://lamport.azurewebsites.net/tla/tla.html)
- Design references: [etcd-io/raft](https://github.com/etcd-io/raft), [tikv/raft-rs](https://github.com/tikv/raft-rs), [hashicorp/raft](https://github.com/hashicorp/raft)
- Existing Java libraries considered: [Apache Ratis](https://ratis.apache.org/), [SOFAJRaft](https://github.com/sofastack/sofa-jraft)
- [[adr-0004-io-free-raft-note-driven-by-a-ready-advance-contract]]
- [[adr-0012-verification-strategy-deterministic-simulation-linearizability-and-iso]]
