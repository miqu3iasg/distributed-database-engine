---
title: Deterministic core and environment SPI
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0003-deterministic-core-and-environment-spi.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: f6b85e53-eae0-4877-ae03-f37d63712207
---

# ADR-003 Deterministic core and environment SPI

## Context

Distributed protocols fail in rare interleavings: a message arrives late, a disk write is torn by a crash, a partition lasts a few hundred milliseconds. Conventional tests almost never exercise such conditions, and when a bug does appear in a real cluster it is usually impossible to reproduce.

The most effective known technique is deterministic simulation testing, used by FoundationDB and TigerBeetle: run the entire system in a single thread on virtual time, with a simulated network and disk, driven by a random seed. Any failure is reproducible from `(seed, code version)`.

This is only possible if the core logic cannot observe anything outside the simulator's control. It must be a design constraint from the first commit, because retrofitting determinism into an existing codebase is extremely costly.

## Decision

All core logic must be a **pure function of (state, input event, virtual time, seeded randomness)**.

1. Introduce a module `geodb-env` that defines a small SPI: `Clock`, `Timers`, `Rand`, `Network`, and `Disk`. Each has a real implementation (system clock, scheduled executor, gRPC, `FileChannel`) and a simulated implementation.
2. Core modules (`raft`, `range`, `txn`, `mvcc`, `cluster`) may depend only on `geodb-env` for anything touching time, randomness, network, disk, or threads.
3. Enforce this mechanically:
    - **forbidden-apis** and **ArchUnit** rules fail the build on `System.currentTimeMillis`, `System.nanoTime`, `Thread.sleep`, `new Thread`, `new Random`, `ThreadLocalRandom`, `UUID.randomUUID`, and direct `java.nio.file` access inside core modules.
    - Iteration over unordered collections whose order depends on identity hash codes is banned in core code; use ordered or explicitly sorted structures.
4. The simulated `Disk` models crash semantics: unsynced writes may be lost, truncated at an arbitrary prefix, or torn at sector granularity; `fsync` and reads can be made to fail.
5. In production the core is driven by a worker model (one logical owner thread per range); in simulation the same code runs on one thread with an event queue ordered by `(virtual time, sequence number)`.
6. Virtual threads are used only at the client-facing edge of the server, never inside the deterministic core.

## Alternatives Considered

### Test with real threads and sleeps

Simple to write, but non-reproducible and slow. Rare interleavings are essentially never reached. Rejected.

### Network-level fault injection only (Toxiproxy, Jepsen-style)

Valuable, and still used for end-to-end chaos testing (ADR-0012), but it explores far fewer interleavings per hour and cannot reproduce a failure exactly. It complements the simulation rather than replacing it.

### Use a JVM bytecode-level determinism tool

Tools that make arbitrary Java deterministic exist but are fragile, heavy, and hard to keep working across JDK versions. Designing for determinism is simpler and more robust.

### Retrofit determinism later

Appealing to postpone, but historically one of the most expensive refactors in distributed systems. Rejected.

## Consequences

### Positive

- Every failure is reproducible from a seed, and failing seeds become permanent regression tests.
- Millions of failure scenarios can run per night on a single machine, since virtual time skips idle periods.
- The architecture is cleanly layered: I/O at the edges, logic in the middle.

### Negative

- Discipline cost: every use of time, randomness, or I/O must go through the SPI, and developers must resist convenient shortcuts.
- The simulator itself is a significant piece of software that needs its own tests, and a bug in the simulated disk or network can mask or fabricate bugs.
- Simulation does not exercise real OS, JVM, or kernel behavior (page cache, GC pauses, real network); that gap is covered by chaos tests with real processes.

## References

- Zhou et al., [FoundationDB: A Distributed Unbundled Transactional Key Value Store (SIGMOD 2021)](https://dl.acm.org/doi/10.1145/3448016.3457559)
- [FoundationDB documentation: Testing](https://apple.github.io/foundationdb/testing.html)
- [TigerBeetle repository](https://github.com/tigerbeetle/tigerbeetle), [VOPR internals](https://github.com/tigerbeetle/tigerbeetle/blob/0.17.2/docs/internals/vopr.md), and the blog post [Protocol-Aware Deterministic Simulation Testing](https://tigerbeetle.com/blog/2026-08-20-protocol-aware-dst/)
- [Toxiproxy](https://github.com/Shopify/toxiproxy) and [Jepsen](https://jepsen.io/) (complementary, non-deterministic fault injection)
- [forbidden-apis](https://github.com/policeman-tools/forbidden-apis) and [ArchUnit](https://www.archunit.org/) (mechanical enforcement of determinism rules)
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [[adr-0001-java-as-the-implementation-language]]
- [[adr-0004-io-free-raft-note-driven-by-a-ready-advance-contract]]
- [[adr-0012-verification-strategy-deterministic-simulation-linearizability-and-iso]]
