# ADR-004: Bounded Resettable Connectivity Statistics Sync

- Status: accepted
- Date: 2026-08-31
- Supersedes: ADR-002

## Context

The merge-only version-1 protocol cannot make a cleared node stay cleared:
another installation can upload its retained contribution and recreate the
aggregate. Creating an immutable file for every reset would fix deletion but
would make the WebDAV directory grow without a hard bound.

## Decision

Android and desktop use the same version-2 connectivity synchronization
protocol.

- WebDAV stores two rotating snapshot slots per installation under
  `clash-connectivity-sync/v2/devices/`. An installation overwrites its slots;
  synchronizations and resets never create additional paths.
- The protocol accepts at most 32 installation identities and two slots per
  identity, so the remote directory contains at most 64 JSON files. Each file
  remains limited to 2 MiB.
- A snapshot carries its monotonically increasing revision, its installation's
  own per-node/day contribution, the contribution's per-node reset generation,
  and the latest reset watermark known for each node.
- A reset generation is ordered by logical counter and then installation ID.
  Merge selects the greatest valid generation per node and ignores statistics
  tagged with older generations. Repeated resets replace the node's watermark;
  reset history is not retained.
- Reset watermarks are gossiped in every installation snapshot. A global clear
  therefore remains effective after another installation observes it, even if
  the installation that originated the clear later disappears.
- The local statistics transaction stores the active reset watermarks with the
  imported-device baseline. Adopting a newer watermark and removing older local
  counters happens in the same transaction, so a retry cannot erase new
  measurements already recorded in the adopted generation.
- A global clear uses reset-wins semantics. Measurements recorded by an offline
  installation under an older generation are excluded when it reconnects.
- A merge is successful only after the new rotating snapshot is uploaded. An
  upload failure does not advance the persisted revision, imported baseline,
  or successful-sync time. Newly adopted reset watermarks are still retained
  locally so a retry cannot accept an older contribution if the reset's source
  snapshot disappears.

## Consequences

- The number of remote files is bounded independently of device lifetime,
  synchronization count, reset count, node count, and generation count.
- Clearing one node does not clear other nodes because reset generations are
  per-node values inside the bounded snapshots, not file-level generations.
- Simultaneous clears converge deterministically without relying on wall-clock
  agreement.
- Version-1 snapshots are not written by version-2 clients. Local version-1
  state is migrated while preserving the installation identity and baseline;
  installations converge again as they upgrade and publish version-2 slots.
