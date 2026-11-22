---
title: GeoDB AGENTS
tags:
    - documentation
related:
    -  # "[[related-note]]"
lang: en-US # switch language
date: 2026-11-22T13:06:27
id: b5edf00f-4551-46d9-949c-05c89d4ee89c
---

# AGENTS.md

Guidance for AI coding agents working on **GeoDB**. Humans should read the docs listed below; this file is the condensed, enforceable version.

## 1. What GeoDB is

A distributed, transactional, ordered key-value database written in Java: Multi-Raft ranges, MVCC with a Hybrid Logical Clock, distributed transactions, a custom LSM engine, and a **deterministic simulation** test strategy. The project's goal is deep distributed-systems expertise and **demonstrable correctness**, not record performance and not a new language.

**Current status: documentation phase. No code exists yet.** The next milestone is Phase 0 (Maven skeleton, CI, ArchUnit/forbidden-apis, `geodb-env` real + simulated, a reproducible virtual-time "hello world").

## 2. Read before changing anything

| Doc                                           | Language | Use it for                                                                    |
| --------------------------------------------- | -------- | ----------------------------------------------------------------------------- |
| `GeoDB General Description`                   | pt-BR    | High-level intent, analogies, promises and non-promises                       |
| `GeoDB Techinical Specification` (v0.1 draft) | pt-BR    | Source of truth for structure, APIs, formats, roadmap and acceptance criteria |
| `docs/adr/adr-00NN-*.md` (ADR-001 to ADR-013) | en-US    | Why each decision was made, alternatives rejected                             |

Precedence when documents disagree: **ADR > Technical Specification > General Description**. If you find a conflict, do not silently pick one: report it (see section 12).

## 3. Non-negotiables

These are the rules that, if broken, silently destroy the project's value. Never trade them for convenience.

1. **Deterministic core.** Core logic is a pure function of `(state, input event, virtual time, seeded randomness)` (ADR-003).
2. **`geodb-raft` performs no I/O**, owns no threads, reads no clock, and depends on neither gRPC nor Protobuf (ADR-004, ADR-013).
3. **Raft host ordering contract** is never relaxed (section 6).
4. **Durability before acknowledgment.** A write is acknowledged only after a majority has `fsync`ed it. No "rely on the page cache" shortcuts (ADR-005).
5. **Replicated command application is deterministic.** The leader fixes timestamps, ids and any other value _before_ proposing. Applying a committed entry never reads the clock, randomness, or non-replicated local state (spec 9.3).
6. **The transaction record is the single atomic commit point** (ADR-009).
7. **Linearizable reads never come from an unconfirmed leader.** ReadIndex first; lease reads only later and only behind the `max_clock_offset` assumption (ADR-011).
8. **Every bug found becomes a permanent regression** (a stored seed, a corpus entry, or a test), never just a fix.
9. **Never fabricate evidence.** Benchmarks, seed counts, mutation scores and simulated hours come from real runs recorded in `docs/EVIDENCE.md`. If you did not run it, do not state a number.
10. **Phase discipline.** Do not implement features belonging to a later phase (section 11) unless asked.

## 4. Architecture in brief

```
Client (geodb-client) --gRPC--> Gateway/TxnCoordinator --> Router (descriptor cache)
  --> Store (1 per node)
        Replica(rangeId) -> RaftNode --+--> Raft Log Engine (shared WAL, group commit)
              | apply (deterministic)
              v
        MVCC layer -> KvEngine (LSM: WAL + memtable + SSTables + MANIFEST)
  --> Transport (one bidirectional gRPC stream per node pair, batched)
```

### Modules and dependency rules

```
geodb-common   HLC, ids, codecs, Result/errors, config
geodb-env      SPI: Clock, Timers, Rand, Network, Disk (real + simulated)
geodb-raft     pure consensus (depends only on common + env)
geodb-storage  KvEngine, LsmEngine, RocksDbEngine (fallback), WAL, SSTable, MANIFEST, raft-log-engine
geodb-mvcc     versions, intents, GC, MVCC iterators
geodb-range    Replica, state machine, split, snapshot, leases
geodb-txn      TxnCoordinator, txn records, intent resolution
geodb-cluster  metadata, allocator, rebalancer, membership
geodb-proto    .proto files
geodb-server   node composition: gRPC, wiring, bootstrap, admin; Protobuf <-> record translation lives HERE
geodb-client   Java SDK
geodb-sim      deterministic simulator, invariants, nemesis
geodb-lincheck linearizability + isolation anomaly checkers
geodb-bench    JMH + YCSB-style load generator
geodb-chaos    Docker/Toxiproxy tests
specs/tla      TLA+ specs
```

