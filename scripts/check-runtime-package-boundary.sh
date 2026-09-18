#!/usr/bin/env bash
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
MAIN='src/android/app/src/main/java/com/openminis/app'
TEST='src/android/app/src/test/java/com/openminis/app'
ANDROID_TEST='src/android/app/src/androidTest/java/com/openminis/app'
RUNTIME="$MAIN/runtime"
LEGACY="$MAIN/sandbox"

fail() { echo "runtime package boundary violation: $*" >&2; exit 54; }

for required in \
  "$RUNTIME/RuntimePathRegistry.kt" \
  "$RUNTIME/ExecutionCoordinator.kt" \
  "$RUNTIME/ubuntu/UbuntuRuntime.kt" \
  "$RUNTIME/ubuntu/UbuntuKernel.kt" \
  "$RUNTIME/ubuntu/DirectRootRunner.kt" \
  "$RUNTIME/ubuntu/UbuntuPaths.kt" \
  "$RUNTIME/ubuntu/RootNetworkProxy.kt" \
  "$RUNTIME/guest/GuestCommandBridge.kt" \
  "$RUNTIME/guest/NativeOffload.kt" \
  "$RUNTIME/terminal/TerminalSanitizer.kt"; do
  [[ -f "$required" ]] || fail "missing required active component: $required"
done

for forbidden in \
  "$RUNTIME/minisd" \
  "$RUNTIME/RootfsManager.kt" \
  "$RUNTIME/terminal/TerminalSession.kt" \
  "$RUNTIME/MinisKernel.kt" \
  "$RUNTIME/MountedFolderCoordinator.kt"; do
  [[ ! -e "$forbidden" ]] || fail "obsolete component entered active runtime: $forbidden"
done

for symbol in MinisdClient MinisdProtocol MinisdBootstrap MinisdConfigBridgeServer; do
  hits="$(git grep -n -w "$symbol" -- src/android/app/src 2>/dev/null || true)"
  [[ -z "$hits" ]] || { printf '%s\n' "$hits" >&2; fail "obsolete broker type '$symbol' returned"; }
done
hits="$(git grep -n -E 'runtime[./]minisd|runtime\.minisd|minisd\.sock|/data/adb/minis/bin/minisd' -- src/android/app/src/main src/android/app/src/test 2>/dev/null || true)"
[[ -z "$hits" ]] || { printf '%s\n' "$hits" >&2; fail "obsolete broker runtime contract returned"; }

allowed_legacy_main=("$LEGACY/RootfsManager.kt" "$LEGACY/TerminalSession.kt")
if [[ -d "$LEGACY" ]]; then
  while IFS= read -r file; do
    allowed=false
    for expected in "${allowed_legacy_main[@]}"; do [[ "$file" == "$expected" ]] && allowed=true; done
    [[ "$allowed" == true ]] || fail "unexpected active source under legacy sandbox package: $file"
  done < <(find "$LEGACY" -type f -print | sort)
fi
if [[ -d "$TEST/sandbox" ]]; then
  while IFS= read -r file; do
    [[ "$file" == "$TEST/sandbox/TarExtractionTest.kt" ]] || fail "unexpected unit test under legacy sandbox package: $file"
  done < <(find "$TEST/sandbox" -type f -print | sort)
fi
[[ ! -d "$ANDROID_TEST/sandbox" ]] || ! find "$ANDROID_TEST/sandbox" -type f -print -quit | grep -q . || fail "instrumentation tests must follow active runtime responsibilities"

old_refs="$(git grep -n -E 'com\.openminis\.app\.sandbox\.' -- src/android 2>/dev/null || true)"
if [[ -n "$old_refs" ]]; then
  unexpected="$(printf '%s\n' "$old_refs" | grep -Ev 'com\.openminis\.app\.sandbox\.(RootfsManager|RootfsInstallState|TerminalSession)([^A-Za-z0-9_]|$)' || true)"
  [[ -z "$unexpected" ]] || { printf '%s\n' "$unexpected" >&2; fail "unexpected legacy sandbox FQN"; }
fi
for legacy_name in MinisKernel MountedFolderCoordinator; do
  hits="$(git grep -n -w "$legacy_name" -- src/android/app/src 2>/dev/null || true)"
  [[ -z "$hits" ]] || { printf '%s\n' "$hits" >&2; fail "legacy runtime type name '$legacy_name' returned"; }
done

grep -Fq 'object RuntimePathRegistry' "$RUNTIME/RuntimePathRegistry.kt" || fail 'RuntimePathRegistry declaration missing'
grep -Fq 'fun registerGlobalBindMounts' "$RUNTIME/RuntimePathRegistry.kt" || fail 'global bind registry missing'
grep -Fq 'var mountedFoldersStore' "$RUNTIME/RuntimePathRegistry.kt" || fail 'mounted-folder registry missing'
grep -Fq 'fun isLinuxPathUnderReadOnlyMount' "$RUNTIME/RuntimePathRegistry.kt" || fail 'mounted-folder write boundary missing'
grep -Fq 'object UbuntuKernel' "$RUNTIME/ubuntu/UbuntuKernel.kt" || fail 'UbuntuKernel declaration missing'
grep -Fq 'object RootNetworkProxy' "$RUNTIME/ubuntu/RootNetworkProxy.kt" || fail 'Root network proxy boundary missing'
grep -Fq 'const val PROXY_LISTEN = "127.0.0.1:18787"' "$RUNTIME/ubuntu/RootNetworkProxy.kt" || fail 'Root network proxy loopback contract changed'
grep -Fq 'const val HOST_MINIS = "/data/adb/minis"' "$RUNTIME/ubuntu/UbuntuPaths.kt" || fail 'legacy/root runtime root changed'
grep -Fq 'const val HOST_ROOTFS = "$HOST_MINIS/rootfs"' "$RUNTIME/ubuntu/UbuntuPaths.kt" || fail 'rootfs contract changed'
grep -Fq 'const val LEGACY_WORKSPACE = "$HOST_MINIS/workspace"' "$RUNTIME/ubuntu/UbuntuPaths.kt" || fail 'legacy workspace migration contract changed'
grep -Fq 'Bind(workspace.absolutePath, "/workspace")' "$RUNTIME/ubuntu/UbuntuKernel.kt" || fail 'guest workspace bind contract changed'
grep -Eq -- '--reuid=\$uid --regid=\$gid --clear-groups' "$RUNTIME/ubuntu/UbuntuKernel.kt" || fail 'guest App UID/GID drop missing'
grep -Fq -- '--inh-caps=-all --ambient-caps=-all --bounding-set=-all' "$RUNTIME/ubuntu/UbuntuKernel.kt" || fail 'guest capability drop missing'
if grep -Eiq 'proot|proot -b|broker-owned' "$RUNTIME/RuntimePathRegistry.kt"; then fail "RuntimePathRegistry documents removed runtime semantics"; fi
if grep -Eiq 'minisd\.sock|/data/adb/minis/bin/minisd' "$RUNTIME/RuntimePathRegistry.kt"; then fail "RuntimePathRegistry contains obsolete broker paths"; fi

echo 'runtime package boundary contract: OK'
