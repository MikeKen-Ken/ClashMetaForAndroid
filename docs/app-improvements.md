# App reliability and performance improvements

## Delivered source changes

1. Desktop subscription updates distinguish download failure, downloaded content awaiting activation, successful activation, and activation failure. The Profiles page offers activation retry without another download and shows the last observed successful activation time. Updates and retries are serialized; the existing profile-switch guard protects activation. Bulk update no longer reports success after a failed update. Status is bounded and session-local, not a durable audit trail.
2. Android home and desktop Settings expose **Connection health**. Reports show observation time, the last application reply observed by the core, bounded recovery events and counters. A reset or successful configuration reload is not presented as proof of working application traffic. Export uses an explicit allowlist and excludes configuration, URLs, domains, node names, credentials and free-form errors. Android also reports optional exploration probe activity. Panels refresh on demand.
3. Core releases require focused Go race tests. Android prereleases require core/wrapper recovery tests and Android unit tests plus Kotlin compilation. Desktop autobuilds require TypeScript, connection/report/activation regressions and Rust recovery/profile tests before modifying release tags or assets.
4. Optional Android exploration checks screen-off suspension before queueing and again after acquiring a worker. Existing in-flight probes may finish within their timeout. Normal tunnel state is preserved. A read-only ADB collector supports repeatable baseline/idle/resume observations; no battery-life improvement is claimed without physical-device measurements.
5. Benchmarks exercise production desktop connection merging (including JSON decoding) and Android row construction at 100, 1,000 and 5,000 connections. Each size covers unchanged, traffic-only and churn updates, with warmup followed by 50 samples. The initial processing budget is p95 <= 50 ms. Rendering, IPC/Binder transfer and device frame time require separate measurement.

## Verification on this workstation

| Check | Result |
|---|---|
| Focused core Go tests and vet | Passed |
| Android Go connectivity/tunnel tests, including screen-off probe suppression | Passed |
| Desktop TypeScript checking | Passed |
| Desktop Node regression tests | 11 passed, including existing storage tests |
| Isolated production Rust policy tests | 9 passed; not a full Tauri build |
| Android production report JUnit tests | 3 passed |
| Android health dialog against cached Android/AndroidX APIs | Compiled; not full Android resource/Binder/JNI linking |
| Android production connection-row checks | Passed |
| Workflow YAML, resource XML and PowerShell syntax | Parsed |
| Core Go race tests | Passed on GitHub in run 34903023649; local CGO compiler unavailable |
| Core release builds | Windows amd64, macOS arm64, Linux amd64/arm64 and Android arm64 passed; prerelease uploaded |
| Full Android Gradle build | Blocked before compilation by Windows JDK loopback/Unix-domain socket initialization failure |
| Full desktop Rust build | Dependency lock reconciled; blocked by missing native GNU dlltool/MSVC toolchain |
| Physical Android, desktop UI and network handover acceptance | Not performed; no authorized Android device connected |

Measured artifacts live in `.scratch/app-improvements/`. Desktop 5,000-connection p95 processing was approximately 12.2 ms unchanged, 14.8 ms traffic-only and 22.2 ms churn. Android row construction on this desktop JVM measured approximately 2.3, 6.9 and 5.8 ms respectively. These are synthetic local measurements, not Android hardware or UI frame benchmarks. CPU includes fixture generation; retained heap delta is not an allocation count or proof about leaks.

## Repeat the focused checks

Run from the Android repository unless a command changes directory:

```powershell
./scripts/test-network-health.ps1 -DownloadDependencies
./scripts/test-connection-rows.ps1 -Benchmark -ReportPath .scratch/app-improvements/android-rows-benchmark.json
Push-Location core/src/foss/golang
go test cfa/native/connectivity cfa/native/tunnel
Pop-Location
Push-Location clash-verge-rev
pnpm exec tsc --noEmit
node --test scripts/network-health.test.cjs scripts/connection-merge.test.cjs scripts/profile-activation.test.cjs
node --expose-gc scripts/benchmark-connections.cjs ../.scratch/app-improvements/desktop-benchmark.json --enforce
./scripts/test-app-policy.ps1
Pop-Location
```

The standalone Android script uses cached Kotlin/Android APIs and immutable Maven test artifacts. It runs the same report JUnit source used by Gradle. The isolated Rust harness tests selected production modules; full app compilation remains required on a provisioned build host. The existing storage tests additionally need the `fake-indexeddb` module configured as described by their script.

## Battery and idle acceptance

Use a physical device and the rebuilt app. Keep device, app version, profile, network and workload comparable. Capture separate baseline, screen-off idle and screen-on resume phases, with longer unplugged observations for energy comparisons. USB charging biases battery readings; use an already configured wireless ADB connection for unplugged measurements. ADB itself can perturb sleep, so treat these samples as diagnostics and confirm with an independent idle run.

```powershell
./scripts/collect-android-health.ps1 -Phase baseline -Samples 10 -Output (Join-Path $env:TEMP 'clash-baseline.json')
# Turn the screen off manually, then collect the idle phase.
./scripts/collect-android-health.ps1 -Phase idle -Samples 30 -Output (Join-Path $env:TEMP 'clash-idle.json')
# Wake/unlock manually and exercise real application traffic before the resume phase.
./scripts/collect-android-health.ps1 -Phase resume -Samples 10 -Output (Join-Path $env:TEMP 'clash-resume.json')
```

Each invocation writes one bounded JSON file (at most 120 samples). Specify `-Serial` for multiple devices and `-Package` for a non-alpha installation. The collector does not force Doze, alter radios, disconnect the VPN, reset battery statistics or change settings. Check recorded device-idle state before calling a run a Doze test; screen-off alone does not prove Doze. Missing readings remain unknown.

Compare health exports before/after idle: optional probe starts should stop after existing work drains, tunnel operation should remain available, and real application traffic should update the last-reply observation after resume. Also exercise download-success/activation-failure and retry in the desktop UI, verify no subscription re-download occurs on retry, and check both health exports for private data. Run 1,000/5,000-connection UI scrolling and long-duration memory/CPU observation before changing production defaults.

## Commit boundary

The requested checkpoint was pushed before implementation: parent `04828bcc`, desktop `37ed3a2f`, and the already-published core `c580a731`. New core source is committed and pushed as `19ff048c`. [Core workflow 34903023649](https://github.com/MikeKen-Ken/mihomo/actions/runs/34903023649) passed all jobs. The [Prerelease-Alpha tag and assets](https://github.com/MikeKen-Ken/mihomo/releases/tag/Prerelease-Alpha) were verified against `19ff048c83cd6bfaab585439eaac348aec3f7ab8`, with all five platform archives, checksums and version.txt present. Android and desktop improvements, including the parent gitlink update, remain uncommitted in the working trees for review. Existing oversized files received wiring or focused edits only; independent responsibilities were placed in new files.
