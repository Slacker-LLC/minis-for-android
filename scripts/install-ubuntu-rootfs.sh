#!/usr/bin/env bash
# Push dist/ubuntu-arm64-rootfs.tar.gz to the phone and extract into
# /data/adb/minis/rootfs. Does not start ubuntu (call ubuntu.start via minisd).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAR="${1:-$ROOT/dist/ubuntu-arm64-rootfs.tar.gz}"
ADB="${ADB:-}"

if [[ -z "$ADB" ]]; then
  for c in \
    "/mnt/d/刷机与开发工具/bin/adb.exe" \
    "/d/刷机与开发工具/bin/adb.exe" \
    "D:/刷机与开发工具/bin/adb.exe" \
    adb
  do
    if [[ -x "$c" ]] || command -v "$c" >/dev/null 2>&1; then
      ADB="$c"
      break
    fi
  done
fi
[[ -n "$ADB" ]] || { echo "adb not found; set ADB=" >&2; exit 1; }
[[ -f "$TAR" ]] || { echo "missing $TAR — run scripts/build-ubuntu-rootfs.sh first" >&2; exit 1; }

echo "==> adb=$ADB"
"$ADB" wait-for-device
echo "==> push $(basename "$TAR")"
"$ADB" push "$TAR" /data/local/tmp/ubuntu-arm64-rootfs.tar.gz
echo "==> extract as root"
"$ADB" shell "su -c 'mkdir -p /data/adb/minis/rootfs /data/adb/minis/workspace /data/adb/minis/memory /data/adb/minis/skills /data/adb/minis/shared && rm -rf /data/adb/minis/rootfs/* /data/adb/minis/rootfs/.[!.]* 2>/dev/null; tar -xzf /data/local/tmp/ubuntu-arm64-rootfs.tar.gz -C /data/adb/minis/rootfs && test -f /data/adb/minis/rootfs/etc/os-release && cat /data/adb/minis/rootfs/etc/os-release | head -5 && ls /data/adb/minis/rootfs/usr/bin/bash /data/adb/minis/rootfs/etc/minis/rootfs.json'"
echo "==> done"
