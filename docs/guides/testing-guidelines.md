---
title: GeoDB Testing guidelines
tags:
    - documentation
related:
    -  # "[[related-note]]"
lang: en-US # switch language
date: 2026-11-22T13:10:34
id: 59cb6acf-57e2-43ec-aa87-8cd38eced1b7
---

# Testing Guidelines

For a distributed database, "the tests pass" is not evidence of correctness (ADR-0012). Bugs live in rare interleavings of crashes, delays, partitions and recovery, and their consequence is lost or inconsistent data. Testing in GeoDB therefore has two goals: **drive the design with fast, precise tests (TDD)** and **produce reproducible evidence of correctness under failure (simulation, checkers, formal models)**.

## 1. Principles

1. **Tests first (TDD).** New behavior and bug fixes start with a failing test.
2. **Deterministic or it does not count.** A test whose outcome depends on wall-clock time, real threads, or hash ordering is a bug. Everything reproducible from a seed.
3. **Reproduce, then fix.** A failure that cannot be replayed from `(seed, code version)` or a minimal input is first made replayable.
4. **Every bug becomes a permanent regression.**
5. **Test the testers.** Simulators, fault injectors, and checkers are code that can be wrong, and a wrong checker silently approves wrong databases.
6. **Evidence, not adjectives.** Results that go into docs come from real runs recorded by CI.
7. **Prefer the real thing in simulation over mocks.** Use `SimClock`, `SimNetwork`, `SimDisk` rather than hand-written mocks of the environment.

## 2. TDD in GeoDB

### 2.1 The cycle

**Red, green, refactor**, in small steps:

1. **Red:** write the smallest test that expresses the next behavior; watch it fail for the _right reason_.
2. **Green:** write the simplest code that passes. No speculative generality.
3. **Refactor:** clean up with all tests green; no behavior change. Commit refactors separately (see `commit-guidelines.md`).

Each iteration typically ends in a small commit (test + implementation together, so history stays green).

### 2.2 Where to start a test, by kind of work

| Work                                                                     | Start with                                                                                                                                     |
| ------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Pure data structure or codec (HLC, key encoding, skiplist, Bloom filter) | Example-based unit test, then a jqwik property (round-trip, ordering, idempotence)                                                             |
| Raft behavior                                                            | A test driving `RaftNode` through `tick/step/propose/ready/advance` with a scripted message sequence, then a simulation scenario and invariant |
| Storage engine                                                           | A model-based property against a reference `TreeMap`, plus a crash-injection scenario on `SimDisk`                                             |
| MVCC / transactions                                                      | A scenario in a single simulated cluster with a history checked by `geodb-lincheck` / the isolation checker                                    |
| A bug found by simulation, checker, fuzzing or chaos                     | The failing seed or input committed to the regression corpus **before** the fix                                                                |
| A new persisted format or wire message                                   | A byte-layout (golden) test and a round-trip property                                                                                          |
| Protocol-level feature                                                   | The invariant or TLA+ property first, then the implementation                                                                                  |

### 2.3 TDD for distributed protocols

Unit tests of examples are necessary but insufficient. For protocol code, TDD means:

1. Write the **scenario** (a deterministic message/failure schedule) that exposes the missing behavior.
2. Write or extend the **invariant** that must hold (for example Log Matching).
3. Make it pass, then widen it: let the simulator generate thousands of schedules against the same invariant.

A "red seed" is the distributed equivalent of a failing test.

### 2.4 When strict TDD is relaxed

Exploratory spikes (for example probing an off-heap arena API) may be written test-after, but they must be rewritten test-first before merging, or deleted.

## 3. Test layers

