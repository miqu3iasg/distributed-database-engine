---
title: "Custom LSM engine behind a KvEngine interface, with RocksDB as fallback"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0006-custom-lsm-engine-behind-a-kvengine-interface-with-rocksdb-as-fallback.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: 1992caef-d54b-4e5e-a1a5-0fdc0cfbe7f5
---

# ADR-006 Custom LSM engine behind a KvEngine interface, with RocksDB as fallback

## Context

Each Store needs a local, ordered, durable key-value engine with atomic batches, consistent snapshots, range iteration, and bulk ingestion (for installing Raft snapshots). The usual choice in production databases is RocksDB (CockroachDB moved to its Go port, Pebble).

Building a storage engine is a large project by itself, and storage bugs can masquerade as consensus bugs and vice versa. On the other hand, the WAL, memtable, SSTable, manifest, and compaction design is one of the highest-value learning areas, and the engine must be compatible with the deterministic `Disk` SPI (ADR-0003), which RocksDB through JNI cannot honor.

## Decision

1. Define a narrow `KvEngine` interface: atomic `write(WriteBatch)`, `get`, `iterator(lower, upper)`, `snapshot()`, `ingest(sstables)`, and `stats()`. The MVCC layer and range layer depend only on this interface.
2. Implement **`LsmEngine`**, a custom LSM-tree in Java:
    - segmented, CRC32C-protected WAL with prefix-valid recovery;
    - concurrent skiplist memtable backed by off-heap arenas;
    - SSTables with 4 KiB prefix-compressed blocks, index block, per-file Bloom filter, and a footer with magic number and checksum;
    - a MANIFEST of version edits with an atomically updated `CURRENT` pointer;
    - **leveled compaction** with a size ratio of 10, write stalls as back-pressure;
    - a segmented-LRU block cache;
    - snapshots as pinned sequence numbers.
3. Implement **`RocksDbEngine`** via JNI behind the same interface as a **comparison baseline and diagnostic fallback**: if a failure appears, swapping engines isolates whether it lives in storage or above it.
4. Verify `LsmEngine` with a model-based test against a `TreeMap` reference (random sequences of puts, deletes, scans, snapshots, flushes, compactions, and crash-and-recover), crash injection at every `write`/`sync` point on the simulated disk, and fuzzing of WAL, SSTable, and MANIFEST readers.

## Alternatives Considered

### Use RocksDB (JNI) only

Pragmatic and fast to start. Rejected as the sole engine because it removes the learning objective, cannot run on the simulated disk, and makes torn-write and crash-recovery behavior untestable by the simulator. It remains as the fallback.

### B-tree engine (e.g., LMDB-style or a custom copy-on-write B+tree)

Better read latency and no compaction, but worse write throughput for the write-heavy workloads of a replicated log-applier, and fewer references that map directly to the target architecture. Rejected.

### Use an embedded Java store (MapDB, H2 MVStore, Chronicle Map)

Reduces effort, but offers poor control over atomic batches, snapshot semantics, ingestion, and crash behavior, which are all required by the layers above.

### In-memory only

Sufficient for early protocol work and used as a stand-in in Phases 1 and 2, but not durable, so it cannot support the recovery guarantees. Not acceptable as the final engine.

## Consequences

### Positive

- Deep control and understanding of durability, compaction, and read/write amplification.
- Full participation in deterministic simulation and crash-at-every-point testing.
- The interface seam allows engine comparison and de-risks debugging.

### Negative

- Substantial implementation and testing effort; correct compaction and MANIFEST handling are notoriously bug-prone.
- Performance will trail RocksDB; the project reports the gap honestly rather than hiding it.
- Off-heap memory management adds complexity (lifetimes, leaks) and must be tested for.

## References

- O'Neil et al., [The Log-Structured Merge-Tree (LSM-Tree)](https://www.cs.umb.edu/~poneil/lsmtree.pdf)
- Chang et al., [Bigtable: A Distributed Storage System for Structured Data (OSDI 2006)](https://research.google/pubs/bigtable-a-distributed-storage-system-for-structured-data/)
- [LevelDB implementation notes](https://github.com/google/leveldb/blob/main/doc/impl.md) and [table format](https://github.com/google/leveldb/blob/main/doc/table_format.md)
- [RocksDB](https://rocksdb.org/) and its [wiki](https://github.com/facebook/rocksdb/wiki) (compaction, MANIFEST, WAL)
- [Pebble](https://github.com/cockroachdb/pebble) (Go port of RocksDB-style engine used by CockroachDB)
- Alternatives considered: [LMDB](http://www.lmdb.tech/doc/), [MapDB](https://mapdb.org/), [H2 MVStore](https://h2database.com/html/mvstore.html), [Chronicle Map](https://github.com/OpenHFT/Chronicle-Map)
- Testing tools: [jqwik](https://jqwik.net/), [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer)
- [[adr-0003-deterministic-core-and-environment-spi]]
- [[adr-0005-share-raft-log-engine-separate-from-the-lsm-data-engine]]
- [[adr-0008-mvcc-key-encoding-with-inverted-timestamps]]
