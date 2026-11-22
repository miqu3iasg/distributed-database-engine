---
title: "Multi-Raft ranges with one data engine per Store and splits without data copy"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0010-multi-raft-ranges-with-one-data-engine-per-store-and-splits-without-da.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: cc9413d4-405e-4ef1-a8f6-4b5fc4d99938
---

# ADR-010 Multi-Raft ranges with one data engine per Store and splits without data copy

## Context

A single Raft group replicates one log through one leader, so it cannot scale beyond the capacity of a single machine's leader and cannot hold more data than one replica set can store. To scale, the key space must be partitioned, with each partition replicated independently.

Partitioning raises several design questions: how large a partition should be, how to route requests, how a partition is split when it grows, and how a node with thousands of partitions avoids drowning in per-group overhead (heartbeats, fsyncs, threads).

## Decision

1. The ordered key space is divided into contiguous **ranges** `[startKey, endKey)`. Each range is an independent Raft group with, by default, three replicas on distinct nodes. The `RangeDescriptor` includes the replica set and a `generation` that increments on every change.
2. Each node runs one **Store** that hosts many **Replicas**. All replicas on a Store share one **LSM data engine** (ADR-0006) and one **Raft log engine** (ADR-0005). A range is merely a key interval in the shared engine, not a separate database.
3. **Splits do not copy data.** When a range exceeds `maxRangeBytes` (initially 64 MiB) or a load threshold, its leader proposes a replicated `Split(splitKey, newRangeId)` command. When applied, the descriptor is shortened and a new range for the right half is created with the same replicas, inheriting timestamp-cache state and the deduplication table. Replicas lagging behind catch up through log replay or a snapshot.
4. **Efficiency measures:** coalesced heartbeats (one per node pair per tick, listing all ranges), quiescence of idle ranges, shared group commit, and one multiplexed bidirectional transport stream per node pair.
5. **Routing:** a **system range (range 0)**, itself a Raft group, holds the map from keys to descriptors and node metadata. Clients and gateways cache descriptors and correct them from error responses (`RangeKeyMismatch`, `NotLeaseholder`). A two-level scheme (meta1/meta2) is only introduced if range 0 becomes a bottleneck.
6. **Replica movement** is a safe sequence: add as learner, catch up via snapshot, promote with joint consensus, then remove the old replica, so a majority of up-to-date replicas always exists.
7. Raft snapshots of a range are streamed in checksummed chunks and installed by ingesting SSTables directly into the LSM.

## Alternatives Considered

### Single Raft group for the whole database

Simplest, but caps throughput and capacity at one leader and one node's disk. Rejected as the final architecture; it is the correct starting point for Phase 2.

### Hash partitioning instead of range partitioning

Spreads load evenly and avoids hot ranges for sequential keys, but destroys efficient range scans, which transactions and future SQL layers rely on. Rejected.

### A separate LSM instance per range

Makes splits and moves into data copies and multiplies file handles, memtables, and compactions by the number of ranges. Rejected in favor of one shared engine, as TiKV does with regions.

### Centralized placement driver as a separate service (TiKV PD style)

Cleaner separation of metadata and scheduling, but adds another system to build and operate. Starting with a Raft-replicated system range inside the database achieves the same effect with less machinery. Revisit if scheduling logic outgrows range 0.

### Fully decentralized metadata (gossip only)

Avoids a metadata bottleneck but makes strongly consistent routing and descriptor updates harder to reason about. Rejected for the initial design.

## Consequences

### Positive

- Splits are cheap and fast, since they only change metadata.
- Many ranges can make progress in parallel, and write throughput scales with the number of ranges up to host limits.
- Shared engines let group commit amortize `fsync` across ranges.
- The model mirrors CockroachDB and TiKV, so established design knowledge applies.

### Negative

- The Multi-Raft machinery (heartbeat coalescing, quiescence, transport multiplexing) adds complexity.
- Races around splits, such as a lagging replica receiving messages for a range it has not created yet, need careful handling and extensive simulation.
- A shared engine couples the performance of all ranges on a Store; one hot range can cause write stalls for others.
- Range 0 is a critical dependency for routing and scheduling; it must be especially well tested.

## References

- Taft et al., [CockroachDB: The Resilient Geo-Distributed SQL Database (SIGMOD 2020)](https://doi.org/10.1145/3318464.3386134)
- [TiKV: Multi-Raft deep dive](https://tikv.org/deep-dive/scalability/multi-raft/), [TiKV repository](https://github.com/tikv/tikv), and [Placement Driver (PD)](https://github.com/tikv/pd)
- Ongaro, [Consensus: Bridging Theory and Practice](https://web.stanford.edu/~ouster/cgi-bin/papers/OngaroPhD.pdf), Chapter 4 (membership changes) and Chapter 5 (log compaction)
- [[adr-0005-share-raft-log-engine-separate-from-the-lsm-data-engine]]
- [[adr-0006-custom-lsm-engine-behind-a-kvengine-interface-with-rocksdb-as-fallback]]
- [[adr-0011-readindex-for-linearizable-reads-with-lease-reads-as-a-later-optimizat]]
