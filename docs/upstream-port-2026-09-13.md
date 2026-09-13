# Selected upstream fixes — 2026-09-13

## Scope

Implemented the seven prioritized fixes from the incremental upstream review.
Android main remains at c454d0e7. Its update-dependencies branch (2954f8e7)
was used as a dependency-alignment reference, not imported wholesale.

| Component | Upstream commit | Adaptation |
| --- | --- | --- |
| mihomo | 20bcda2d | Capture the VLESS decryption instance before deferred constructor cleanup. |
| mihomo | 5f951098 | Fix wildcard backtracking while retaining HasWithMatch reporting. |
| mihomo | 0159cf47 | Correct Hysteria v1 UDP request flag and packet construction. |
| mihomo | 634b3199 | Upgrade sing-quic to v0.0.0-20260904234848-1c242664697a for Hysteria2 session cleanup. |
| Desktop | 44f6f6e1 | Remove startup orphan-file deletion and its unused helpers; explicit deletion remains. |
| Desktop | 695fadf2 | Validate HTTP/HTTPS URLs at the existing native open_web_url boundary and subscription ingestion; route profile home links through that command; remove frontend shell capabilities. Native shell integration remains available for core management. |
| Desktop | 78d6f8ee | Preserve PASS-RULE in proxy-group cleanup. |

The core changes are committed as a043a65a10d3a565b44c899ea45a295e87a2c50f
in MikeKen-Ken/mihomo, on Alpha. The Build workflow completed successfully for
Windows amd64, macOS arm64, Linux amd64/arm64, and Android arm64.
Prerelease-Alpha points to that exact commit; its published version.txt contains
alpha-a043a65. The parent core gitlink is staged at the new commit.
Desktop and Android wrapper source changes are left in the working tree for review.

Both Android wrapper modules were resolved and tidied against the fork core.
This also updates older wrapper requirements to the existing core requirements;
the cfa module now declares Go 1.26, matching the core. The core itself only
changes the sing-quic dependency. The desktop sidecar continues to follow the
release version.txt mechanism.

## Validation

- Passed focused Go package tests for component/trie, transport/hysteria/core,
  listener/sing_vless, and adapter/outbound, using -vet=off.
- Added regressions for overlapping domain wildcards, UDP packet round-trip,
  and VLESS constructor failure after decryption initialization.
- Built the Windows core successfully; go mod verify passed.
- Android arm64 wrapper dependency graph resolved with -mod=readonly.
  Ordinary Windows wrapper tests cannot build the Android/Linux native bindings.
- Desktop TypeScript check passed using the installed compiler directly.
- Three isolated Rust tests passed using the production URL validation module
  and extracted production proxy-group cleanup function. This is not a full
  Tauri build or native UI acceptance test.
- Full desktop cargo check --locked was blocked because the existing lockfile
  needs regeneration. Cargo.toml and Cargo.lock were not changed by this port.
- The broader Go suite failed in the existing Fake-IP recovery test:
  TestFakeIPRecoveryDoesNotDeleteMissingMapping panics in resolver.ClearCache.
  The same failure was reproduced in a detached worktree at the untouched
  b32bb259 baseline. Other reported packages passed.
- Default Go vet reports pre-existing nonconstant logging format strings in
  component/trie/ipcidr_trie.go at lines 155 and 200.

No Android APK, desktop installer, or live-device/network acceptance was completed.
The separate pending recommendations in scripts/upstream-review.json remain pending.
Existing large desktop files were only edited at the relevant call sites; no
unrelated responsibility split was attempted.
