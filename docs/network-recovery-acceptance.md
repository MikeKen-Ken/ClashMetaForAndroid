# Network recovery acceptance

Run with rebuilt Android/desktop clients and the matching rebuilt core. Record
their versions, platform, scenario and start/end UTC times. Do not label unit
tests or a successful reset action as restored application connectivity.

## Collect evidence

The authenticated local controller exposes `GET /network/diagnostics`:
64 events maximum, lifetime counters, no domain/proxy names, no persistent files.
Counters reset when the core restarts. `durationMs` measures the named recovery
action or backup search, not end-to-end outage time. Switches count recovery
replacement decisions; manual and ordinary latency-driven selections are excluded.

Use `scripts/collect-network-acceptance.ps1` with `-Controller`, `-Proxy`, and a
`-Target` you control or are authorized to test. Set `MIHOMO_CONTROLLER_SECRET`
in the environment if authentication is enabled. For Android, forward the local
controller and HTTP proxy ports with ADB. Keep the controller bound to loopback.

The collector makes at most 300 sequential HTTP samples and overwrites one JSON
file (default: `$env:TEMP/network-acceptance.json`). Choose a separate output name
when preserving a baseline. It does not change routes, DNS, profiles or radios.
Use a stable 2xx endpoint; redirects and timeouts count as failed samples.

## Scenario matrix

Run continuous TCP download and bidirectional UDP traffic alongside HTTP samples.
Repeat each transition at least ten times, recording interruption duration and
the counter deltas. Compare the same devices and profiles against the baseline.

| Scenario | Required behavior |
|---|---|
| DNS server changes while route remains stable | DNS reset only; established TCP/UDP remains usable |
| Primary probe endpoint fails, independent endpoint works | No failover or manual-pin loss |
| Current node fails, one backup works | Replacement verified during recovery; traffic resumes |
| All backups fail | Existing selection retained; failed-search counter advances; no switching loop |
| A is being checked while old B connections receive data | A's own replies still veto its failure; B alone cannot veto A |
| Android preferred Wi-Fi briefly appears | Usable current network retained until two-second stability window |
| Android current network is lost/blocked/unvalidated | No additional two-second preference delay |
| Windows route changes | Notification wakes settled fingerprint check; unrelated routes cause no reset |
| Desktop sleep/resume | Resume recovery executes; recovery bursts remain coalesced |
| 30-minute mixed TCP/UDP load and repeated handovers | Bounded diagnostics, no growing queues, no repeated healthy-node switching |

Report median and worst observed application interruption, unnecessary disconnects,
switch counts and failed searches. Include failures; do not infer zero outage from
a missing sample. A normal route handover may break existing TCP connections.

## Current local validation boundary

Local checks on 2026-09-13:

- Go tests passed for network recovery, DNS, adapter, outbound groups and providers;
  the controller route package compiled. Vet passed for changed Go packages.
- Selected traffic, endpoint and diagnostics regressions passed 50 repetitions.
- Six isolated desktop policy tests passed, including a live fingerprint read,
  notification burst coalescing and Windows subscription registration/cleanup.
  Run `scripts/test-desktop-network-policy.ps1` to repeat these focused checks.
- Go race testing was blocked because CGO is disabled in this environment.
- Full desktop `cargo check --lib --locked` was blocked because the existing
  workspace lockfile needs updating; no dependency manifests or lockfiles changed.

ADB detected no connected device. Android Gradle failed before tests with
`java.io.IOException: Unable to establish loopback connection`. These scenarios
remain pending on rebuilt applications; the collector and scenario matrix are
prepared, not a claim of device acceptance.