| Layer                        | Tool                          | Covers                                                                 | Runs                                                          |
| ---------------------------- | ----------------------------- | ---------------------------------------------------------------------- | ------------------------------------------------------------- |
| Unit                         | JUnit 5, AssertJ              | Isolated classes and functions                                         | Every commit                                                  |
| Property-based / model-based | jqwik                         | Storage, MVCC, codecs, HLC against reference models                    | Every commit (bounded) + nightly (large)                      |
| Deterministic simulation     | `geodb-sim`                   | Whole cluster under injected faults, reproducible by seed              | Regression seeds every commit; fresh seeds per PR and nightly |
| Linearizability              | `geodb-lincheck`              | Legality of single-key histories                                       | With simulation and chaos                                     |
| Isolation anomalies          | `geodb-lincheck` (Elle-style) | G0, G1a/b/c, G-single, G2; write skew under SI                         | With simulation (phases 4+)                                   |
| Formal model                 | TLA+ / TLC                    | Raft (+ PreVote, CheckQuorum) invariants                               | Nightly and on spec changes                                   |
| Concurrency                  | JCStress                      | Memory visibility, lock-free structures                                | Nightly                                                       |
| Fuzzing                      | Jazzer                        | WAL, SSTable, MANIFEST, Protobuf decoders                              | Nightly / long runs                                           |
| Mutation                     | PIT                           | Quality of `geodb-raft` tests (minimum 85%)                            | Nightly / per phase                                           |
| Chaos                        | Docker Compose, Toxiproxy     | Real processes: `kill -9`, `SIGSTOP/SIGCONT`, latency, cuts, disk-full | Scheduled / manual                                            |
| Architecture                 | ArchUnit, forbidden-apis      | Dependency and determinism rules                                       | Every build                                                   |
| Benchmarks                   | JMH, YCSB-style generator     | Performance (not correctness)                                          | On demand, with methodology                                   |

## 4. Writing good tests

### 4.1 Structure and naming

- One behavior per test. Arrange/Act/Assert (or Given/When/Then), clearly separated.
- Test class mirrors the class or feature: `RaftNodeElectionTest`, `MvccKeyEncodingTest`.
- Method names describe behavior: `followerGrantsOnlyOneVoteInSameTerm`, `walRecoveryTruncatesAtFirstInvalidCrc`. Use `@DisplayName` for readable reports.
- Tests are readable specifications. A reader should learn the protocol rule from the test.

### 4.2 Determinism rules for tests

- Never `Thread.sleep`, real timers, or wall-clock assertions. Use `Clock`/`Timers` from `geodb-env` with virtual time.
- Randomness only from a seeded `Rand`; print the seed on failure.
- No dependence on `HashMap` iteration order or identity hash codes.
- No real network or filesystem in unit and simulation tests. Use `SimNetwork` and `SimDisk`.
- Tests are independent and order-independent; no shared mutable static state.

### 4.3 Test doubles

- Prefer the **simulated environment** and real collaborators.
- Hand-written fakes are fine for narrow seams (for example an in-memory `KvEngine` in Phases 1 and 2, as ADR-0006 allows).
- Avoid mocking frameworks inside core modules; interaction-based assertions tend to encode implementation rather than behavior.

### 4.4 Assertions

- Assert on **observable behavior and invariants**, not internal structure.
- Use AssertJ for expressive failures. Include context (seed, node, term, index) in assertion messages.
- Never weaken an assertion, widen a timeout, or add a retry to make a test pass. Find the cause.

## 5. Property-based and model-based testing (jqwik)

- Define properties: encode/decode round-trip, ordering preservation (MVCC keys, escaping), idempotent recovery, "latest version at or before `t`" correctness.
- **Storage:** random sequences of `put/delete/get/scan/snapshot/flush/compact/crash+recover` compared with an in-memory reference model.
- **MVCC encoding:** generate arbitrary byte keys (including `0x00` and `0xFF`) and assert lexicographic order of user keys is preserved across escaped encodings and versions.
- **HLC:** monotonicity under arbitrary clock jumps, logical overflow behavior, rejection beyond `max_clock_offset`.
- Rely on shrinking; commit the shrunk counterexample as an example test.

## 6. Deterministic simulation testing

### 6.1 How it works

A single-threaded event loop on virtual time, with simulated clock (skew, jumps), network (loss, delay, duplication, reordering, partitions), and disk (unsynced writes lost, truncated, or torn; `fsync`/read failures; bit flips; `ENOSPC`). A seeded **nemesis** schedules faults. Time jumps to the next event, so simulated hours run in seconds.

### 6.2 Invariants checked at every event

- Raft: Election Safety, Leader Append-Only, Log Matching, Leader Completeness, State Machine Safety.
- Range: non-overlapping spans, consistent descriptors, replica convergence at the end.
- **Liveness:** after faults stop, the cluster must make progress within a bounded time.

