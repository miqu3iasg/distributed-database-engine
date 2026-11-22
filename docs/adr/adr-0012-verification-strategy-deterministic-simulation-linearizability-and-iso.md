---
title: "Verification strategy: deterministic simulation, linearizability and isolation checkers, TLA+, and chaos testing"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0012-verification-strategy-deterministic-simulation-linearizability-and-iso.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: 2490eab7-0d86-4940-89aa-3e22e240fd56
---

# ADR-012 Verification strategy: deterministic simulation, linearizability and isolation checkers, TLA+, and chaos testing

## Context

For a distributed database, "the tests pass" is not evidence of correctness. Bugs live in rare interleavings of crashes, delays, partitions, and recovery, and the consequences (lost or inconsistent data) are severe. The project must be able to _demonstrate_ correctness, not just claim it, and that evidence is also the most valuable output of the project.

No single technique is sufficient. Unit tests find local bugs; simulation explores interleavings; checkers decide whether observed behavior is legal; formal models verify the protocol design; real-process chaos tests catch what simulators do not model.

## Decision

Adopt a layered verification strategy in which each layer has a defined role and a CI gate.

1. **Deterministic simulation** (`geodb-sim`, enabled by ADR-0003): whole-cluster runs on virtual time with simulated clock skew, network, and disk. A seeded _nemesis_ injects crashes, partitions (symmetric and asymmetric), delay, duplication, reordering, disk faults, and membership changes. Protocol invariants are asserted at every event: election safety, leader append-only, log matching, leader completeness, and state machine safety, plus range-level invariants (non-overlapping spans, replica convergence) and a liveness check after faults cease. Failing seeds are stored and replayed as permanent regression tests. A _buggify_ mechanism enables extra rare faults only under simulation.
2. **Linearizability checker** (`geodb-lincheck`): a Wing-Gong-Lowe style search with memoization and decomposition by key (P-compositionality). Operations that time out are treated as indeterminate. On failure it emits a minimal violating sub-history. The checker itself is tested by **injecting known bugs** and requiring detection.
3. **Isolation anomaly checker**: an Elle-style dependency-graph cycle search over list-append workloads, to detect G0, G1a/b/c, G-single, and G2. It must flag write skew under Snapshot Isolation and find none under Serializable (ADR-0009).
4. **Formal specification** (`specs/tla`): TLA+ model of Raft (adapted from the public specification) extended with PreVote and CheckQuorum, verified with TLC on small configurations. Java code references the corresponding spec actions in comments. A mismatch between spec and code is treated as a bug in one of them.
5. **Chaos tests with real processes** (`geodb-chaos`): Docker Compose clusters with Toxiproxy for latency, loss, and cuts, plus `kill -9`, `SIGSTOP`/`SIGCONT` (to mimic long GC pauses), and disk-full scenarios. Histories are verified with the same checkers.
6. **Supporting techniques:** model-based property tests (jqwik) for storage and MVCC against reference implementations, JCStress for shared concurrent structures, Jazzer fuzzing of WAL, SSTable, MANIFEST, and protobuf decoders, and PIT mutation testing on `geodb-raft` with a minimum mutation score (initially 85%).
7. **CI gates:** all regression seeds pass; a nightly batch of fresh seeds finds no violation; TLC reports no counterexample for configured models; ArchUnit and forbidden-API rules pass.
8. **Evidence ledger:** CI records seeds executed, simulated hours, bugs found by category, validated history sizes, and mutation scores into `docs/EVIDENCE.md`.

## Alternatives Considered

### Unit and integration tests only

Cheap and necessary but nowhere near sufficient for distributed protocols. Rejected as the main strategy.

### Jepsen-style black-box testing only

Excellent for finding real-world bugs and used here in the chaos layer, but it is non-deterministic and explores far fewer interleavings per hour. Used as a complement, not the foundation.

### Formal proof (TLAPS, Ivy, Verdi-style)

Strongest assurance, but the effort is out of proportion for a personal project and it verifies a model rather than the Java code. Model checking with TLC gives most of the benefit at a fraction of the cost.

### Rely on a commercial deterministic-testing platform

Powerful, but not free, and it would hide the mechanics that are central to the project's learning goal.

## Consequences

### Positive

- Correctness claims are backed by reproducible, quantified evidence.
- Bugs are found early, are reproducible from a seed, and each one becomes a documented lesson and a regression test.
- The same checkers serve simulation and real-cluster tests.

### Negative

- A large amount of verification infrastructure to build, which competes with feature work; the simulator and checkers can themselves be wrong.
- TLC state-space explosion limits model sizes to small clusters and bounded logs, so the spec proves less than a full proof would.
- Simulation does not model every real-world behavior (kernel, JVM, GC, real network stack), which is why the chaos layer exists.
- CI time and compute budget must be managed, since large seed campaigns are expensive on free CI tiers.

## References

- Zhou et al., [FoundationDB: A Distributed Unbundled Transactional Key Value Store (SIGMOD 2021)](https://dl.acm.org/doi/10.1145/3448016.3457559) and [FoundationDB documentation: Testing](https://apple.github.io/foundationdb/testing.html)
- [TigerBeetle VOPR internals](https://github.com/tigerbeetle/tigerbeetle/blob/0.17.2/docs/internals/vopr.md) and [Protocol-Aware Deterministic Simulation Testing](https://tigerbeetle.com/blog/2026-08-20-protocol-aware-dst/)
- Herlihy & Wing, [Linearizability: A Correctness Condition for Concurrent Objects](https://doi.org/10.1145/78969.78972)
- Wing & Gong, [Testing and Verifying Concurrent Objects](https://doi.org/10.1006/jpdc.1993.1015)
- Lowe, [Testing for Linearizability](https://www.cs.ox.ac.uk/people/gavin.lowe/LinearizabilityTesting/)
- Horn & Kroening, [Faster Linearizability Checking via P-Compositionality](https://arxiv.org/abs/1504.00204)
- Reference checkers: [Porcupine](https://github.com/anishathalye/porcupine), [Knossos](https://github.com/jepsen-io/knossos)
- Kingsbury & Alvaro, [Elle: Inferring Isolation Anomalies from Experimental Observations](https://arxiv.org/abs/2003.10554), [Elle implementation](https://github.com/jepsen-io/elle), and [Jepsen analyses](https://jepsen.io/analyses)
- Lamport, [Specifying Systems](https://lamport.azurewebsites.net/tla/book.html); [TLA+ tools (TLC)](https://github.com/tlaplus/tlaplus); [Raft TLA+ specification](https://github.com/ongardie/raft.tla)
- Formal verification alternatives considered: [TLAPS](https://proofs.tlapl.us/), [Ivy](https://kenmcmil.github.io/ivy/), [Verdi](https://verdi.uwplse.org/)
- Tooling: [jqwik](https://jqwik.net/), [JCStress](https://github.com/openjdk/jcstress), [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer), [PIT mutation testing](https://pitest.org/), [Toxiproxy](https://github.com/Shopify/toxiproxy), [Docker Compose](https://docs.docker.com/compose/), [ArchUnit](https://www.archunit.org/)
- [[adr-0003-deterministic-core-and-environment-spi]]
- [[adr-0009-transaction-record-as-the-commit-point-with-snapshot-isolation-before]]
- [[adr-0011-readindex-for-linearizable-reads-with-lease-reads-as-a-later-optimizat]]
