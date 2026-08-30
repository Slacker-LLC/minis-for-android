#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
MAIN='src/android/app/src/main/java/io/github/slackerllc/minis'
TEST='src/android/app/src/test/java/io/github/slackerllc/minis'
ANDROID_TEST='src/android/app/src/androidTest/java/io/github/slackerllc/minis'
RUNTIME="$MAIN/runtime"
LEGACY="$MAIN/sandbox"
GRADLE='src/android/app/build.gradle.kts'
NATIVE_MAIN='src/native/minisd/src/main.rs'

fail() {
  echo "runtime package boundary violation: $*" >&2
  exit 54
}

for required in \
  "$RUNTIME/RuntimePathRegistry.kt" \
  "$RUNTIME/ExternalMountCoordinator.kt" \
  "$RUNTIME/ExecutionCoordinator.kt" \
  "$RUNTIME/minisd/MinisdProtocol.kt" \
  "$RUNTIME/minisd/MinisdBootstrap.kt" \
  "$RUNTIME/distribution/RuntimeDistributionManifest.kt" \
  "$RUNTIME/distribution/RuntimeDistributionManager.kt" \
  "$RUNTIME/ubuntu/UbuntuRuntime.kt" \
  "$RUNTIME/ubuntu/UbuntuPaths.kt" \
  "$RUNTIME/ubuntu/AppPersistentPaths.kt" \
  "$RUNTIME/guest/NativeOffload.kt" \
  "$RUNTIME/terminal/TerminalSanitizer.kt"; do
  [[ -f "$required" ]] || fail "missing required active component: $required"
done

for forbidden in \
  "$RUNTIME/RootfsManager.kt" \
  "$RUNTIME/terminal/TerminalSession.kt" \
  "$RUNTIME/MinisKernel.kt" \
  "$RUNTIME/MountedFolderCoordinator.kt"; do
  [[ ! -e "$forbidden" ]] || fail "legacy component entered active runtime: $forbidden"
done

allowed_legacy_main=(
  "$LEGACY/RootfsManager.kt"
  "$LEGACY/TerminalSession.kt"
)
if [[ -d "$LEGACY" ]]; then
  while IFS= read -r file; do
    allowed=false
    for expected in "${allowed_legacy_main[@]}"; do
      [[ "$file" == "$expected" ]] && allowed=true
    done
    [[ "$allowed" == true ]] || fail "unexpected active source under legacy sandbox package: $file"
  done < <(find "$LEGACY" -type f -print | sort)
fi

if [[ -d "$TEST/sandbox" ]]; then
  while IFS= read -r file; do
    [[ "$file" == "$TEST/sandbox/TarExtractionTest.kt" ]] || \
      fail "unexpected unit test under legacy sandbox package: $file"
  done < <(find "$TEST/sandbox" -type f -print | sort)
fi
[[ ! -d "$ANDROID_TEST/sandbox" ]] || \
  ! find "$ANDROID_TEST/sandbox" -type f -print -quit | grep -q . || \
  fail "instrumentation tests must follow active runtime responsibilities"

old_refs="$(git grep -n -E 'io\.github\.slackerllc\.minis\.sandbox\.' -- src/android 2>/dev/null || true)"
if [[ -n "$old_refs" ]]; then
  unexpected="$(printf '%s\n' "$old_refs" | grep -Ev 'io\.github\.slackerllc\.minis\.sandbox\.(RootfsManager|RootfsInstallState|TerminalSession)([^A-Za-z0-9_]|$)' || true)"
  [[ -z "$unexpected" ]] || {
    printf '%s\n' "$unexpected" >&2
    fail "unexpected legacy sandbox FQN"
  }
fi

for legacy_name in MinisKernel MountedFolderCoordinator; do
  hits="$(git grep -n -w "$legacy_name" -- src/android/app/src 2>/dev/null || true)"
  [[ -z "$hits" ]] || {
    printf '%s\n' "$hits" >&2
    fail "legacy runtime type name '$legacy_name' returned"
  }
done

if grep -Eiq 'proot|proot -b' "$RUNTIME/ExternalMountCoordinator.kt"; then
  fail "ExternalMountCoordinator documents removed PRoot semantics"
fi

grep -Fq 'object RuntimePathRegistry' "$RUNTIME/RuntimePathRegistry.kt" || fail 'RuntimePathRegistry declaration missing'
grep -Fq 'fun initialize(context: Context)' "$RUNTIME/RuntimePathRegistry.kt" || fail 'RuntimePathRegistry still exposes legacy boot API'
grep -Fq 'object ExternalMountCoordinator' "$RUNTIME/ExternalMountCoordinator.kt" || fail 'ExternalMountCoordinator declaration missing'

protocol="$RUNTIME/minisd/MinisdProtocol.kt"
grep -Fq 'const val PROTOCOL_V = 1' "$protocol" || fail 'minisd protocol version changed'
grep -Fq 'const val DEFAULT_BIN = "libminisd.so"' "$protocol" || fail 'minisd native-library contract changed'
grep -Fq 'const val HOST_WORKSPACE = "/data/adb/minis/workspace"' "$protocol" || fail 'workspace contract changed'
grep -Fq 'const val GUEST_WORKSPACE = "/workspace"' "$protocol" || fail 'guest workspace contract changed'

