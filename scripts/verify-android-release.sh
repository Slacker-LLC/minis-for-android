#!/usr/bin/env bash
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "usage: $0 path/to/app-release.apk" >&2
  exit 2
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME is required" >&2
  exit 2
fi

APKSIGNER="$(find "$ANDROID_SDK_ROOT/build-tools" -type f -name apksigner -print | sort -V | tail -n1)"
if [[ -z "$APKSIGNER" || ! -x "$APKSIGNER" ]]; then
  echo "apksigner not found" >&2
  exit 2
fi

CERT="$($APKSIGNER verify --print-certs "$APK")"
printf '%s\n' "$CERT"
if grep -Fq 'CN=Android Debug' <<<"$CERT"; then
  echo "release APK is signed with an Android debug certificate" >&2
  exit 1
fi

if unzip -Z1 "$APK" | grep -Eq '(^|/)debug-skill(/|$)'; then
  echo "release APK contains debug-server skill assets" >&2
  exit 1
fi

# Issue #43: production builds must carry the complete offline recovery payload.
# A build that omits any of these files cannot satisfy the runtime self-heal
# contract and must fail before it can be distributed.
APK_ENTRIES="$(unzip -Z1 "$APK")"
for required in \
  assets/runtime/minisd-aarch64 \
  assets/runtime/minisd-aarch64.sha256 \
  assets/runtime/ubuntu-arm64-rootfs.tar.gz \
  assets/runtime/ubuntu-arm64-rootfs.tar.gz.sha256 \
  assets/runtime/ubuntu-arm64-rootfs.manifest.json
do
  if ! grep -Fxq "$required" <<<"$APK_ENTRIES"; then
    echo "release APK missing runtime recovery asset: $required" >&2
    exit 1
  fi
done

# R8 should eliminate the debug RPC server because every startup/reference is
# guarded by BuildConfig.DEBUG=false in release. Scan every DEX for the source
# descriptor/string as a regression backstop.
while IFS= read -r dex; do
  if unzip -p "$APK" "$dex" | strings | grep -Fq 'com/openminis/app/debug/DebugServer'; then
    echo "release APK still contains DebugServer in $dex" >&2
    exit 1
  fi
done < <(unzip -Z1 "$APK" | grep -E '^classes([0-9]+)?\.dex$')

echo "release APK verification passed"
