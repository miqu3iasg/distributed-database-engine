# GeoDB Glossary

A distributed, transactional, ordered key-value database in Java. Data is split into **ranges**, each replicated by its own **Raft** group; transactions use **MVCC** with a **Hybrid Logical Clock**; correctness is shown through **deterministic simulation**, checkers, and a formal model. This is the shared vocabulary: use these terms in issues, PRs, ADRs and code.

## Language

### Cluster and data

**Cluster**:
A set of Nodes working together that clients see as one database.

**Node**:
One running GeoDB process on one machine.
_Avoid_: server, instance, host (use **Node**; "host" only for the OS machine)

**Store**:
The per-node unit that owns one data directory, one **LSM engine**, and one **Raft Log Engine**, and hosts many **Replicas**.

**Range**:
A contiguous key interval `[startKey, endKey)`, replicated independently. A Range is only a key interval in the Store's shared engine, not a separate database.
_Avoid_: shard, partition, region

**Replica**:
One copy of a **Range** on one **Store**, with its own **Raft node** and state machine. By default each Range has three.

**RangeDescriptor**:
The record describing a Range: id, key span, replica set, and a `generation` that increments on every change.

**Range 0 (system range)**:
A special Raft group holding the key-to-descriptor map and node metadata, used for routing.

**Split**:
Dividing a Range in two via a replicated command. No data is copied; only metadata changes.

**Gateway**:
The node that receives a client request and runs the **Transaction coordinator** for it. Any node can be a gateway.

### Consensus

**Raft group**:
The set of Replicas of one Range that agree on a single log through Raft.

**Leader**:
The Replica of a Raft group that accepts writes and replicates them. One per **Term**.

**Term**:
A Raft epoch number. At most one leader per term.

**Quorum (majority)**:
More than half of a group's voting Replicas. Writes commit and leaders are elected only with a quorum.

**Learner**:
A non-voting Replica that receives the log, used to add nodes safely.

**Joint consensus**:
The two-phase membership-change procedure (`C_old,new` then `C_new`) that keeps a majority at every step.

**Ready / advance**:
The contract of the I/O-free Raft node: it returns a `Ready` describing what the host must do (persist, send, apply), and the host calls `advance` afterwards, in a fixed order.

**Commit index / applied index**:
The highest log index known committed by a quorum / the highest applied to the state machine. `applied <= commit`.

**ReadIndex**:
The default linearizable read path: the leader confirms leadership with a majority, waits for its applied index to reach the recorded commit index, then reads locally.

**Lease read**:
A later optimization where a leader serves reads locally while holding a time-bound lease. Safe only under the **max clock offset** assumption.

**Raft snapshot**:
A compacted copy of a range's state sent to a lagging replica, installed by ingesting SSTables.

### Time and versions

**HLC (Hybrid Logical Clock)**:
A 64-bit timestamp, 48 bits of wall time plus 16 bits of logical counter, giving causal order close to real time without special hardware.

**Max clock offset**:
The configured bound on clock disagreement between nodes. Safety of uncertainty handling and lease reads depends on it.

**MVCC**:
Multi-version concurrency control. Each write creates a new timestamped version; readers see a consistent snapshot without blocking writers.

**Intent**:
A provisional (uncommitted) MVCC version carrying transaction metadata. At most one per key.

**Tombstone**:
A version marking a key as deleted.

**GC threshold**:
The per-range timestamp below which shadowed versions are removed.

### Transactions

**Transaction**:
A group of reads and writes, possibly across several ranges, that commits atomically (all or nothing) at a defined isolation level.

**Transaction record**:
The replicated record (`PENDING`, `COMMITTED`, `ABORTED`) stored in the range of the transaction's **anchor key**. Writing `COMMITTED` is the single atomic **commit point**.

**Anchor key**:
The first key a transaction writes; its range hosts the transaction record.

**Transaction coordinator**:
The component at the gateway that tracks a transaction, heartbeats its record, and drives commit.

**Snapshot Isolation (SI)**:
Reads at a fixed timestamp, first-committer-wins on write-write conflicts. Allows **write skew**. Implemented first (Phase 4).

**Serializable**:
Stronger isolation, added in Phase 6 with a **timestamp cache**, read refresh, and uncertainty intervals.

**Write skew**:
An anomaly allowed by SI where two transactions read overlapping data and write disjoint keys, breaking an invariant. Tested on purpose.

