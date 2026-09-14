# Cross-platform delay and recovery review

Scope: current Android manual-test/JNI/service paths, shared-core URLTest and
health-check/recovery paths, desktop startup testing, result display and auto-test
settings. This is a focused review, not a complete audit of all application features.
Production files were not edited during this review. The pre-existing parent
desktop submodule pointer change was preserved.

## 1. P1: Android-style core tests skip result recording

`core/src/foss/golang/clash/adapter/adapter.go:188` skips recording when the
captured context is canceled. At lines 245-248 URLTest replaces that context with
its own timeout context and defers cancellation. Go runs that cancellation before
the earlier recording defer. Therefore a successful test, or an early connection
failure, is mistaken for a deliberately canceled recovery probe. History, alive
state and connectivity statistics remain stale; actual deadline expiry follows a
different path and can still record failures.

Android's `native/tunnel/connectivity.go:163` uses the affected
WithDelayTestTimeoutMs path. Its returned success count can disagree with the
history/liveness later used by the UI and automatic selection.

Reproduced three times through actual Proxy.URLTest using a local HTTP server:
11-13ms success, zero history entries. Existing TestURLTest tests still pass.
The overlay adds only a test file; production code is unchanged.

## 2. P2: Desktop cached timeout hides newer automatic success

`clash-verge-rev/src/services/delay.ts:493` prefers cached results without comparing
their timestamp with core history. `proxy-item.tsx:86` independently does the
same. Cache TTL is 30 minutes. A failed manual/startup check can therefore hide a
later successful automatic check in the UI and delay-based filtering/sorting.

Reproduced through actual DelayManager: cached delay 0 plus a newer 75ms history
entry still returns 0. The inverse can display old success after a new failure.

## 3. P1: Startup finalization pins a failed URL-test node

`clash-verge-rev/src/services/proxy-live-connectivity-order.ts:177` chooses the
first score-ordered member without checking this test's successful results.
`app-data-provider.tsx:243` invokes it after completing startup tests, overriding
the early picker's verified healthy selection. ForceSet creates a manual pin.
This concerns URLTest groups; fallback follows a different branch.

Reproduced through actual helpers: an offline high-score member and a healthy
75ms backup first select the backup, then finalization pins the offline member.

## 4. P2: Android old test completion deletes a new profile's selection

`service/.../ClashManager.kt:281-285` reads activeProfile after awaiting the native
test. ProfileProcessor.active independently changes that value. If profile A's
test completes after activation of B, it deletes B's saved selection for the same
group name. The operation has no captured profile identity or generation check.

Source-traced interleaving; not reproduced on an Android device. SelectionDao's
delete is a real persisted delete by profile UUID and group name.

## 5. P2: Desktop automatic-delay settings have no scheduling consumer

The enable_auto_delay_detection and auto_delay_detection_interval_minutes fields
are read/written in misc-viewer.tsx and declared/persisted in Rust verge config.
A repository-wide search of frontend and backend source finds no timer or worker
consuming them. Enabling the option or changing its interval cannot schedule the
advertised current-node background tests. Core health checks are separate.

Source audit, not a live UI timer test.

## Reproduction commands

Run from repository root in PowerShell:

```powershell
node .scratch/cross-platform-review/desktop-review.cjs
node .scratch/cross-platform-review/startup-selection.cjs
$reviewRoot = Join-Path (Get-Location) '.scratch/cross-platform-review'
$reviewCore = Join-Path (Get-Location) 'core/src/foss/golang/clash'
@{Replace=@{(Join-Path $reviewCore 'adapter/zz_review_test.go')=(Join-Path $reviewRoot 'adapter_review_test.go')}} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $reviewRoot 'overlay.json')
go -C core/src/foss/golang/clash test -overlay (Join-Path $reviewRoot 'overlay.json') ./adapter -run TestReviewAndroidSuccessfulTestRecordsHistory -count=3
```

These intentionally fail on the reviewed code. No full Android or desktop build,
device acceptance, live recovery stress test or upstream review was performed.
