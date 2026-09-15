param(
    [string]$Adb = (Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'),
    [string]$Serial = '',
    [string]$Package = 'com.github.metacubex.clash.alpha',
    [ValidateSet('baseline', 'idle', 'resume')][string]$Phase = 'baseline',
    [ValidateRange(1, 120)][int]$Samples = 1,
    [ValidateRange(1, 3600)][int]$IntervalSeconds = 60,
    [string]$Output = (Join-Path $env:TEMP 'android-health.json')
)
$ErrorActionPreference = 'Stop'
if ($Package -notmatch '^[a-zA-Z0-9_.]+$') { throw 'Invalid Android package name.' }
$devices = & $Adb devices
if ($LASTEXITCODE -ne 0) { throw 'ADB device discovery failed.' }
$ready = @($devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
if (!$Serial) {
    if ($ready.Count -ne 1) { throw 'Connect exactly one authorized device or specify -Serial.' }
    $Serial = $ready[0]
}
if ($Serial -notin $ready) { throw 'The selected device is not connected and authorized.' }
function Read-Adb([string[]]$Arguments) {
    $text = & $Adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB measurement failed: $($Arguments[0])" }
    return $text -join "`n"
}
$version = Read-Adb @('shell', 'dumpsys', 'package', $Package)
if ($version -notmatch 'versionName=([^\r\n]+)') { throw 'Package not installed on the selected device.' }
$versionName = $Matches[1].Trim()
$results = [Collections.Generic.List[object]]::new()
for ($sample = 0; $sample -lt $Samples; $sample++) {
    $battery = Read-Adb @('shell', 'dumpsys', 'battery')
    $memory = Read-Adb @('shell', 'dumpsys', 'meminfo', $Package)
    $cpu = Read-Adb @('shell', 'dumpsys', 'cpuinfo')
    $power = Read-Adb @('shell', 'dumpsys', 'power')
    $idle = Read-Adb @('shell', 'dumpsys', 'deviceidle')
    $frames = Read-Adb @('shell', 'dumpsys', 'gfxinfo', $Package)
    $values = [ordered]@{ observedAt = [DateTime]::UtcNow.ToString('o'); phase = $Phase }
    foreach ($key in @('level', 'temperature', 'voltage', 'charge counter', 'AC powered', 'USB powered', 'Wireless powered')) {
        if ($battery -match ('(?m)^\s*' + [regex]::Escape($key) + ':\s*([^\r\n]+)')) { $values[$key] = $Matches[1].Trim() }
    }
    $values.memory = @($memory -split "`n" | Where-Object { $_ -match '^\s*(TOTAL\s|TOTAL PSS:|Native Heap|Dalvik Heap)' })
    $values.cpu = @($cpu -split "`n" | Where-Object { $_ -match ([regex]::Escape($Package) + '(?::|/|\s)') })
    $values.power = @($power -split "`n" | Where-Object { $_ -match 'mWakefulness=|mIsPowered=' })
    $values.idle = @($idle -split "`n" | Where-Object { $_ -match 'mState=|mLightState=|mScreenOn=|mCharging=' })
    $values.frames = @($frames -split "`n" | Where-Object {
        $_ -match 'Total frames rendered:|Janky frames:|\d+th percentile:|Number Missed Vsync:|Number Slow UI thread:'
    })
    $results.Add($values)
    # One bounded artifact. Preserve phases using a different -Output for each run.
    [ordered]@{
        schemaVersion = 1; appVersion = $versionName; package = $Package
        scope = 'Read-only ADB samples. No radio, battery, idle, VPN or statistics reset is performed. CPU values use the system sampling window; missing values are unknown.'
        samples = $results.ToArray()
    } | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Output -Encoding utf8
    if ($sample + 1 -lt $Samples) {
        for ($remaining = $IntervalSeconds; $remaining -gt 0; $remaining -= 30) {
            Start-Sleep -Seconds ([Math]::Min(30, $remaining))
        }
    }
}
Write-Host "Saved $($results.Count) read-only samples to $Output"