### 6.3 Workloads

Single register (`read/write/cas`), bank accounts (sum invariant), list-append (isolation checker), concurrent scans during splits.

### 6.4 Seeds and the regression corpus

- Failures are recorded as `(seed, code version)`. Failing seeds are added to `sim/corpus/` and replayed on **every** commit forever.
- Never delete a corpus seed because it "no longer fails after the refactor". A seed's path through the system changes with the code, which is exactly why the invariant checks matter.
- Use **buggify** points (`Buggify.fire("snapshot.delay")`) to enable rare faults only under simulation.
- Fault coverage should grow by phase (new nemesis faults ship with the features they stress).

### 6.5 Testing the simulator

The simulator is a significant piece of software. It needs its own tests: same seed produces byte-identical event traces, `SimDisk` crash semantics behave as specified, the nemesis actually injects each fault class, and a deliberately broken protocol is **caught**.

## 7. Linearizability and isolation checking

- `geodb-lincheck` implements Wing-Gong-Lowe with memoization and per-key decomposition. Timed-out operations are **indeterminate**.
- On failure it emits a minimal violating sub-history. Treat that output as the failing test case.
- **Test the checker with injected bugs** (stale reads, lost writes, reordered commits) and require detection. A checker that never fails proves nothing.
- Isolation: the Elle-style checker must flag **write skew under Snapshot Isolation** and find **no cycles under Serializable**. Both outcomes are tested on purpose.

## 8. Formal specification (TLA+)

- `specs/tla/Raft.tla` is checked with TLC on small configurations (3 to 5 nodes, bounded terms and logs) in CI/nightly.
- Java handlers reference spec actions. A mismatch between spec and code is a bug in one of them and is fixed in the same PR.
- TLC's bounded exploration proves less than a full proof; document the bounds.

## 9. Fuzzing, concurrency and mutation

- **Fuzzing (Jazzer):** WAL, SSTable, MANIFEST and Protobuf decoders must never crash, hang, or accept corrupt input as valid. Crashes found become corpus entries and unit tests.
- **JCStress:** every shared concurrent structure (block cache, metrics, routing cache) has a JCStress test.
- **Mutation (PIT):** `geodb-raft` must keep a mutation score of at least **85%**. A surviving mutant means a missing or weak assertion; add the test, do not exclude the mutant without justification.
- **Coverage (JaCoCo)** is a signal, not a goal. High coverage with weak assertions is worthless; mutation score is the stronger signal.

## 10. Storage-specific testing

- **Crash at every point:** `SimDisk` interrupts at each `write`/`sync`, then recovery is compared with the last acknowledged state.
- **Torn writes and corruption:** truncated and bit-flipped WAL/SSTable/MANIFEST files must be detected via CRC32C and handled (truncate at first invalid record, or isolate the node).
- **Atomicity:** `appliedIndex` and command effects are in one `WriteBatch`; test that re-applying after a crash is idempotent.
- **Engine parity:** the same model-based suite runs against `LsmEngine` and `RocksDbEngine` behind `KvEngine`, so differences are attributable.
- **Format pinning:** golden-file tests for on-disk layouts.
- **Resource safety:** off-heap arenas and block cache are tested for leaks and lifetime errors.

## 11. Time and clock testing

- Inject skew, drift and jumps through the simulated `Clock`.
- Test the exact boundary of `max_clock_offset`: below it, behavior is safe; above it, lease reads can violate linearizability (ADR-0011), and the node's self-protection triggers.
- Test uncertainty-interval restarts under Serializable (ADR-0007, ADR-0009).

## 12. Chaos testing with real processes

- Docker Compose clusters of 3 to 5 nodes with Toxiproxy between them; `kill -9`, `SIGSTOP`/`SIGCONT` (mimics long GC pauses), and disk-full.
- Record histories from real clients and verify them with the **same** checkers.
- Chaos covers what simulation cannot (kernel, page cache, JVM, GC, real network stack). It is slower and non-deterministic, so it complements, never replaces, simulation.

## 13. Flaky tests policy

**Zero tolerance.** A flaky test is a bug in the test, the code, or the determinism of the environment.