- Dependencies flow downward: `server -> txn -> range -> mvcc -> storage`; `raft` is consumed by `range`.
- **Core modules** = `raft`, `range`, `txn`, `mvcc`, `cluster`. They may touch time, randomness, network, disk or threads **only** through `geodb-env`.
- Language level **21** for core modules, JDK **25 LTS** toolchain. Do not use APIs newer than 21 in core modules. `geodb-storage` may use the FFM API (`java.lang.foreign`).
- Messages and commands are `record`s and `sealed interface`s; use exhaustive `switch` so an unhandled message type is a compile error.

## 5. Determinism rules (enforced by ArchUnit + forbidden-apis)

Forbidden inside core modules:

- `System.currentTimeMillis`, `System.nanoTime`, `Thread.sleep`, `new Thread`
- `new Random`, `ThreadLocalRandom`, `UUID.randomUUID` (transaction ids come from `Rand`)
- direct `java.nio.file` access
- iterating `HashMap`/`HashSet` where order depends on identity hash codes; use ordered or explicitly sorted structures

Other rules:

- Simulated `Disk` models crash semantics: unsynced writes may be lost, truncated to an arbitrary prefix, or torn at sector granularity (512 B / 4 KiB); `fsync` and reads can fail; bit flips and `ENOSPC` can be injected.
- Simulation runs on one thread with an event queue ordered by `(virtual time, sequence number)`. Production uses one logical owner thread per range.
- **Virtual threads only at the client-facing edge**, never in the core. Avoid `synchronized` there (pinning); use `ReentrantLock`.
- Do not "temporarily" bypass the SPI. If the SPI is missing something you need, extend the SPI.

## 6. Raft host contract (ADR-004)

`RaftNode` API: `tick()`, `step(msg)`, `propose(data)`, `proposeConfChange(c)`, `readIndex(ctx)`, `transferLeadership(target)`, `hasReady()`, `ready()`, `advance(rd)`.

`Ready` = soft state, hard state (term, vote, commit), entries to persist, committed entries, messages, optional snapshot, read states.

The host (`Replica` in `geodb-range`) must, in this order:

1. Persist `hardState` and `entriesToPersist` with `fsync`.
2. Send messages that depend on that persistence (follower acks, vote grants). A leader may send `AppendEntries` in parallel with its own persistence.
3. Apply `committedEntries` to the state machine.
4. Call `advance(rd)`.

Required Raft features, in implementation order (spec 6.4): leader election, log replication, commit rule (leader counts a majority only for entries **of its own term**), no-op on election, PreVote, CheckQuorum, ReadIndex, snapshots/compaction, pipelining + flow control, batching, learners, joint-consensus membership change, leadership transfer, lease reads.

Simulation-time invariants: Election Safety, Leader Append-Only, Log Matching, Leader Completeness, State Machine Safety.

## 7. Storage and MVCC facts

**Raft Log Engine** (ADR-005): one per Store, shared by all ranges, separate from the LSM. Segments of 64 MiB, preallocated. Record: `[len u32][crc32c][rangeId][index][term][type][payload]`, `type` in `{ENTRY, HARD_STATE, TRUNCATE_SUFFIX, SNAPSHOT_MARK}`. One log-writer thread does group commit with a single `fsync` per batch (max wait about 200 us, tunable). Recovery truncates at the first invalid length/CRC. Prefix GC deletes whole segments. A range's `appliedIndex` is written in the **same atomic `WriteBatch`** as the effects of the applied command.

**LsmEngine** (ADR-006): behind `KvEngine` (`write(WriteBatch)`, `get`, `iterator`, `snapshot`, `ingest`, `stats`). CRC32C WAL, off-heap skiplist memtable, 4 KiB prefix-compressed SSTable blocks + index + Bloom filter + footer, MANIFEST with atomic `CURRENT`, leveled compaction (ratio 10), write stalls as back-pressure, segmented LRU block cache, snapshots as pinned sequence numbers. `RocksDbEngine` (JNI) exists only as a comparison baseline and diagnostic fallback. The layers above depend **only** on `KvEngine`.

