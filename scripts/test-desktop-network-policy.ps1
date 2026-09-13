param([string]$Cargo = 'cargo', [string]$Toolchain = '')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$harness = Join-Path $env:TEMP ('network-policy-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $harness 'src') -Force | Out-Null
$module = Join-Path $root 'clash-verge-rev/src-tauri/src/module/network_recovery'
foreach ($name in @('fingerprint', 'platform_snapshot', 'coordinator', 'notifications')) {
    Copy-Item -LiteralPath (Join-Path $module "$name.rs") -Destination (Join-Path $harness "src/$name.rs")
}
@'
[package]
name = "network-policy-check"
version = "0.1.0"
edition = "2024"
[dependencies]
anyhow = "1"
serde = "1"
serde_json = "1"
tokio = { version = "1", features = ["sync", "rt", "time"] }
'@ | Set-Content (Join-Path $harness 'Cargo.toml')
@'
mod fingerprint;
mod coordinator;
mod notifications;
#[test] fn live_snapshot_smoke() { assert!(fingerprint::capture().is_ok()); }
'@ | Set-Content (Join-Path $harness 'src/lib.rs')
$arguments = @()
if ($Toolchain) { $arguments += "+$Toolchain" }
$arguments += @('test', '--manifest-path', (Join-Path $harness 'Cargo.toml'))
& $Cargo @arguments
if ($LASTEXITCODE -ne 0) { throw "Desktop policy checks failed; harness: $harness" }
Write-Host "Policy checks passed. This does not compile the full desktop application. Harness: $harness"
