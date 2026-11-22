---
title: "gRPC and Protobuf for transport, with translation at the module boundary"
tags:
    - architecture
    - adr
project: GeoDB
repository: geodb
path: docs/adr/adr-0013-grpc-and-protobuf-for-transport-with-translation-at-the-module-boundar.md
# Optional relationships with other ADRs:
# supersedes: "[[adr-related-note]]"
# superseded_by: "[[adr-related-note]]"
lang: en-US # switch language
date: "2026-11-22"
id: ddef5144-0f75-45ce-a745-41e9e7578493
---

# ADR-013 gRPC and Protobuf for transport, with translation at the module boundary

## Context

GeoDB needs three kinds of network communication: a client-facing API, node-to-node Raft traffic (high volume, many ranges multiplexed), and bulk transfers such as range snapshots. It also needs a schema that can evolve across versions without breaking mixed-version clusters.

The deterministic core (ADR-0003, ADR-0004) must not depend on any transport library, and the simulator must be able to replace the network entirely.

## Decision

1. Use **gRPC with Protocol Buffers** (via `grpc-netty-shaded`) for the external API, internal RPCs, Raft transport, and snapshot transfer.
2. **Raft transport:** one bidirectional streaming RPC per node pair, carrying batches of messages from many ranges. This supports multiplexing, batching, and coalesced heartbeats.
3. **Snapshots:** a dedicated client-streaming RPC with chunking (about 1 MiB), per-chunk CRC, flow control, and a rate limiter so snapshot traffic does not starve foreground traffic.
4. **Boundary translation:** Raft and range messages and commands are plain Java records and sealed interfaces in core modules. Conversion to and from Protobuf lives in `geodb-server`, not in `geodb-raft`. The simulated `Network` carries the Java objects directly.
5. **Schema evolution rules:** fields are only added; removed fields are `reserved`; the protocol version is negotiated at connection start; replicated commands carry a cluster version so a node never applies a command whose semantics it does not yet understand.
6. **Client semantics:** typed, retryable errors (`NotLeaseholder`, `RangeKeyMismatch`, `TxnRetry`, `AmbiguousResult`, `ServerBusy`), deadlines on every call, and idempotency through client sessions with per-session sequence numbers.

## Alternatives Considered

### Custom binary protocol on raw Netty

Maximum control over framing, batching, and allocation, and potentially higher throughput. Rejected for now because it duplicates what gRPC already provides (streaming, deadlines, flow control, interceptors, TLS) and adds a large surface for bugs unrelated to the project's goals. Can be reconsidered if profiling shows transport overhead dominates.

### REST/JSON over HTTP

Easy to debug, but inefficient, weakly typed, and without native bidirectional streaming. Rejected.

### Aeron or another low-latency messaging library

Excellent latency, but adds a native-heavy dependency and a different operational model. Over-engineered for this stage.

### Apache Thrift or Avro RPC

Viable, but with a smaller ecosystem for streaming and tooling in Java than gRPC. Rejected.

### Protobuf types used directly inside the core

Saves mapping code, but couples the deterministic core to generated classes and to a serialization library, and makes the simulator carry serialization concerns. Rejected in favor of boundary translation.

## Consequences

### Positive

- Streaming, deadlines, TLS, load-balancing hooks, and observability interceptors are available out of the box.
- Schema evolution is well supported and widely understood.
- The core stays free of transport dependencies, preserving determinism and testability.
- Raft message batching over a single stream per node pair keeps connection counts low.

### Negative

- Protobuf serialization and HTTP/2 framing add CPU and allocation overhead on hot paths; batching and message pooling are required to keep it acceptable.
- A mapping layer must be maintained and tested for fidelity (round-trip property tests) between records and Protobuf messages.
- gRPC's flow control and default limits (message size, concurrent streams) need explicit tuning for snapshot transfers.
- Debugging at the wire level is less convenient than with a text protocol; tooling for dumping messages is required.

## References

- [gRPC documentation](https://grpc.io/docs/) and [core concepts (streaming, deadlines)](https://grpc.io/docs/what-is-grpc/core-concepts/)
- [grpc-java](https://github.com/grpc/grpc-java) and [Netty](https://netty.io/)
- [Protocol Buffers documentation](https://protobuf.dev/) and [guidance on updating message types (reserved fields)](https://protobuf.dev/programming-guides/proto3/#updating)
- Alternatives considered: [Aeron](https://aeron.io/), [Apache Thrift](https://thrift.apache.org/), [Apache Avro](https://avro.apache.org/docs/)
- [[adr-0003-deterministic-core-and-environment-spi]]
- [[adr-0004-io-free-raft-note-driven-by-a-ready-advance-contract]]
- [[adr-0010-multi-raft-ranges-with-one-data-engine-per-store-and-splits-without-da]]
