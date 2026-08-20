#!/usr/bin/env pwsh
# Build OpenMinis Pet debug APK and copy it to the repo root with a
# versioned filename, e.g. OpenMinis-Pet-1.12-pet.2-arm64-debug.apk.
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts/build-pet-apk.ps1

# 'Continue' on purpose: the local WSL emits a localhost-proxy warning on
# stderr which PowerShell 5.1 would otherwise turn into a terminating
# NativeCommandError. Correctness is enforced via $LASTEXITCODE below.
$ErrorActionPreference = 'Continue'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot  = Split-Path -Parent $ScriptDir
$AndroidDir = Join-Path $RepoRoot 'src\android'

function Get-VersionName {
    $gradle = Join-Path $RepoRoot 'src\android\app\build.gradle.kts'
    $m = Select-String -Path $gradle -Pattern 'versionName\s*=\s*"([^"]+)"'
    if (-not $m) { throw "Cannot find versionName in $gradle" }
    return $m.Matches[0].Groups[1].Value
}

function Invoke-Build {
    $wrapperBat = Join-Path $AndroidDir 'gradlew.bat'
    $wrapperJar = Join-Path $AndroidDir 'gradle\wrapper\gradle-wrapper.jar'
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue

    if (Test-Path $wrapperBat) {
        Push-Location $AndroidDir
        try { & $wrapperBat :app:assembleDebug --console=plain } finally { Pop-Location }
    } elseif ($wsl -and ((wsl.exe -e bash -lc "test -d ~/Android/Sdk && echo ok" 2>&1 | Out-String) -match '(?m)^ok\s*$')) {
        # This fork ships only the POSIX `gradlew`; on the author's machine the
        # Android SDK lives inside WSL. Convert the Windows path to /mnt/c/….
        $linuxPath = ($AndroidDir -replace '\\','/' -replace '^([A-Za-z]):','/mnt/$1').ToLower()
        wsl.exe -e bash -lc "cd '$linuxPath' && export ANDROID_HOME=~/Android/Sdk && ./gradlew :app:assembleDebug --console=plain" 2>&1
    } elseif (Test-Path $wrapperJar) {
        # Last resort: invoke the wrapper jar directly with a native JDK.
        & java -cp $wrapperJar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --console=plain
    } else {
        throw "Gradle wrapper not found under $AndroidDir"
    }
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed with exit code $LASTEXITCODE" }
}

Invoke-Build

$apk = Join-Path $AndroidDir 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw "Build output not found: $apk" }

$version = Get-VersionName
$target = Join-Path $RepoRoot "OpenMinis-Pet-$version-arm64-debug.apk"
Copy-Item -LiteralPath $apk -Destination $target -Force
Write-Host "OK: $target"