- Do not retry, ignore, or quarantine to get a green build.
- In simulation, non-reproducibility from a seed means determinism has leaked into the core; find the leak (forbidden API, unordered iteration, real thread).
- If a chaos test is flaky, make its failure reproducible in simulation if at all possible, then fix.

## 14. CI tiers (targets, adjust with measurement)

| Tier               | Trigger                           | Contents                                                                                | Target          |
| ------------------ | --------------------------------- | --------------------------------------------------------------------------------------- | --------------- |
| Fast               | Every commit/push                 | Build, ArchUnit, forbidden-apis, unit, bounded property tests, **all regression seeds** | A few minutes   |
| PR                 | Pull request                      | Fast + a moderate batch of fresh simulation seeds + checkers                            | Tens of minutes |
| Nightly            | Scheduled                         | Large seed campaign, TLC, JCStress, Jazzer, PIT, large model tests                      | Hours           |
| Scheduled / manual | Weekly or before phase completion | Chaos suite, benchmarks                                                                 | As needed       |

Free CI compute is limited; large campaigns run nightly and are budgeted. CI records seeds executed, simulated hours, bugs found, history sizes and mutation scores into `docs/EVIDENCE.md`.

## 15. What to test per module (minimum)

| Module                         | Minimum                                                                                                                 |
| ------------------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| `geodb-common`                 | HLC properties, codec round-trips, id/config validation                                                                 |
| `geodb-env`                    | Real and simulated implementations honor the same contract; deterministic event ordering                                |
| `geodb-raft`                   | All features in spec 6.4, invariants in simulation, TLA+ alignment, PIT >= 85%                                          |
| `geodb-storage`                | Model-based, crash-at-every-point, fuzzing, format pinning, group-commit behavior                                       |
| `geodb-mvcc`                   | Key encoding order, intent rules, GC consistency, `gcThreshold` errors                                                  |
| `geodb-range`                  | Deterministic apply, split races, snapshots, dedup table, replica-move safety                                           |
| `geodb-txn`                    | Commit atomicity, ambiguous results, intent resolution, write skew (SI) vs none (Serializable), bank-transfer invariant |
| `geodb-cluster`                | Allocator decisions, repair after node death, "majority of up-to-date replicas always exists"                           |
| `geodb-server` / `geodb-proto` | Protobuf round-trip fidelity with core records, schema evolution rules, typed errors                                    |
| `geodb-client`                 | Retry with backoff and budget, idempotent sessions, routing-cache correction                                            |

## 16. Bug workflow

1. **Capture** the seed or minimal input.
2. **Reproduce** deterministically; if you cannot, make it reproducible first.
3. **Add the failing test/seed** to the suite (commit it together with the fix to keep history green).
4. **Find the root cause**, not just the symptom. Ask which invariant or check _should_ have caught it earlier.
5. **Fix** and verify the seed passes.
6. **Strengthen**: add the missing invariant, nemesis fault, or property if one was missing.
7. **Document** in `docs/bugs/` and reference the seed with a `Seed:` footer on the commit.

## 17. Definition of Done for a change

- Tests were written first and cover the new behavior at the right layer.
- Fast tier and all regression seeds pass; fresh simulation seeds pass for protocol changes.
- Relevant invariants and checkers are still enforced (none weakened).
- Mutation and coverage thresholds hold where they apply.
- Docs updated (spec/ADR/guides/bug write-up) and no unsupported performance or correctness claims.

## 18. Anti-patterns

- Sleeping or retrying to wait for a result.
- Asserting only that "no exception was thrown".
- Mocking the environment instead of using the simulator.
- Skipping or deleting a regression seed.
- Adjusting a checker or invariant to match the code.
- Quoting benchmark numbers without methodology, or from a single unwarmed run.
- Testing only the happy path and failure-free schedules.
- Treating a green fast tier as evidence of protocol correctness.

## 19. For AI agents

Write the failing test first and show it fails for the right reason. Use the simulator and seeded randomness, never sleeps. When something fails in simulation, preserve the seed, reproduce, and add it to `sim/corpus/` with the fix. Never weaken an assertion, invariant, or CI gate to get green. Report exactly which suites you ran and their results, and never state numbers you did not obtain.
