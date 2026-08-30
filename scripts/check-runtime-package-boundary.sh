#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
MAIN='src/android/app/src/main/java/io/github/slackerllc/minis'
TEST='src/android/app/src/test/java/io/github/slackerllc/minis'
ANDROID_TEST='src/android/app/src/androidTest/java/io/github/slackerllc/minis'
RUNTIME="$MAIN/runtime"
LEGACY="$MAIN/sandbox"

fail() {
  echo "runtime package boundary violation: $*" >&2
  exit 54
}

# The active runtime must be discoverable by current responsibilities.
for required in \
  "$RUNTIME/RuntimePathRegistry.kt" \
  "$RUNTIME/ExternalMountCoordinator.kt" \
  "$RUNTIME/ExecutionCoordinator.kt" \
  "$RUNTIME/minisd/MinisdProtocol.kt" \
  "$RUNTIME/ubuntu/UbuntuRuntime.kt" \
  "$RUNTIME/guest/NativeOffload.kt" \
  "$RUNTIME/terminal/TerminalSanitizer.kt"; do
  [[ -f "$required" ]] || fail "missing required active component: $required"
done

# Compatibility shells must never be presented as current runtime components.
for forbidden in \
  "$RUNTIME/RootfsManager.kt" \
  "$RUNTIME/terminal/TerminalSession.kt" \
  "$RUNTIME/MinisKernel.kt" \
  "$RUNTIME/MountedFolderCoordinator.kt"; do
  [[ ! -e "$forbidden" ]] || fail "legacy component entered active runtime: $forbidden"
done

# The deprecated main-source boundary has a deliberately narrow allowlist.
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

# RootfsManager's parser regression test intentionally follows the legacy shell.
if [[ -d "$TEST/sandbox" ]]; then
  while IFS= read -r file; do
    [[ "$file" == "$TEST/sandbox/TarExtractionTest.kt" ]] || \
      fail "unexpected unit test under legacy sandbox package: $file"
  done < <(find "$TEST/sandbox" -type f -print | sort)
fi
[[ ! -d "$ANDROID_TEST/sandbox" ]] || \
  ! find "$ANDROID_TEST/sandbox" -type f -print -quit | grep -q . || \
  fail "instrumentation tests must follow active runtime responsibilities"

# Old active package families/names may not return. Compatibility references are
# restricted to the three exact symbols still owned by the legacy shells.
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

# Mounted-folder ownership must describe SAF -> host -> minisd bind semantics,
# never the removed proot -b interface.
if grep -Eiq 'proot|proot -b' "$RUNTIME/ExternalMountCoordinator.kt"; then
  fail "ExternalMountCoordinator documents removed PRoot semantics"
fi

grep -Fq 'object RuntimePathRegistry' "$RUNTIME/RuntimePathRegistry.kt" || \
  fail 'RuntimePathRegistry declaration missing'
grep -Fq 'fun initialize(context: Context)' "$RUNTIME/RuntimePathRegistry.kt" || \
  fail 'RuntimePathRegistry still exposes legacy boot API'
grep -Fq 'object ExternalMountCoordinator' "$RUNTIME/ExternalMountCoordinator.kt" || \
  fail 'ExternalMountCoordinator declaration missing'

# Package-boundary refactors must not silently mutate the privileged runtime
# wire/path contract owned by minisd and Ubuntu.
protocol="$RUNTIME/minisd/MinisdProtocol.kt"
grep -Fq 'const val PROTOCOL_V = 1' "$protocol" || fail 'minisd protocol version changed'
grep -Fq 'const val DEFAULT_SOCKET = "/data/adb/minis/run/minisd.sock"' "$protocol" || fail 'minisd socket contract changed'
grep -Fq 'const val DEFAULT_BIN = "/data/adb/minis/bin/minisd"' "$protocol" || fail 'minisd binary contract changed'
grep -Fq 'const val DEFAULT_ROOTFS = "/data/adb/minis/rootfs"' "$protocol" || fail 'rootfs contract changed'
grep -Fq 'const val HOST_WORKSPACE = "/data/adb/minis/workspace"' "$protocol" || fail 'workspace contract changed'
grep -Fq 'const val GUEST_WORKSPACE = "/workspace"' "$protocol" || fail 'guest workspace contract changed'

echo 'runtime package boundary contract: OK'
