# Connection monitoring optimizations

Implemented locally; no commits, releases, app installs or core deployment.

- Android connections polling stops on ActivityStop and restarts on ActivityStart. Cancelled refreshes propagate cancellation rather than producing an empty snapshot. Core LAN block rules remain configured independently of this page.
- Android active rows are formatted only when shown; unchanged closed tabs are not rebuilt every second. Sampling survives tab/sort changes. Start-time labels are bounded to 512 entries and application icons to 128 entries. Traffic-only binds retain static views and actions use the latest bound item.
- Desktop core collects up to 64 concurrent statistics results per disk transaction, with a 2 ms collection window and a 64-entry waiting queue. Callers wait until the write attempt finishes. Existing mutex/disk locking, disk reload, failure deduplication, reset data and opaque sync metadata are retained. Persistence remains best effort on disk/lock errors, as before.
- Desktop IndexedDB stores closed history only under the list key. Active snapshots omit it; reads support legacy embedded history. Explicit empty lists win. Writes are serialized, throttled and commit-acknowledged, and paired pending writes use one transaction.

## Verification

Passed:
- Full Go adapter package tests and go vet ./adapter.
- Statistics batch/reset/sync tests repeated 50 times; bounded batches and acknowledgement checked.
- Desktop tsc --noEmit.
- Five fake-indexeddb regressions: single-copy storage, legacy migration, empty-history precedence, clear ordering and hidden-page flush.
- Isolated Kotlin production row-builder/formatter checks: sampling, tab/sort rebuilds, time gaps, counter reset and connection removal. Android parcel scaffolding is excluded by the runner.

Synthetic Go benchmark (Windows, 1,000 stored nodes, 64 successful results, 3 iterations):
- 64 individual transactions: 822.94 ms, 62,905,824 allocated bytes per operation.
- One 64-result transaction: 11.33 ms, 936,656 allocated bytes per operation.
This measures persistence only, excluding probe/network time and batch collection. Real batch occupancy varies. Sparse results can incur up to the 2 ms collection window plus scheduling time.

Blocked/unverified:
- Android Gradle failed before compilation: java.io.IOException: Unable to establish loopback connection.
- Go race checks: CGO disabled; gcc/clang unavailable on PATH.
- Android lifecycle/UI/device acceptance, real browser IndexedDB and live core/network acceptance remain untested.

## Reproduce

From the parent repository (PowerShell):

```powershell
./scripts/test-connection-rows.ps1
Push-Location core/src/foss/golang/clash
go test ./adapter
go vet ./adapter
go test ./adapter -run '^TestDesktopStats' -count=50
go test ./adapter -run '^$' -bench BenchmarkDesktopStatsPersistence -benchtime=3x -benchmem
Pop-Location
$deps = Join-Path $env:TEMP 'clash-connection-storage-test-deps'
npm install --prefix $deps fake-indexeddb --no-package-lock --ignore-scripts --no-audit --no-fund
$env:FAKE_INDEXEDDB_MODULE = Join-Path $deps 'node_modules/fake-indexeddb'
Push-Location clash-verge-rev
node --test scripts/connection-storage.test.cjs
node node_modules/typescript/bin/tsc --noEmit
Pop-Location
```

Device acceptance: background the connections page and verify queries cease; resume and verify immediate refresh; check live speeds, sorting, closed-tab updates, app icons and actions after recycling rows. Test a large delay run alongside statistics resets/WebDAV sync with the rebuilt matching core.
