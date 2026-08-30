# ADR-002: Merge-Only WebDAV Connectivity Statistics Sync

- Status: accepted
- Date: 2026-08-30

## Context

Android and desktop keep the same version-2 node connectivity statistics shape,
but each installation records only its own delay-test outcomes. Replacing one
device's file with another loses history, while repeatedly adding a shared total
double-counts data. A single shared WebDAV document also creates a last-writer-wins
race when devices synchronize at the same time.

## Decision

A connectivity sync module owns the merge interface on each client.

- WebDAV stores one bounded snapshot per installation under
  `clash-connectivity-sync/v1/devices/<device-id>.json`.
- Each installation has a stable random device ID and only overwrites its own
  snapshot, so devices never contend for the same remote object.
- Snapshots contain non-negative per-node, per-day success, failure, and effective
  delay totals. The existing 30-day retention rule applies before upload and after
  download.
- Local sync state remembers the last contribution imported from other devices.
  The next local contribution is the current aggregate minus that remembered
  contribution, using saturating subtraction. Remote snapshots are then summed
  once by device ID and written back as the new local aggregate.
- Sync is merge-only: another device's retained contribution is never deleted by
  a local clear. Expired days disappear through normal retention.
- Clients run an opportunistic automatic sync when the configured interval is due.
  The default interval is 24 hours. Manual sync and interval controls live in the
  Node Connectivity Statistics panel.

## Consequences

- Retried syncs are idempotent and concurrent uploads do not overwrite another
  device's snapshot.
- Node names are the cross-device identity. Renamed subscription nodes remain
  separate entries until retention removes the old name.
- WebDAV credentials stay in each client's existing local credential store and are
  not included in statistics snapshots.
- Automatic sync runs while the client is active and also checks whether it is due
  at startup; mobile operating systems may defer execution while the app is closed.
