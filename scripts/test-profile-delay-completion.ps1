param([string]$Java = 'java')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
$compiler = Get-ChildItem (Join-Path $gradleHome 'wrapper/dists/gradle-8.10.2-bin') -Recurse -File -Filter 'kotlin-compiler-embeddable-*.jar' | Select-Object -First 1
if (-not $compiler) { throw 'The cached Gradle 8.10.2 Kotlin compiler is required for this isolated check.' }
$lib = $compiler.Directory.FullName
$output = Join-Path $env:TEMP ('clash-profile-delay-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -Force | Out-Null
$classpath = (Get-ChildItem $lib -File | Where-Object Name -Match '^(kotlin-stdlib|junit-|hamcrest-|annotations-)' | ForEach-Object FullName) -join [IO.Path]::PathSeparator
$source = Join-Path $root 'service/src/main/java/com/github/kr328/clash/service/ProfileScopedDelayTest.kt'
$guardSource = Join-Path $root 'service/src/main/java/com/github/kr328/clash/service/DelayTestSelectionGuard.kt'
$test = Join-Path $root 'service/src/test/java/com/github/kr328/clash/service/ProfileScopedDelayTestTest.kt'
& $Java -cp (Join-Path $lib '*') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath $classpath -d $output $source $guardSource $test
if ($LASTEXITCODE -ne 0) { throw 'Kotlin test compilation failed.' }
& $Java -cp ($output + [IO.Path]::PathSeparator + $classpath) org.junit.runner.JUnitCore com.github.kr328.clash.service.ProfileScopedDelayTestTest
if ($LASTEXITCODE -ne 0) { throw 'Profile delay-completion tests failed.' }
Write-Host 'Isolated profile-completion checks passed; this is not a full Android build.'
