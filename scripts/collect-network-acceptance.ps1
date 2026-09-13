param(
    [Parameter(Mandatory)][uri]$Controller,
    [Parameter(Mandatory)][uri]$Proxy,
    [Parameter(Mandatory)][uri]$Target,
    [ValidateRange(1, 300)][int]$Samples = 60,
    [string]$OutputPath = (Join-Path $env:TEMP 'network-acceptance.json')
)
$ErrorActionPreference = 'Stop'
if ($Controller.Scheme -notin @('http', 'https') -or
    $Controller.Host -notin @('127.0.0.1', 'localhost', '[::1]', '::1')) {
    throw 'Use a local HTTP controller or an ADB-forwarded loopback controller.'
}
$headers = @{}
if ($env:MIHOMO_CONTROLLER_SECRET) {
    $headers.Authorization = 'Bearer ' + $env:MIHOMO_CONTROLLER_SECRET
}
$base = $Controller.AbsoluteUri.TrimEnd('/')
# Preflight avoids labeling a run against an old core as accepted.
$initial = Invoke-RestMethod "$base/network/diagnostics" -Headers $headers -TimeoutSec 5
$rows = [System.Collections.Generic.List[object]]::new()
for ($i = 0; $i -lt $Samples; $i++) {
    $started = [DateTimeOffset]::UtcNow
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $success = $false
    $status = $null
    try {
        $response = Invoke-WebRequest -Uri $Target -Proxy $Proxy -TimeoutSec 5 -MaximumRedirection 0
        $status = [int]$response.StatusCode
        $success = $status -ge 200 -and $status -lt 300
    } catch { }
    $watch.Stop()
    $diagnostics = $null
    try { $diagnostics = Invoke-RestMethod "$base/network/diagnostics" -Headers $headers -TimeoutSec 5 } catch { }
    $rows.Add([pscustomobject]@{
        at = $started.ToString('o'); success = $success; status = $status
        requestMs = $watch.ElapsedMilliseconds; diagnostics = $diagnostics
    })
    Write-Host ("Sample {0}/{1}: success={2}, requestMs={3}" -f ($i + 1), $Samples, $success, $watch.ElapsedMilliseconds)
    Start-Sleep -Seconds 1
}
[pscustomobject]@{
    scope = 'HTTP through the supplied proxy; TCP continuity, UDP and device handover need separate observation'
    initial = $initial; samples = $rows
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputPath -Encoding utf8
Write-Host "Saved $OutputPath"
