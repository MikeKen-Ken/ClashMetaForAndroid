# Fix validation

All five reviewed issues have source fixes. No installer, APK, core release,
installation, commit or application restart was performed.

- Core: timeout cleanup runs after result recording. Successful and immediate
  failed probes update history/liveness; externally canceled probes do not.
- Desktop: both card layouts and shared delay lookups resolve cached/core
  results by timestamp, retaining an active test's spinner until it settles.
- Desktop: URL-test startup finalization pins only a successful node from this
  test session, within the group's timeout. No result means no speculative pin.
- Android: test completion captures the original profile identity and cannot
  delete another profile's persisted selection after a profile switch.
- Desktop: the auto-delay toggle and interval now control one recurring
  current-node test. The app-level component survives page navigation. Disabling
  stops future tests; a request already sent to the core can finish normally.

## Checks

- Core adapter, provider, outbound-group and network-recovery Go package tests passed.
- Android native tunnel Go package tests passed.
- Three isolated Kotlin/JUnit profile-completion tests passed, including a
  suspended operation resumed after switching profiles.
- Nine new desktop regression/component tests passed.
- Existing 300-node startup regression passed: 300 successes, 300 requests,
  peak 150 in both manual and automatic paths.
- TypeScript validation and targeted lint of new source files passed.
- Diff whitespace checks passed for all three repositories.
- Android Gradle `:service:testMetaDebugUnitTest --offline --no-daemon` could not
  start its build process: `java.io.IOException: Unable to establish loopback connection`.
- Android-specific `cmfa` tagged execution was not available on the Windows
  host: the standalone core module cannot resolve `cfa/native/delegate`, and
  the parent module excludes the Android-only `cfa/native/platform` files.
- Desktop frontend production build passed (Vite, 14,214 modules, 2m 32s).
  It reported an empty vendor-lodash chunk and stale Browserslist data; neither
  prevented the build. This is the frontend bundle, not a Tauri installer.

The Kotlin runner is `scripts/test-profile-delay-completion.ps1`. Desktop tests
live under `clash-verge-rev/scripts/*delay*.test.cjs` and
`clash-verge-rev/scripts/startup-selection.test.cjs`.

`findings.md` records the original review. Its scratch reproductions are historical;
the permanent regression tests above are the maintained validation entrypoints.

Existing `src/services/delay.ts` remains above the 800-line soft limit (849 lines).
New timestamp reconciliation and timer responsibilities were put in separate files;
a broader split of the existing manager is left for a dedicated refactoring task.
