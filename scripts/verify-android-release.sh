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

AAPT2="$(find "$ANDROID_SDK_ROOT/build-tools" -type f -name aapt2 -print | sort -V | tail -n1)"
if [[ -z "$AAPT2" || ! -x "$AAPT2" ]]; then
  echo "aapt2 not found" >&2
  exit 2
fi

APK_PACKAGE="$($AAPT2 dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -n1)"
if [[ "$APK_PACKAGE" != "io.github.slackerllc.minis" ]]; then
  echo "release APK package mismatch: got '${APK_PACKAGE:-unknown}', expected 'io.github.slackerllc.minis'" >&2
  exit 1
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

while IFS= read -r dex; do
  if unzip -p "$APK" "$dex" | strings | grep -Fq 'io/github/slackerllc/minis/debug/DebugServer'; then
    echo "release APK still contains DebugServer in $dex" >&2
    exit 1
  fi
done < <(unzip -Z1 "$APK" | grep -E '^classes([0-9]+)?\.dex$')

python3 - "$APK" <<'PY'
import hashlib
import json
import re
import sys
import zipfile

apk = sys.argv[1]
lib = 'lib/arm64-v8a/libminisd.so'
rootfs = 'assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz'
manifest_path = 'assets/minis-runtime/runtime-manifest.json'

def digest_stream(stream):
    h = hashlib.sha256()
    while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
            return h.hexdigest()
        h.update(chunk)

with zipfile.ZipFile(apk) as z:
    names = set(z.namelist())
    for required in (lib, rootfs, manifest_path):
        if required not in names:
            raise SystemExit(f'release APK missing runtime payload: {required}')
    if 'assets/runtime-distribution.json' in names:
        raise SystemExit('obsolete runtime-distribution.json returned')
    manifest_raw = z.read(manifest_path).decode('utf-8')
    manifest = json.loads(manifest_raw)
    with z.open(lib) as f:
        minisd_sha = digest_stream(f)
    with z.open(rootfs) as f:
        rootfs_sha = digest_stream(f)

assert manifest['schemaVersion'] == 2
assert manifest['protocolVersion'] == 1
assert manifest['layoutVersion'] == 2
assert manifest['abi'] == 'arm64-v8a'
assert manifest['minisdSha256'] == minisd_sha
assert manifest['rootfsSha256'] == rootfs_sha
assert re.fullmatch(r'ubuntu-24\.04-r[1-9][0-9]*-[0-9a-f]{16}', manifest['rootfsVersion'])
assert manifest['rootfsVersion'].endswith(rootfs_sha[:16])
assert manifest['rootfsRelease'].startswith('24.04')
assert manifest['rootfsProfile'] == 'base'
assert re.fullmatch(r'[0-9a-f]{64}', manifest['rootfsUpstreamSha256'])
assert isinstance(manifest['provisionRevision'], int) and manifest['provisionRevision'] > 0
assert manifest['requiredCommands'] == ['python3', 'git', 'curl']
for forbidden in (
    'managed', 'external_staged', '/data/local/tmp/minis-runtime',
    '/data/adb/minis/bin/minisd', '/data/adb/minis/run/minisd.sock',
    '/data/adb/minis/run/minisd.pid', '/data/adb/minis/policy/policy.json',
):
    if forbidden in manifest_raw:
        raise SystemExit(f'obsolete runtime contract in release manifest: {forbidden}')
PY

echo "release APK verification passed for $APK_PACKAGE"
