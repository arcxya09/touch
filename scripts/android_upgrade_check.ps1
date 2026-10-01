<#
.SYNOPSIS
Runs a same-certificate source-fixture upgrade on a clean, isolated Android emulator.
.DESCRIPTION
Supply an unminified seed fixture built from the named baseline source ref, a signed
target APK (which may be R8-minified), and a test APK targeting the release package.
All three must use the same test/release certificate. The fixture and test APK must
be built with TOUCH_API_BASE=http://127.0.0.1:8010; start the disposable API using
server/tests/device_fixture.py first. No Gradle files or version files are edited.

This produces SOURCE-FIXTURE evidence. It does not claim that an arbitrary historic
published APK's schema was tested; that requires a separately recorded historical
artifact run. Never substitute this report for the domestic-device/OEM checklist.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [Parameter(Mandatory)][string]$BaselineFixtureApk,
    [Parameter(Mandatory)][string]$BaselineSourceRef,
    [Parameter(Mandatory)][string]$TargetApk,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$TargetManifest,
    [Parameter(Mandatory)][string]$WrongSignerApk,
    [Parameter(Mandatory)][string]$WrongPackageApk,
    [Parameter(Mandatory)][ValidateRange(1, 2147483647)][long]$ExpectedVersionCode,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$Adb = 'adb',
    [string]$Aapt = 'aapt2',
    [string]$ApkSigner = 'apksigner',
    [ValidateRange(1024, 65535)][int]$FixturePort = 8010,
    [ValidatePattern('^verify_local_[a-zA-Z0-9_]+$')][string]$TestUser = 'verify_local_ci',
    [ValidatePattern('^[A-Za-z0-9._-]{10,128}$')][string]$TestPassword = 'test-only-local-123'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$package = 'com.arcxya09.touch'
foreach ($path in @($BaselineFixtureApk, $TargetApk, $TestApk, $TargetManifest, $WrongSignerApk, $WrongPackageApk)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing artifact: $path" }
}
if ([string]::IsNullOrWhiteSpace($BaselineSourceRef)) { throw 'BaselineSourceRef is required for truthful evidence.' }
$reportDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null

function Invoke-Adb([string[]]$Arguments) {
    $result = @(& $Adb -s $Serial @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed: $($result -join [Environment]::NewLine)" }
    return ($result -join [Environment]::NewLine)
}
function Read-Apk([string]$Path) {
    $badging = @(& $Aapt dump badging $Path 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect APK: $Path" }
    $match = [regex]::Match($badging, "package: name='([^']+)' versionCode='([0-9]+)'")
    if (!$match.Success) { throw "Missing package identity in $Path" }
    $signing = @(& $ApkSigner verify --print-certs $Path 2>&1) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "APK signature validation failed: $Path" }
    $certificates = @([regex]::Matches($signing, 'certificate SHA-256 digest: ([0-9a-fA-F]+)') | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() })
    if ($certificates.Count -ne 1) { throw "Expected exactly one signing certificate in $Path" }
    return @{ package = $match.Groups[1].Value; version = [long]$match.Groups[2].Value; certificate = $certificates[0]; badging = $badging }
}
function Invoke-Probe([string]$Phase, [string]$Class, [string[]]$ExtraArguments) {
    $arguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', "com.arcxya09.touch.$Class", '-e', 'upgradeProbe', $Phase) + $ExtraArguments + @($runner)
    $result = Invoke-Adb $arguments
    $result | Set-Content -LiteralPath (Join-Path $reportDirectory "$Phase.txt") -Encoding utf8
    if ($result -notmatch 'OK \(1 test\)' -or $result -match 'INSTRUMENTATION_STATUS_CODE: -[234]|FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw "Upgrade $Phase did not execute exactly one successful, non-skipped test. See $reportDirectory."
    }
}

