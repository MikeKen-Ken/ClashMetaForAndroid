# Runtime network stability

Implemented in the shared Mihomo core and the Android/desktop adapters.
Source changes require rebuilt clients and the matching core before they affect
installed applications. Desktop still resolves its sidecar through `version.txt`.

## Preserve working connections

- Desktop captures default-route interfaces separately from DNS. DNS-only changes
  request `dns-changed`; unrelated adapters and DHCP timestamps are excluded from
  route evidence. A changing snapshot must settle before recovery is requested.
- A failed desktop recovery is retried on the next observation rather than
  committing the fingerprint as successfully handled. Deduplication includes the
  recovery kind so a DNS reset cannot suppress a subsequent resume/route reset.
- Android considers default routes and lost source addresses. Adding an address
  does not interrupt established connections.
- DNS recovery counts one upstream exchange, not every singleflight waiter or
  canceled caller. Five failures in five seconds must cover at least three names.
  Evidence is bounded. TCP/UDP replies through failover groups in the last 30 seconds prevent
  DNS failures from escalating to connection closure or a core restart.
  Reporting those replies never waits for the core recovery lock.

## Confirm failures before failover

- The primary group URL remains authoritative for service-specific health.
- If its diagnostic precheck fails, confirm through the same proxy using
  `https://cp.cloudflare.com/generate_204` (or Google's gstatic endpoint when the
  primary is hosted under cloudflare.com). Confirmation expects HTTP 204.
- Diagnostic requests do not write normal connectivity statistics/history.
  Only primary success or failure confirmed at both destinations publishes the
  primary URL's health. Confirmation success preserves manual selection.
- Application data received through that proxy during the check vetoes failover;
  canceled application requests do not count as node failures.

## Select verified backups promptly

- Recovery prioritizes successful measurements from the last five minutes and
  starts by rechecking up to three candidates. If those fail, the rolling search
  expands within the existing global worker ceiling. Periodic checks supply backup history; no new
  polling timer or unbounded probe queue is added.
- Return as soon as a backup succeeds and cancel redundant probes. Cancellation
  does not mark a node dead. Only candidates verified during this recovery are
  eligible as the replacement, regardless of older alive flags.
- Fallback and URL-test recovery keep the selection when no replacement passes.
  Fallback closes affected failed-proxy connections only after verification.
- A working replacement is held for two minutes before normal priority/latency
  selection resumes. A real failure or explicit manual selection takes precedence.

## Android handover

- A preferred network must remain the candidate for two seconds before replacing
  a still-usable current network. The observer rechecks when that window expires,
  even if Android sends no further callback.
- A lost, blocked, suspended, or unvalidated current network does not incur that
  additional delay. Existing 250 ms callback coalescing remains.
- Equal-priority networks retain the current selection.

## Validation boundaries

Focused Go tests cover failure confirmation, diagnostic history isolation, DNS
waiter accounting, working-traffic protection, verified candidate selection,
cancellation, and failback hold. Android policy tests cover handover and route
classification. Desktop tests cover DNS classification and recovery deduplication.

Real Wi-Fi/mobile handovers, sleep/resume, application traffic, and long-running
device acceptance still need the rebuilt applications. Unit tests do not establish
an outage rate or guarantee seamless migration of existing TCP connections.
