#!/usr/bin/env pwsh
# Build the Minis for Android debug APK.
#
# The APK remains a local Gradle build artifact and is not copied into the
# repository root. See BUILDING.md for the supported build environment.
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts/build-android-debug.ps1

$ErrorActionPreference = 'Stop'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$AndroidDir = Join-Path $RepoRoot 'src\android'

function Invoke-AndroidDebugBuild {
    $wrapperBat = Join-Path $AndroidDir 'gradlew.bat'
    $wrapperJar = Join-Path $AndroidDir 'gradle\wrapper\gradle-wrapper.jar'
    $wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue

    if (Test-Path $wrapperBat) {
        Push-Location $AndroidDir
        try {
            & $wrapperBat :app:assembleDebug --console=plain
        } finally {
            Pop-Location
        }
    } elseif ($wsl) {
        $linuxPath = ($AndroidDir -replace '\\','/' -replace '^([A-Za-z]):','/mnt/$1').ToLower()
        wsl.exe -e bash -lc "cd '$linuxPath' && ./gradlew :app:assembleDebug --console=plain" 2>&1
    } elseif (Test-Path $wrapperJar) {
        Push-Location $AndroidDir
        try {
            & java -cp $wrapperJar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --console=plain
        } finally {
            Pop-Location
        }
    } else {
        throw "Gradle wrapper not found under $AndroidDir"
    }

    if ($LASTEXITCODE -ne 0) {
        throw "assembleDebug failed with exit code $LASTEXITCODE"
    }
}

Invoke-AndroidDebugBuild

$apk = Join-Path $AndroidDir 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) {
    throw "Build output not found: $apk"
}

$gitCommand = Get-Command git.exe -ErrorAction Stop
$gitRoot = Split-Path (Split-Path $gitCommand.Source -Parent) -Parent
$bash = Join-Path $gitRoot 'bin/bash.exe'
if (-not (Test-Path $bash)) {
    throw 'Git Bash is required for the APK native verification.'
}
$verifyScript = (Join-Path $RepoRoot 'scripts/verify-android-16k.sh') -replace '\\', '/'
& $bash $verifyScript ($apk -replace '\\', '/')
if ($LASTEXITCODE -ne 0) {
    throw "APK native verification failed with exit code $LASTEXITCODE"
}
Write-Host "OK: $apk"
