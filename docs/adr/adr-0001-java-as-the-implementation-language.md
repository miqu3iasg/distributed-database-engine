---
title: GeoDB Java as the implementation language
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0001-java-as-the-implementation-language.md
lang: en-US # switch language
date: "2026-11-22"
id: 1e283cfb-0a69-422e-bdf9-31d0371d569f
---

# ADR-001 GeoDB Java as the implementation language

## Context

GeoDB is a distributed, transactional key-value database. Systems of this kind are usually written in Go, Rust, or C++ because of predictable latency, explicit memory control, and mature ecosystems for consensus and storage.

The project's primary goal is to build deep expertise in distributed systems (consensus, replication, MVCC, distributed transactions, fault tolerance), not to learn a new language. The author's production stack is Java, which means every hour spent fighting an unfamiliar language is an hour not spent on the actual problem domain. Java also has a strong concurrency model, excellent tooling (profilers, JMH, JCStress, ArchUnit, Jazzer), and recent features that fit this domain well.

## Decision

Use **Java** for all modules.

- **Toolchain:** JDK 25 LTS.
- **Language level:** 21 for the core modules (`geodb-raft`, `geodb-env`, `geodb-common`, `geodb-mvcc`, `geodb-txn`, `geodb-range`), so that they do not silently depend on newer APIs. `geodb-storage` may use the Foreign Function & Memory API (`java.lang.foreign`, final since Java 22) for memory-mapped files and off-heap arenas.
- **Features to use deliberately:** records and sealed interfaces for messages and commands (exhaustive `switch` pattern matching makes unhandled message types a compile error), virtual threads only at the client-facing edge (see ADR-0003 for why not in the core), and `java.util.zip.CRC32C` for checksums.
- **Build:** Maven multi-module, with `maven-enforcer` and ArchUnit enforcing dependency rules.

## Alternatives Considered

### Rust

Better fit for a storage engine: no GC, precise memory layout, strong type-level guarantees. Rejected because it would add a steep language-learning curve on top of an already steep domain, and the project's value comes from distributed-systems depth rather than from language choice.

### Go

The language of etcd, CockroachDB, and the MIT 6.824 labs, so reference material is abundant. Rejected because the author's stack is Java and because the abundance of Go references would tempt translation instead of design.

### C++

Maximum control, minimal runtime. Rejected for the same learning-curve reason as Rust, plus a larger risk of memory-safety bugs that would obscure protocol bugs.

### Kotlin or Scala on the JVM

Same runtime and GC characteristics with different syntax. No meaningful benefit for this project, and it would reduce the pool of readers who can follow the code.

## Consequences

### Positive

- Full focus on the problem domain; no language-learning overhead.
- Rich tooling for correctness work (JCStress, Jazzer, PIT, ArchUnit, JMH).
- Sealed types and records make protocol state machines explicit and checked by the compiler.
- Concurrent, portable, easy to run in CI and Docker on any machine.

### Negative

- GC pauses can distort tail latency. Mitigation: off-heap arenas for memtables and block cache, allocation discipline on hot paths, and explicit experiments with ZGC and Shenandoah.
- No value types today, so the design must lean on primitives and `byte[]`/`MemorySegment` in hot paths.
- JIT warm-up complicates benchmarking; the benchmark methodology (ADR-0012 and the technical specification) must account for it.
- Raw performance will not match Rust or C++ engines; the project targets _measured and explained_ performance rather than records.

## References

- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [JEP 409: Sealed Classes](https://openjdk.org/jeps/409)
- [JDK 25 (LTS) project page](https://openjdk.org/projects/jdk/25/)
- [Project Valhalla (value types)](https://openjdk.org/projects/valhalla/)
- [ZGC](https://openjdk.org/projects/zgc/) and [Shenandoah](https://openjdk.org/projects/shenandoah/) low-pause garbage collectors
- [`java.util.zip.CRC32C` API documentation](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/CRC32C.html)
- Tooling: [JMH](https://github.com/openjdk/jmh), [JCStress](https://github.com/openjdk/jcstress), [ArchUnit](https://www.archunit.org/), [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer), [Maven Enforcer Plugin](https://maven.apache.org/enforcer/maven-enforcer-plugin/)
- Languages considered: [Rust](https://www.rust-lang.org/), [Go](https://go.dev/), [Kotlin](https://kotlinlang.org/), [Scala](https://www.scala-lang.org/)
- Reference systems written in Go: [etcd](https://github.com/etcd-io/etcd), [CockroachDB](https://github.com/cockroachdb/cockroach)
- [MIT 6.824 Distributed Systems course and labs](https://pdos.csail.mit.edu/6.824/)
- [[adr-0003-deterministic-core-and-environment-spi]]
- [[adr-0012-verification-strategy-deterministic-simulation-linearizability-and-iso]]
