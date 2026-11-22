---
title: "MVCC key encoding with inverted timestamps"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0008-mvcc-key-encoding-with-inverted-timestamps.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: 5eb0128f-6dfe-4461-91f6-6a0a0893d027
---

# ADR-008 MVCC key encoding with inverted timestamps

## Context

Multi-version concurrency control stores several versions of each user key. The common read is "the newest version at or before timestamp `t`". The storage engine is an ordered key-value store (ADR-0006), so the way versions are encoded into physical keys determines how many seeks a read costs, how scans behave, and how garbage collection works.

Two correctness constraints apply: the encoding must preserve the lexicographic order of user keys (a scan over `[a, b)` must not interleave versions of different keys incorrectly), and the separator between user key and timestamp must be unambiguous even when user keys contain arbitrary bytes, including `0x00`.

## Decision

The physical key is:

```
[space prefix: 1 B] [escaped user key] [terminator: 0x00 0x01] [version suffix]
```

- **Escaping:** within the user key, each `0x00` byte is encoded as `0x00 0xFF`. The terminator is `0x00 0x01`. This preserves order: a key that is a strict prefix of another sorts first, because the terminator `0x00 0x01` sorts before the escaped zero `0x00 0xFF`.
- **Version suffix:** the 8-byte HLC timestamp, **bit-inverted** (`~ts`) and encoded big-endian, so that **newer versions sort before older ones**. A single `Seek(key, readTs)` lands on the newest version visible at `readTs`.
- **Write intents** use an **empty version suffix**, which sorts before every timestamped version. Any reader reaching a key therefore sees the provisional value first, and invariants such as "at most one intent per key" can be checked in one step.
- **Value types:** `VALUE`, `TOMBSTONE`, `INTENT` (carrying transaction metadata).
- **Key spaces (1-byte prefixes):** `0x01` user data, `0x02` range metadata, `0x03` transaction records, `0x04` system and cluster metadata.
- **Garbage collection:** a per-range `gcThreshold` derives from a configured TTL and the oldest active read timestamp. Versions shadowed below the threshold are dropped by a compaction filter and by a replicated `GcRange` command so that all replicas discard the same set. Reads below the threshold fail with an explicit error.

## Alternatives Considered

### Ascending timestamps (oldest first)

Natural to write but forces a scan to the end of a key's versions to find the newest, or a reverse seek. Rejected for the cost on the dominant read path.

### Separate version tables or column families per timestamp range

Complicates iteration, ingestion, and snapshotting. Rejected.

### Store version lists in the value (one record per user key)

Makes writes read-modify-write, bloats values for hot keys, and makes compaction-based garbage collection impossible. Rejected.

### Doubling `0x00` as the escape (`0x00 0x00`)

Breaks ordering: the key `"a\0"` would sort before `"a"` because the escaped zero would sort before the terminator. Rejected in favor of `0x00 0xFF`.

### Keep intents in a separate lock table

Used by newer CockroachDB versions and gives cleaner separation, at the cost of an extra lookup per read and a second structure to keep consistent. Deferred; it may supersede this ADR if intent handling becomes a bottleneck.

## Consequences

### Positive

- A read for "latest version at or before `t`" is a single seek.
- Version ordering and user-key ordering both fall out of plain byte-wise comparison, so the LSM needs no custom comparator.
- Intents are encountered first and conflict checks are cheap.
- GC integrates with LSM compaction.

### Negative

- Keys carry a roughly 10-byte overhead per version.
- The escaping rule must be implemented and fuzzed carefully; an ordering bug here silently corrupts scans.
- Inverted timestamps make the raw physical layout less intuitive when debugging; dedicated dump tools are required.
- Hot keys with many versions will degrade scans until GC runs.

## References

- Taft et al., [CockroachDB: The Resilient Geo-Distributed SQL Database (SIGMOD 2020)](https://doi.org/10.1145/3318464.3386134)
- [CockroachDB design document](https://github.com/cockroachdb/cockroach/blob/master/docs/design.md) and [CockroachDB RFCs](https://github.com/cockroachdb/cockroach/tree/master/docs/RFCS) (MVCC, key encoding, and lock table design)
- [[adr-0006-custom-lsm-engine-behind-a-kvengine-interface-with-rocksdb-as-fallback]]
- [[adr-0007-hybrid-logical-clock-for-transaction-timestamps]]
- [[adr-0009-transaction-record-as-the-commit-point-with-snapshot-isolation-before]]
