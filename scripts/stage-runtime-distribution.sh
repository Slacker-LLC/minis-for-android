#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: $0 MINISD ROOTFS_TAR [ADB]" >&2
  exit 64
fi

MINISD="$1"
ROOTFS_TAR="$2"
ADB="${3:-${ADB:-adb}}"
STAGING="/data/local/tmp/minis-runtime"

[[ -s "$MINISD" ]] || { echo "minisd missing or empty: $MINISD" >&2; exit 65; }
[[ -x "$MINISD" ]] || { echo "minisd is not executable: $MINISD" >&2; exit 66; }
[[ -s "$ROOTFS_TAR" ]] || { echo "rootfs archive missing or empty: $ROOTFS_TAR" >&2; exit 67; }
command -v "$ADB" >/dev/null 2>&1 || [[ -x "$ADB" ]] || { echo "adb not found: $ADB" >&2; exit 68; }

MINISD_SHA="$(sha256sum "$MINISD" | awk '{print $1}')"
ROOTFS_SHA="$(sha256sum "$ROOTFS_TAR" | awk '{print $1}')"

"$ADB" wait-for-device
"$ADB" shell "rm -rf '$STAGING' && mkdir -p '$STAGING' && chmod 0700 '$STAGING'"
"$ADB" push "$MINISD" "$STAGING/minisd-arm64"
"$ADB" push "$ROOTFS_TAR" "$STAGING/ubuntu-arm64-rootfs.tar.gz"
"$ADB" shell "chmod 0700 '$STAGING/minisd-arm64' && chmod 0600 '$STAGING/ubuntu-arm64-rootfs.tar.gz'"

DEVICE_MINISD_SHA="$("$ADB" shell "sha256sum '$STAGING/minisd-arm64' | awk '{print \\$1}'" | tr -d '\r' | awk '{print $1}')"
DEVICE_ROOTFS_SHA="$("$ADB" shell "sha256sum '$STAGING/ubuntu-arm64-rootfs.tar.gz' | awk '{print \\$1}'" | tr -d '\r' | awk '{print $1}')"

[[ "$DEVICE_MINISD_SHA" == "$MINISD_SHA" ]] || { echo "device minisd staging digest mismatch" >&2; exit 69; }
[[ "$DEVICE_ROOTFS_SHA" == "$ROOTFS_SHA" ]] || { echo "device rootfs staging digest mismatch" >&2; exit 70; }

echo "staged only; live /data/adb/minis runtime was not modified"
echo "  minisd sha256: $MINISD_SHA"
echo "  rootfs sha256: $ROOTFS_SHA"