**HLC** (ADR-007): 64-bit, `wall` 48 high bits (ms) + `logical` 16 low bits. Overflow of `logical` advances `wall` by 1 ms; it never wraps. Reject messages beyond `max_clock_offset`; a node that persistently measures offsets over the bound terminates itself. Physical time is read only through `Clock`.

**MVCC physical key** (ADR-008):

```
[space prefix 1B][escaped user key][0x00 0x01][~ts (8B big-endian)]
```

- Escape each `0x00` in the user key as `0x00 0xFF`. Terminator is `0x00 0x01`. (Doubling to `0x00 0x00` breaks ordering; it was rejected.)
- Timestamp is bit-inverted so newer versions sort first; "latest at or before `t`" is one `Seek`.
- Write intents use an **empty version suffix** and sort before every timestamped version. At most one intent per key.
- Value types: `VALUE`, `TOMBSTONE`, `INTENT`.
- Key spaces: `0x01` user data, `0x02` range metadata, `0x03` transaction records, `0x04` system/cluster.
- GC: per-range `gcThreshold` (TTL + oldest active read timestamp), applied by compaction filter **and** a replicated `GcRange` command so all replicas drop the same set. Reads below it fail explicitly.
- An ordering bug here silently corrupts scans. Fuzz and property-test the encoding.

## 8. Ranges, Multi-Raft, transactions, reads

**Ranges** (ADR-010): contiguous `[startKey, endKey)`, 3 replicas by default, `RangeDescriptor` has a `generation` incremented on every change. All replicas on a Store share one LSM and one Raft log engine; a range is only a key interval. **Splits copy no data** (replicated `Split(splitKey, newRangeId)` command; `maxRangeBytes` initially 64 MiB). Range 0 (system range, itself a Raft group) holds the descriptor map; clients cache descriptors and repair them from `RangeKeyMismatch` / `NotLeaseholder`. Replica move = add learner, snapshot catch-up, promote via joint consensus, then remove the old one. Never remove the old replica before the new one is voting and caught up.

**Transactions** (ADR-009):

- Write intents go through Raft in each key's range. The transaction record (`PENDING` / `COMMITTED` / `ABORTED`) lives in the **anchor key's** range (first written key).
- Commit = one atomic Raft proposal writing `COMMITTED`. Intent resolution afterwards is asynchronous.
- Reader/writer meeting an intent consults the record: resolve if committed, remove if aborted, otherwise push/wait or abort an expired heartbeat.
- Client losing contact during commit gets `AmbiguousResult` and queries by `txnId`.
- Isolation roadmap: **Snapshot Isolation in a single range first (Phase 4)**, with write skew explicitly tested and documented; **Serializable in Phase 6** (timestamp cache, read refresh, uncertainty intervals; raise the timestamp-cache low-water mark after leadership changes).
- Deadlocks: timeouts + priorities first; distributed wait-for graph later.
- Parallel commits (`STAGING`) and a separate lock table are deferred stretch goals.

**Reads** (ADR-011), in order: (1) `ReadIndex`: leader must have committed an entry in its current term, records `commitIndex`, confirms leadership with a majority heartbeat, waits for `appliedIndex >= readIndex`, serves locally. (2) Lease reads: lease strictly shorter than `electionTimeout - max_clock_offset`, configurable and disableable. (3) Follower reads at a closed timestamp: explicitly `STALE_BOUNDED`, never linearizable. Writes always go through the log.

**Client idempotency**: sessions (`clientId`) with per-session `seq`; a dedup table `clientId -> (lastSeq, response)` in the state machine gives exactly-once for non-idempotent writes (e.g. `CompareAndSet`).

**Transport** (ADR-013): gRPC + Protobuf. One bidirectional stream per node pair carrying batched messages of many ranges. Snapshots stream in about 1 MiB chunks with per-chunk CRC, flow control and a rate limiter. Protobuf fields are additive only; removed fields are `reserved`; protocol version negotiated at connect; replicated commands carry a cluster version. Typed retryable errors: `NotLeaseholder`, `RangeKeyMismatch`, `TxnRetry`, `AmbiguousResult`, `ServerBusy`.

