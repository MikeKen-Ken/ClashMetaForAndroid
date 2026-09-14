param([string]$Java = 'java')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$compiler = Get-ChildItem (Join-Path $gradleHome 'wrapper/dists/gradle-8.10.2-bin') -Recurse -File -Filter 'kotlin-compiler-embeddable-*.jar' | Select-Object -First 1
if (-not $compiler) { throw 'Cached Kotlin compiler from Gradle 8.10.2 is required.' }
$output = Join-Path $env:TEMP ('clash-connection-rows-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
# Compile the production row builder and formatting functions. Remove only
# Android parcel/serialization scaffolding from the production DTOs.
$model = Get-Content (Join-Path $root 'core/src/main/java/com/github/kr328/clash/core/model/Connection.kt') -Raw
$dtos = @('package com.github.kr328.clash.core.model')
foreach ($name in @('Connection', 'ConnectionMetadata')) {
    $match = [regex]::Match($model, "(?s)data class $name\(.*?\n\) : Parcelable")
    if (-not $match.Success) { throw "Cannot extract $name DTO" }
    $dtos += ($match.Value -replace ' : Parcelable$', '' -replace '@SerialName\("[^"]+"\)\s*', '')
}
$dtos -join "`n" | Set-Content (Join-Path $output 'Models.kt') -Encoding utf8
$display = Get-Content (Join-Path $root 'design/src/main/java/com/github/kr328/clash/design/adapter/ConnectionDisplayItem.kt') -Raw
$display = [regex]::Match($display, '(?s)data class ConnectionDisplayItem\(.*?\n\)').Value
"package com.github.kr328.clash.design.adapter`nimport com.github.kr328.clash.core.model.Connection`n$display" | Set-Content (Join-Path $output 'Display.kt') -Encoding utf8
$formatting = Get-Content (Join-Path $root 'design/src/main/java/com/github/kr328/clash/design/util/I18n.kt') -Raw
$formatting = $formatting.Substring($formatting.IndexOf('fun Long.toBytesString()'))
"package com.github.kr328.clash.design.util`nimport java.text.SimpleDateFormat`nimport java.util.*`n$formatting" | Set-Content (Join-Path $output 'Formatting.kt') -Encoding utf8
@"
import com.github.kr328.clash.core.model.Connection
import com.github.kr328.clash.design.connections.ActiveConnectionRows
fun main() {
    val rows = ActiveConnectionRows()
    val a = Connection(id = "a", download = 100, start = "2026-09-14T01:00:00Z")
    val b = Connection(id = "b", download = 50, start = "2026-09-14T02:00:00Z")
    rows.update(listOf(a, b), 1000)
    check(rows.build(true).map { it.connection.id } == listOf("b", "a"))
    rows.update(listOf(a.copy(download = 300), b), 3000)
    val active = rows.build(false)
    check(active[0].trafficDisplayText.contains("100 Bytes/s"))
    // Rebuilding for a tab/sort change must not consume or reset the speed sample.
    check(rows.build(true).last().trafficDisplayText == active[0].trafficDisplayText)
    rows.update(listOf(a.copy(download = 1300)), 13000)
    check(rows.build(false).single().trafficDisplayText.contains("100 Bytes/s"))
    rows.update(listOf(a.copy(download = 0)), 14000)
    check(rows.build(false).single().trafficDisplayText.contains("0 Bytes/s"))
    rows.update(emptyList(), 15000)
    check(rows.build(false).isEmpty())
    rows.update(listOf(a), 16000)
    check(rows.build(false).single().trafficDisplayText.contains("0 Bytes/s"))
    println("Connection row sampling, sort/tab rebuild, gaps, counter reset and removal checks passed")
}
"@ | Set-Content (Join-Path $output 'Check.kt') -Encoding utf8
$lib = $compiler.Directory.FullName
$classpath = (Get-ChildItem $lib -File | Where-Object Name -Match '^(kotlin-stdlib|annotations-)' | ForEach-Object FullName) -join [IO.Path]::PathSeparator
$source = Join-Path $root 'design/src/main/java/com/github/kr328/clash/design/connections/ActiveConnectionRows.kt'
$sources = @(Get-ChildItem $output -Filter '*.kt' | ForEach-Object FullName) + $source
& $Java -cp (Join-Path $lib '*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Isolated Kotlin compilation failed.' }
& $Java -cp ($output + [IO.Path]::PathSeparator + $classpath) CheckKt
if ($LASTEXITCODE -ne 0) { throw 'Connection row checks failed.' }
Write-Host 'Source-isolated checks only; Android lifecycle, binding and device rendering remain unverified.'
