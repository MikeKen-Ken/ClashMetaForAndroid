param(
    [string]$Java = 'java',
    [string]$JsonJar = (Join-Path $env:TEMP 'network-health-test-deps/json-20240303.jar'),
    [switch]$DownloadDependencies
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$compiler = Get-ChildItem (Join-Path $gradleHome 'wrapper/dists/gradle-8.10.2-bin') -Recurse -File -Filter 'kotlin-compiler-embeddable-*.jar' | Select-Object -First 1
if (!$compiler) { throw 'A cached Gradle Kotlin compiler is required.' }
if (!(Test-Path -LiteralPath $JsonJar)) {
    if (!$DownloadDependencies) { throw 'Provide -JsonJar or use -DownloadDependencies to fetch the JVM JSON test library.' }
    New-Item -ItemType Directory -Force (Split-Path $JsonJar -Parent) | Out-Null
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $JsonJar
}
$scratch = Join-Path $env:TEMP ('network-health-kotlin-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $scratch | Out-Null
$lib = $compiler.Directory.FullName
$stdlib = @(Get-ChildItem $lib -File | Where-Object Name -Match '^(kotlin-stdlib|annotations-)' | ForEach-Object FullName)
$testJars = @($JsonJar)
foreach ($artifact in @(
    @{ Name = 'junit-4.13.2.jar'; Path = 'junit/junit/4.13.2/junit-4.13.2.jar' },
    @{ Name = 'hamcrest-core-1.3.jar'; Path = 'org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar' }
)) {
    $target = Join-Path (Split-Path $JsonJar -Parent) $artifact.Name
    if (!(Test-Path -LiteralPath $target)) {
        if (!$DownloadDependencies) { throw "Missing $($artifact.Name); use -DownloadDependencies." }
        Invoke-WebRequest ('https://repo.maven.apache.org/maven2/' + $artifact.Path) -OutFile $target
    }
    $testJars += $target
}
$classpath = ($stdlib + $testJars) -join [IO.Path]::PathSeparator
$reportSource = Join-Path $root 'app/src/main/java/com/github/kr328/clash/diagnostics/NetworkHealthReport.kt'
$tests = Join-Path $root 'app/src/test/java/com/github/kr328/clash/diagnostics/NetworkHealthReportTest.kt'
& $Java -cp (Join-Path $lib '*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $scratch $reportSource $tests
if ($LASTEXITCODE -ne 0) { throw 'Health report compilation failed.' }
& $Java -cp ($scratch + [IO.Path]::PathSeparator + $classpath) org.junit.runner.JUnitCore com.github.kr328.clash.diagnostics.NetworkHealthReportTest
if ($LASTEXITCODE -ne 0) { throw 'Health report regressions failed.' }

# Compile the real dialog against cached Android/AndroidX APIs. Generated R IDs
# are replaced by names from the actual resource XML; this is not an APK build.
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$android = Get-ChildItem (Join-Path $sdk 'platforms') -Recurse -Filter android.jar | Sort-Object FullName -Descending | Select-Object -First 1
if (!$android) { throw 'An installed Android SDK platform is required for dialog compilation.' }
$cache = Join-Path $gradleHome 'caches/modules-2/files-2.1'
$jars = [Collections.Generic.List[string]]::new()
$jars.Add($android.FullName)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$n = 0
foreach ($group in @('androidx.appcompat', 'androidx.activity', 'androidx.fragment', 'androidx.lifecycle', 'androidx.savedstate', 'androidx.core', 'androidx.annotation', 'androidx.collection', 'androidx.arch.core', 'org.jetbrains.kotlinx')) {
    if (!(Test-Path (Join-Path $cache $group))) { continue }
    foreach ($file in Get-ChildItem (Join-Path $cache $group) -Recurse -File | Where-Object { $_.Name -match '\.(aar|jar)$' -and $_.Name -notmatch '(sources|javadoc)' }) {
        if ($file.Extension -eq '.jar') { $jars.Add($file.FullName); continue }
        $zip = [IO.Compression.ZipFile]::OpenRead($file.FullName)
        try {
            $entry = $zip.GetEntry('classes.jar')
            if ($entry) {
                $target = Join-Path $scratch "api-$n.jar"
                $n++
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
                $jars.Add($target)
            }
        } finally { $zip.Dispose() }
    }
}
$resources = [xml](Get-Content (Join-Path $root 'design/src/main/res/values/network_health.xml') -Raw)
$names = @($resources.resources.string | ForEach-Object { 'const val ' + $_.name + ' = ' + (++$n) })
('package com.github.kr328.clash.design' + "`nobject R { object string {`n" + ($names -join "`n") + "`n} }") | Set-Content (Join-Path $scratch 'R.kt') -Encoding utf8
$apiClasspath = ($stdlib + $jars.ToArray()) -join [IO.Path]::PathSeparator
& $Java -cp (Join-Path $lib '*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $apiClasspath -d (Join-Path $scratch 'dialog') $reportSource (Join-Path $root 'app/src/main/java/com/github/kr328/clash/diagnostics/NetworkHealthDialog.kt') (Join-Path $scratch 'R.kt')
if ($LASTEXITCODE -ne 0) { throw 'Android dialog API compilation failed.' }
Write-Host 'Health dialog API compilation passed. Full resources, Binder/JNI linking and device UI remain separate checks.'