**Node concurrency** (spec 14): one thread at a time per range; Raft workers (hash(rangeId) % N), one log-writer thread, apply workers; bounded queues everywhere with `ServerBusy` on saturation. Shared structures (block cache, metrics, routing cache) are tested with JCStress.

## 9. Verification (ADR-012): this is the core of the project

Layers and gates:

1. **Deterministic simulation** (`geodb-sim`): 3 to 7 nodes, `SimClock` (per-node skew/jumps), `SimNetwork`, `SimDisk`; seeded nemesis (crashes, symmetric/asymmetric partitions, delay, duplication, reordering, disk faults, gray failure, membership changes); invariants asserted at every event; liveness check after faults stop; `Buggify` hooks active only in simulation. Failing seeds are stored in `sim/corpus/` and replayed forever.
2. **Linearizability checker** (`geodb-lincheck`): Wing-Gong-Lowe with memoization and per-key decomposition; timed-out operations are indeterminate; emits a minimal violating sub-history. **The checker is tested by injecting known bugs and requiring detection.**
3. **Isolation checker**: Elle-style cycle search over list-append (G0, G1a/b/c, G-single, G2). Must flag write skew under SI and find none under Serializable.
4. **TLA+** (`specs/tla`): Raft + PreVote + CheckQuorum, checked with TLC. Java code references spec actions in comments (`/* TLA: HandleAppendEntriesRequest */`). A spec/code mismatch is a bug in one of them.
5. **Chaos** (`geodb-chaos`): Docker Compose + Toxiproxy, `kill -9`, `SIGSTOP/SIGCONT`, disk-full; histories checked by the same checkers.
6. **Supporting**: jqwik model-based tests (storage and MVCC vs `TreeMap`-style references), JCStress, Jazzer fuzzing (WAL, SSTable, MANIFEST, protobuf decoders), PIT on `geodb-raft` (minimum 85% mutation score).
7. **CI gates**: ArchUnit + forbidden-apis green; all regression seeds pass; nightly fresh seeds find no violation; TLC finds no counterexample.

Rules for agents writing tests:

- Never "fix" a flaky or failing simulation scenario with `sleep`, retries or loosened assertions. Reproduce from `(seed, code version)`, find the cause, add the seed to the corpus.
- Prefer a property or model-based test over hand-picked examples for storage, MVCC and codecs.
- A bug in a simulator component (`SimDisk`, `SimNetwork`) can mask or fabricate real bugs. Test the simulator too.

## 10. Build, commands and conventions

Tooling: Maven multi-module (`maven-enforcer`, own BOM), JUnit 5, AssertJ, jqwik, JCStress, Jazzer, PIT, ArchUnit, forbidden-apis, JMH, HdrHistogram, Micrometer, OpenTelemetry, SLF4J + Logback (JSON), Docker/Testcontainers, Toxiproxy, TLA+/TLC, GitHub Actions.

> The build does not exist yet. The commands below are **targets**; confirm they exist before running them, and update this section when Phase 0 lands.

```
mvn -q verify                                              # full build incl. ArchUnit/forbidden-apis
mvn -pl geodb-sim exec:java -Dseeds=0..1000000             # simulation campaign (from the spec)
```

Conventions:

- Code, identifiers, comments, commit messages, ADRs: **English**. The overview and spec are pt-BR; reply to the human in the language they use.
- Structured logs include `nodeId`, `rangeId`, `term`, `index`, `txnId` and a correlation id.
- Hot paths: allocation discipline, primitives and `byte[]`/`MemorySegment`; memtable and block cache are off-heap (test lifetimes and leaks).
- Benchmarks: open-model load (avoid coordinated omission), HdrHistogram, discarded warm-up, repetitions with confidence intervals, record hardware/JVM/flags. Performance claims are _measured and explained_, never promised.
- Checksums: `java.util.zip.CRC32C`.

## 11. Roadmap and acceptance (a phase ends only when **all** criteria pass)