**Timestamp cache**:
Per-range memory of the highest read timestamp per key, used to push conflicting writes under Serializable.

**Uncertainty interval**:
The window `(readTs, readTs + max_clock_offset]` where values may be causally earlier than the read; encountering one restarts the read.

**Linearizability**:
Single-key guarantee that operations appear to take effect atomically between invocation and response. Checked by `geodb-lincheck`.

### Storage

**KvEngine**:
The narrow interface (`write`, `get`, `iterator`, `snapshot`, `ingest`, `stats`) all upper layers use. Implemented by `LsmEngine` and, as a baseline, `RocksDbEngine`.

**LSM engine**:
The custom log-structured merge-tree: WAL, memtable, SSTables, MANIFEST, leveled compaction.

**Raft Log Engine**:
The shared, append-only log store for all ranges on a Store, with **group commit**. Separate from the LSM.

**Group commit**:
One `fsync` serving the pending writes of many ranges.

**WAL**:
A write-ahead log. GeoDB has two: the LSM's WAL and the Raft log (see ambiguities).

**SSTable / MANIFEST**:
An immutable sorted file on disk / the log of file-set changes that defines the current version of the LSM.

### Determinism and verification

**Core module**:
`raft`, `range`, `txn`, `mvcc`, `cluster`. They may touch time, randomness, network, disk and threads only through `geodb-env`.

**Environment SPI (`geodb-env`)**:
The interfaces `Clock`, `Timers`, `Rand`, `Network`, `Disk`, each with a real and a simulated implementation.

**Deterministic simulation**:
Running the whole cluster on one thread with virtual time and simulated network and disk, reproducible from a **seed**.

**Seed**:
The number that fully determines a simulated run. A failure is reproduced from `(seed, code version)`.

**Nemesis**:
The seeded component that injects faults: crashes, partitions, delay, duplication, reordering, clock skew, disk faults.

**Buggify**:
Hooks that enable extra rare faults only under simulation.

**Invariant**:
A property asserted at every simulation event (for example Log Matching).

**Regression corpus**:
The stored set of failing seeds in `sim/corpus/`, replayed on every commit forever.

**Checker**:
`geodb-lincheck`'s linearizability and isolation-anomaly (Elle-style) verifiers. They are themselves tested with injected bugs.

**Chaos test**:
A test with real processes (Docker, Toxiproxy, `kill -9`) verified by the same checkers.

### Project process

**ADR**:
Architecture Decision Record. Immutable once accepted; changed only by a superseding ADR.

**Phase**:
A roadmap stage (0 to 8) that ends only when all its acceptance criteria pass.

**Evidence ledger**:
`docs/EVIDENCE.md`, generated by CI with measured results. Never hand-edited.

## Relationships

- A **Cluster** has many **Nodes**; a **Node** has one **Store**; a **Store** hosts many **Replicas**.
- A **Range** has one **Raft group**, made of its **Replicas**, with one **Leader** per **Term**.
- All **Replicas** on a **Store** share one **LSM engine** and one **Raft Log Engine**.
- A **Transaction** has one **Transaction record**, located in the range of its **Anchor key**, and many **Intents**.
- A **Seed** reproduces a **Deterministic simulation**; a failing **Seed** joins the **Regression corpus**.

## Flagged ambiguities

- **"snapshot"** means three things. Resolved: **Raft snapshot** (state sent to a lagging replica), **KvEngine snapshot** (a pinned sequence number for consistent reads), and **Snapshot Isolation** (the transaction isolation level). Always use the full term.
- **"log"** can mean the **Raft log** (replicated entries), the **LSM WAL** (local crash recovery), or application logs. Use **Raft log**, **LSM WAL**, and **structured logs**.
- **"commit"** has two levels. A **committed log entry** is replicated on a quorum; a **COMMITTED transaction** has its record written as `COMMITTED`. Say which.
- **"lease"** appears in **Lease read** (leader time-bound read right) and in range lease/leaseholder error names. Prefer the specific term.
- **"two-phase commit"** appears in the general description, but ADR-0009 rejects classic 2PC with a dedicated coordinator. GeoDB's commit is the **transaction record** as the single commit point.
- **"node"** vs **"Store"** vs **"Replica"**: a node is a process, a Store is its storage and hosting unit, a Replica is one range copy. Do not use them interchangeably.
- **"shard" / "partition" / "region"** are not domain terms. Use **Range**.