bootstrap="$RUNTIME/minisd/MinisdBootstrap.kt"
grep -Fq 'const val MINISD_NATIVE_NAME = "libminisd.so"' "$bootstrap" || fail 'APK minisd name missing'
grep -Fq 'context.applicationInfo.nativeLibraryDir' "$bootstrap" || fail 'nativeLibraryDir lookup missing'
grep -Fq -- '--policy-json' "$bootstrap" || fail 'in-memory policy bootstrap missing'
# Android production owns the lease supervisor itself. Verify the UID/PID/starttime
# shell lease rather than requiring Rust standalone --watchdog CLI arguments.
grep -Fq 'commands += "LEASE_PID=$leasePid"' "$bootstrap" || fail 'app lease pid missing'
grep -Fq 'commands += "LEASE_STARTTIME=$leaseStartTime"' "$bootstrap" || fail 'app lease starttime missing'
grep -Fq '/proc/\$LEASE_PID/status' "$bootstrap" || fail 'app lease UID check missing'
grep -Fq '/proc/\$LEASE_PID/stat' "$bootstrap" || fail 'app lease proc starttime check missing'
grep -Fq 'lease_start=\$(awk' "$bootstrap" || fail 'app lease starttime comparison missing'
grep -Fq 'if ! lease_ok; then kill' "$bootstrap" || fail 'app lease child termination missing'
if grep -Fq -- '--watchdog --socket' "$bootstrap"; then fail 'Android production returned to Rust compatibility watchdog'; fi
grep -Fq 'startsWith('"'"'@'"'"')' "$bootstrap" || fail 'abstract socket guard missing'
grep -Fq 'PERSISTENT_MIGRATION_MARKER' "$bootstrap" || fail '#50 migration marker missing'

manifest="$RUNTIME/distribution/RuntimeDistributionManifest.kt"
grep -Fq 'CURRENT_SCHEMA_VERSION = 2' "$manifest" || fail 'runtime manifest schema must be v2'
grep -Fq 'CURRENT_LAYOUT_VERSION = 2' "$manifest" || fail 'runtime layout must be v2'
grep -Fq 'SUPPORTED_ABI = "arm64-v8a"' "$manifest" || fail 'runtime ABI contract missing'
grep -Fq 'ROOTFS_ASSET_PATH = "minis-runtime/ubuntu-arm64-rootfs.tar.gz"' "$manifest" || fail 'APK rootfs asset contract missing'

for path in \
  /data/adb/minis/workspace \
  /data/adb/minis/sessions \
  /data/adb/minis/memory \
  /data/adb/minis/skills \
  /data/adb/minis/shared \
  /data/adb/minis/home; do
  git grep -Fq "$path" -- "$RUNTIME" "$LEGACY/RootfsManager.kt" || fail "persistent path contract missing: $path"
done

# Active Android/Gradle production code must never return to an externally
# staged or filesystem-backed broker. Native filesystem socket/policy support is
# tolerated only as standalone/dev compatibility and is separately guarded.
active_hits="$(git grep -n -E 'external_staged|/data/local/tmp/minis-runtime|/data/adb/minis/bin/minisd|/data/adb/minis/run/minisd\.(sock|pid)|/data/adb/minis/policy/policy\.json' -- \
  src/android/app/src/main "$GRADLE" 2>/dev/null || true)"
[[ -z "$active_hits" ]] || {
  printf '%s\n' "$active_hits" >&2
  fail 'obsolete external or filesystem broker contract returned to Android production'
}
[[ ! -e src/android/app/src/main/assets/runtime-distribution.json ]] || \
  fail 'obsolete runtime-distribution.json asset returned'

# Gradle must package one authoritative runtime identity and force native-lib
# extraction so ApplicationInfo.nativeLibraryDir/libminisd.so is an executable
# Package Manager-owned path on device.
grep -Fq 'useLegacyPackaging = true' "$GRADLE" || fail 'nativeLibraryDir extraction contract missing'
grep -Fq '"schemaVersion" to 2' "$GRADLE" || fail 'Gradle schema-v2 manifest generator missing'
grep -Fq '"layoutVersion" to 2' "$GRADLE" || fail 'Gradle layout-v2 manifest generator missing'
grep -Fq '"requiredCommands" to commands' "$GRADLE" || fail 'Gradle requiredCommands manifest contract missing'
grep -Fq 'ubuntu-arm64-rootfs.tar.gz' "$GRADLE" || fail 'Gradle packaged rootfs asset missing'
grep -Fq 'dependsOn(packageMinisdNative, buildPinnedUbuntuRootfs)' "$GRADLE" || fail 'runtime assets are not ordered after both runtime producers'
if grep -Fq '"managed"' "$GRADLE"; then fail 'Gradle runtime manifest still contains managed placeholder'; fi

# Standalone/dev-only compatibility in native minisd may retain filesystem Unix
# sockets and --policy PATH, but it must carry an explicit runtime guard. The
# Android production bootstrap above never sets this flag.
grep -Fq -- '--dev-filesystem-ipc' "$NATIVE_MAIN" || \
  fail 'native filesystem IPC compatibility lacks explicit --dev-filesystem-ipc guard'

echo 'runtime package boundary contract: OK'