$baseline = Read-Apk $BaselineFixtureApk
$target = Read-Apk $TargetApk
$tests = Read-Apk $TestApk
if ($baseline.package -ne $package -or $target.package -ne $package) { throw 'Both app APKs must target the release package com.arcxya09.touch.' }
if ($target.version -ne $ExpectedVersionCode -or $baseline.version -ge $target.version) { throw 'Expected target version must match the APK and exceed the fixture version.' }
if ($baseline.certificate -ne $target.certificate -or $tests.certificate -ne $target.certificate) { throw 'Fixture, target and instrumentation APK certificates must match.' }
$manifestTree = @(& $Aapt dump xmltree --file AndroidManifest.xml $TestApk 2>&1) -join "`n"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the instrumentation manifest with aapt2.' }
$targetPackage = [regex]::Match($manifestTree, 'android:targetPackage[^=]*="([^"]+)"')
if (!$targetPackage.Success -or $targetPackage.Groups[1].Value -ne $package -or $manifestTree -notmatch 'androidx\.test\.runner\.AndroidJUnitRunner') {
    throw 'The instrumentation APK must use AndroidJUnitRunner and explicitly target the release package.'
}
$runner = $tests.package + '/androidx.test.runner.AndroidJUnitRunner'
$manifest = Get-Content -LiteralPath $TargetManifest -Raw | ConvertFrom-Json
if ([long]$manifest.versionCode -ne $ExpectedVersionCode) { throw 'Manifest version does not match ExpectedVersionCode.' }
if ((Invoke-Adb @('get-state')).Trim() -ne 'device') { throw 'The named emulator is not ready.' }
if ((Invoke-Adb @('shell', 'pm', 'list', 'packages', $package)) -match '(?m)^package:com\.arcxya09\.touch\s*$') { throw 'Use a clean dedicated emulator; the script never clears or replaces pre-existing app data.' }

# Disable outbound device networking. adb reverse still serves the loopback fixture.
Invoke-Adb @('shell', 'svc', 'wifi', 'disable') | Out-Null
Invoke-Adb @('shell', 'svc', 'data', 'disable') | Out-Null
$offline = $false
for ($attempt = 0; $attempt -lt 10; $attempt++) {
    $connectivity = Invoke-Adb @('shell', 'dumpsys', 'connectivity')
    if ($connectivity -match 'Active default network:\s*(none|null|0)(\s|$)') { $offline = $true; break }
    Start-Sleep -Seconds 1
}
if (!$offline) { throw 'Cannot prove the emulator has no default network; refusing any account or APK probe.' }
Invoke-Adb @('reverse', "tcp:$FixturePort", "tcp:$FixturePort") | Out-Null
Invoke-Adb @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
Invoke-Adb @('shell', 'settings', 'put', 'system', 'user_rotation', '0') | Out-Null
Invoke-Adb @('install', '-t', $BaselineFixtureApk) | Out-Null
Invoke-Adb @('install', '-t', $TestApk) | Out-Null
Invoke-Probe 'seed' 'UpgradeSeedTest' @('-e', 'fixtureBase', "http://127.0.0.1:$FixturePort", '-e', 'touchTestUser', $TestUser, '-e', 'touchTestPassword', $TestPassword)
$external = "/sdcard/Android/data/$package/files"
foreach ($asset in @(@($TargetApk, 'touch.apk'), @($TargetManifest, 'update.json'), @($WrongSignerApk, 'wrong-signer.apk'), @($WrongPackageApk, 'wrong-package.apk'))) {
    Invoke-Adb @('push', $asset[0], "$external/$($asset[1])") | Out-Null
}
Invoke-Probe 'packages' 'UpgradePackageTest' @('-e', 'expectedVersionCode', "$ExpectedVersionCode")
Invoke-Adb @('shell', 'am', 'force-stop', $package) | Out-Null
Invoke-Adb @('install', '-r', $TargetApk) | Out-Null
Invoke-Probe 'verify' 'UpgradeVerifyTest' @('-e', 'expectedVersionCode', "$ExpectedVersionCode")
$proof = [ordered]@{
    schema = 1; kind = 'signed-source-fixture-upgrade'; baselineSourceRef = $BaselineSourceRef
    verifiedAtUtc = [DateTime]::UtcNow.ToString('o'); serial = $Serial
    baselineVersionCode = $baseline.version; targetVersionCode = $target.version
    baselineSha256 = (Get-FileHash -LiteralPath $BaselineFixtureApk -Algorithm SHA256).Hash.ToLowerInvariant()
    targetSha256 = (Get-FileHash -LiteralPath $TargetApk -Algorithm SHA256).Hash.ToLowerInvariant()
    testApkSha256 = (Get-FileHash -LiteralPath $TestApk -Algorithm SHA256).Hash.ToLowerInvariant()
    certificateSha256 = $target.certificate; probesPassed = @('seed', 'packages', 'verify')
    productionAccountsUsed = $false; historicalPublishedApkVerified = $false; domesticOemVerified = $false
}
$proof | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $reportDirectory 'upgrade-proof.json') -Encoding utf8
Write-Output "Three non-skipped upgrade probes passed. Source-fixture evidence saved to $reportDirectory."
