# ADR-001: Typed Cross-Client Network Recovery

- Status: accepted
- Date: 2026-08-29

## Context

Android and desktop both run Mihomo, but they previously reacted differently to route and DNS failures. Android could notify the embedded core directly, while desktop had no route-change trigger and an older core revision emitted a log sentinel that no desktop code consumed. Recovery details were spread across DNS, Android native glue, and individual protocol adapters.

## Decision

Mihomo owns a `networkrecovery` module with one typed recovery interface and a structured report.

- `dns-changed` resets resolver caches and upstream DNS connections without interrupting established tunnels.
- `dns-failure` first performs DNS-only recovery, then escalates to a full route recovery when failures continue.
- `route-changed` closes connections tied to the previous route, flushes Fake-IP/DNS state, and resets reusable protocol sessions.
- Persistent failure after full recovery sets `restartRecommended` in the typed recovery status.
- Full recovery is serialized and deduplicated to prevent callback bursts from causing recovery storms.

Android calls the module through its native bridge after updating the VPN underlying network. Desktop observes a hashed interface, route, and DNS fingerprint and uses the authenticated local Mihomo controller socket. Desktop may restart Mihomo only when typed status recommends it and a five-minute cooldown permits it. Logs are diagnostic output, not a control protocol.

Profile activation remains transactional at the client layer: a failed candidate keeps or restores the last successfully loaded profile.

## Consequences

- Both clients share recovery semantics while retaining platform-specific network observation adapters.
- Protocol adapters that own reusable sessions implement `NetworkStateResetter`; stateless adapters need no new interface.
- New recovery kinds or escalation behavior must be added to the core interface and all affected adapters/tests together.
- Until the desktop pin references a released build containing the controller routes, desktop falls back to the legacy authenticated connection-close and Fake-IP flush operations; full adapter-session reset requires the new route.
- Device, sleep/resume, and cross-platform runtime acceptance remains necessary because static checks cannot reproduce real route handovers.