| Phase | Scope                                                                          | Acceptance (summary)                                                                                 |
| ----- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| 0     | Skeleton, CI, ArchUnit, `geodb-env` real+sim                                   | Green build; rule blocking `System.currentTimeMillis` in core; reproducible virtual-time hello world |
| 1     | Pure Raft (features 1-6), `SimDisk` persistence, invariants, base TLA+         | 1M seeds (3 and 5 nodes, partitions, crashes, reordering) without violations; PIT >= 85%             |
| 2     | Replicated KV, ReadIndex, snapshots, session dedup, lincheck (register model)  | Linearizable histories under nemesis; checker detects injected bugs                                  |
| 3     | Own storage engine + Raft Log Engine, RocksDB comparison                       | Model + crash-at-every-point tests; fuzzing clean; JMH comparison documented                         |
| 4     | HLC, MVCC, intents, TxnRecord, GC (single range, SI)                           | Bank-transfer invariant under faults; write skew demonstrated and documented                         |
| 5     | Multi-Raft, splits, range 0, routing, real gRPC, Docker                        | 3 nodes, hundreds of ranges via splits under load; linearizability across splits and leader changes  |
| 6     | Distributed txns, Serializable, deadlock detection                             | No G2 under Serializable; anomaly detected under SI                                                  |
| 7     | Learners, joint consensus, allocator, repair, leadership transfer, lease reads | Add/remove nodes under load, linearizability intact; dead node's replicas rebuilt                    |
| 8     | Observability, YCSB benchmarks, docs, README                                   | One-command reproducible results, honest limitations                                                 |

Suggested cut line: already excellent after Phase 4, rare after Phase 6. Documentation is written **incrementally**, not at the end.

Non-goals (do not build): Byzantine tolerance, SQL/secondary indexes, TrueTime/atomic clocks, multi-tenancy/ACLs/quotas, beating RocksDB/CockroachDB/TiKV on raw performance.

## 12. Documentation conventions and known gaps

**ADRs** live in `docs/adr/adr-NNNN-<slug>.md`, with Obsidian-style front matter (`title`, `tags: [architecture, adr]`, `project: GeoDB`, `repository: geodb`, `path`, `lang`, `date`, `id`) and sections: Context, Decision, Alternatives Considered, Consequences (Positive/Negative), References (with `[[wikilinks]]` to related ADRs). A decision with relevant alternatives gets its own ADR; to change one, write a new ADR with `supersedes`/`superseded_by`.

**Known inconsistencies to resolve (report, do not silently work around):**

1. ADR-012's H1 reads `# ADR-002 Verification strategy...`; it should be `ADR-012`.
2. Front-matter/typo nits: ADR-005 has `project: GeoDB]`; ADR-003 and ADR-010 have stray `]]]` in a wikilink.
3. The General Description (4.6) describes transactions as "two-phase commit (prepare/confirm)". ADR-009 explicitly rejects classic 2PC with a coordinator in favor of the transaction record as commit point. Treat ADR-009 as authoritative and update the overview.
4. The spec (10.2) says intents live "under the reserved ts 0"; ADR-008 says intents use an **empty version suffix**. Follow ADR-008 and fix the spec.
5. The spec's ADR plan (section 21, 18 items) does not match the 13 ADRs written. Still **without a dedicated ADR**: joint consensus vs single-server change, deadlock detection strategy, node concurrency model, client-session idempotency, LSM comparison method. Write them before implementing the related feature.
6. ADR-003 lists `storage` as not a core module, but ADR-006 requires `LsmEngine` to run on the simulated `Disk`. Interpretation to confirm: `geodb-storage` performs file I/O **only** through the `Disk` SPI so it is simulable; `FileChannel`/mmap live in the real `Disk` implementation; off-heap arenas (memory, not disk) are fine.
7. Spec status says "Draft v0.1" while its front matter says `version: 1.0.0`.

## 13. Working agreements for agents

- Before editing `geodb-raft`, `geodb-storage`, `geodb-mvcc` or `geodb-txn`, read the relevant ADR and spec section, and cite them in your plan or PR description.
- Make small, reviewable changes. Keep each change inside one phase.
- If the docs are silent or ambiguous, **ask or propose an ADR**; do not invent protocol details.
- Do not add dependencies (libraries) without justification; core modules must stay free of gRPC, Protobuf and I/O libraries.
- Do not weaken a CI gate, an ArchUnit rule, a forbidden-API entry, or an invariant to make something pass.
- Record each bug found (category, phase, seed) so it can feed `docs/EVIDENCE.md` and a "bugs I found" write-up.
