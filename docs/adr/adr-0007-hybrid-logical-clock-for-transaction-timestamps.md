---
title: "Hybrid Logical Clock for transaction timestamps"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0007-hybrid-logical-clock-for-transaction-timestamps.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: 4a1f9161-d359-44a5-9b0a-39876edfdf3d
---

# ADR-007 Hybrid Logical Clock for transaction timestamps

## Context

MVCC and distributed transactions need a total order of timestamps across nodes. Physical clocks are cheap and human-meaningful but drift and can jump; purely logical clocks (Lamport) preserve causality but are unrelated to wall time, which makes garbage-collection thresholds, TTLs, and debugging harder.

Google Spanner solves this with TrueTime, which relies on GPS and atomic clocks. That hardware is not available, and the project must run on commodity machines with zero cost.

## Decision

Use a **Hybrid Logical Clock (HLC)**.

- **Representation:** a 64-bit value, `wall` in the high 48 bits (milliseconds since epoch) and `logical` in the low 16 bits. It is totally ordered as an unsigned long and cheap to store, compare, and encode.
- **Local or send event:** `wall' = max(wall, physicalNow)`; if `wall'` equals the previous `wall`, increment `logical`, otherwise reset it to 0.
- **Receive event with timestamp `m`:** `wall' = max(wall, m.wall, physicalNow)`, with `logical` updated according to which component set the maximum.
- **Logical overflow:** if `logical` would exceed 16 bits, advance `wall` by 1 ms and reset `logical` rather than wrapping.
- **Clock-offset bound:** the cluster configures a `max_clock_offset`. A message whose `wall` exceeds local physical time by more than this bound is rejected. A node that persistently measures offsets beyond the bound terminates itself, as a node with a bad clock can violate safety properties.
- **Uncertainty intervals:** a read at timestamp `t` treats values in `(t, t + max_clock_offset]` as uncertain and restarts the read at a higher timestamp when it encounters one (Serializable mode, ADR-0009).
- **Injection:** the HLC reads physical time exclusively through the `Clock` SPI (ADR-0003), so the simulator can inject skew, drift, and jumps.

## Alternatives Considered

### TrueTime-style bounded-uncertainty clocks with commit-wait

Gives external consistency without uncertainty restarts. Requires specialized hardware. Rejected.

### Centralized timestamp oracle (as in TiDB's PD or Percolator)

Simple and gives a strict global order. Rejected because it makes a single service a throughput and availability bottleneck, and it would itself have to be replicated with Raft; it also hides the clock-related design problems that are part of the learning goal.

### Pure Lamport logical clocks

Preserve causality but have no relation to wall time, which complicates TTLs, GC thresholds, and operability.

### Raw system time

Violates monotonicity under NTP adjustments and clock jumps, and causes unsafe ordering.

## Consequences

### Positive

- Works on commodity hardware with no external dependency.
- Timestamps stay close to wall time, so they are debuggable and usable for GC and TTLs.
- Fully injectable and testable under simulated skew.

### Negative

- Safety relies on a **bounded clock offset** assumption that real infrastructure can violate; the node self-termination rule mitigates but does not eliminate this risk.
- Uncertainty restarts add latency to some reads under Serializable isolation.
- Without commit-wait, the system does not provide Spanner-style external consistency across arbitrary clients; this limitation is documented rather than hidden.

## References

- Kulkarni et al., [Logical Physical Clocks and Consistent Snapshots in Globally Distributed Databases](https://www.cse.buffalo.edu/~demirbas/publications/hlc.pdf) (also [OPODIS 2014](https://doi.org/10.1007/978-3-319-14472-6_2))
- Corbett et al., [Spanner: Google's Globally-Distributed Database (OSDI 2012)](https://www.usenix.org/conference/osdi12/technical-sessions/presentation/corbett)
- Taft et al., [CockroachDB: The Resilient Geo-Distributed SQL Database (SIGMOD 2020)](https://doi.org/10.1145/3318464.3386134)
- Peng & Dabek, [Percolator (OSDI 2010)](https://www.usenix.org/legacy/event/osdi10/tech/full_papers/Peng.pdf) (centralized timestamp oracle approach)
- [TiKV Placement Driver (PD)](https://github.com/tikv/pd) (timestamp oracle in TiDB/TiKV)
- Lamport, [Time, Clocks, and the Ordering of Events in a Distributed System](https://lamport.azurewebsites.net/pubs/time-clocks.pdf)
- [[adr-0003-deterministic-core-and-environment-spi]]
- [[adr-0009-transaction-record-as-the-commit-point-with-snapshot-isolation-before]]
