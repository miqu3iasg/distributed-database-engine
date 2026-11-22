---
title: "Shared Raft log engine, separate from the LSM data engine"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0005-share-raft-log-engine-separate-from-the-lsm-data-engine.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: 02b71133-5fba-47fe-98d3-4b4695648a68
---

# ADR-005 Shared Raft log engine, separate from the LSM data engine

## Context

Each node hosts replicas of many ranges, each with its own Raft log. Every proposal must be made durable (`fsync`) before it can be acknowledged, and `fsync` is the dominant cost of a linearizable write.

Raft logs have a distinctive access pattern: append-only writes, reads that are mostly sequential and recent, truncation of a prefix after snapshots, and occasional truncation of a suffix when a new leader overwrites conflicting entries. Data in the key-value store has a very different pattern: random writes, point and range reads, and long-lived data.

If each range had its own log file, a node with thousands of ranges would issue thousands of independent `fsync` calls. Storing the Raft log inside the LSM would introduce write amplification from compaction for data that is deleted soon after.

## Decision

Introduce a dedicated **Raft Log Engine** per Store, shared by all ranges on that Store, and separate from the LSM data engine.

- **Format:** segmented append-only files (target 64 MiB, preallocated). Each record is `[len][crc32c][rangeId][index][term][type][payload]`, with `type` in `{ENTRY, HARD_STATE, TRUNCATE_SUFFIX, SNAPSHOT_MARK}`.
- **Group commit:** a single log-writer thread collects `Ready` batches from all ranges, writes them in one append, and issues **one `fsync`**, bounded by a maximum wait (initially around 200 µs) and batch size.
- **Recovery:** read sequentially and truncate at the first record with an invalid length or CRC, which handles torn writes.
- **In-memory index:** `rangeId → (firstIndex, lastIndex, offsets)`, rebuilt on startup.
- **Compaction:** whole segments are deleted once every range in them has advanced past its compacted index; no rewriting.
- **Suffix truncation:** written as a `TRUNCATE_SUFFIX` record plus an index adjustment, not by rewriting data.
- **Atomicity with the state machine:** each range's `appliedIndex` is written in the same atomic `WriteBatch` as the effects of the applied commands in the LSM engine, so re-applying after a crash is idempotent.

## Alternatives Considered

### One log file per range

Simple conceptually, but it destroys group commit and scales badly: thousands of open files and thousands of `fsync` calls.

### Store the Raft log in the LSM engine

Reuses one component, but suffers from compaction-driven write amplification on short-lived data, and couples log durability to LSM tuning. TiKV originally did this and later introduced a dedicated Raft Engine for these reasons.

### One shared LSM column family for all logs

Reduces file count but keeps write amplification and compaction interference.

### Rely on the OS page cache without explicit `fsync`

Faster, but violates the durability guarantee that an acknowledged write survives a crash of a majority-minus-one nodes. Rejected.

## Consequences

### Positive

- One `fsync` serves many ranges, which multiplies write throughput under concurrency.
- Log access patterns are served by a structure designed for them; no compaction cost for logs.
- Torn-write handling is simple and testable against the simulated disk.

### Negative

- A second storage engine to implement, test, and fuzz.
- Garbage collection is coarse: a single slow or lagging range can keep an entire segment alive.
- Recovery time grows with the number of retained segments, so log truncation policy matters.
- The group-commit wait introduces a small latency floor under low load; it must be tunable.

## References

- [TiKV Raft Engine](https://github.com/tikv/raft-engine) (dedicated, shared log engine for Multi-Raft)
- [TiKV](https://github.com/tikv/tikv) and its [Multi-Raft deep dive](https://tikv.org/deep-dive/scalability/multi-raft/) (batching of Raft `Ready` across groups into a single write)
- Ongaro, [Consensus: Bridging Theory and Practice](https://web.stanford.edu/~ouster/cgi-bin/papers/OngaroPhD.pdf), Chapter 11 (performance)
- [[adr-0004-io-free-raft-note-driven-by-a-ready-advance-contract]]
- [[adr-0006-custom-lsm-engine-behind-a-kvengine-interface-with-rocksdb-as-fallback]]
- [[adr-0010-multi-raft-ranges-with-one-data-engine-per-store-and-splits-without-da]]
