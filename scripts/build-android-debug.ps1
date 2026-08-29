$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$androidDir = Join-Path $repoRoot "src/android"
$gradlew = Join-Path $androidDir "gradlew.bat"
$apk = Join-Path $androidDir "app/build/outputs/apk/debug/app-debug.apk"

if (-not (Test-Path $gradlew)) {
  throw "Gradle wrapper not found at $gradlew"
}

Push-Location $androidDir
try {
  & $gradlew ":app:assembleDebug" "--no-parallel" "--max-workers=2" "--stacktrace"
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle debug build failed with exit code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}

if (-not (Test-Path $apk)) {
  throw "Expected debug APK was not produced at $apk"
}

Write-Host "Built APK: $apk"
