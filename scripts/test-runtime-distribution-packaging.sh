#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PREPARE="$ROOT/scripts/prepare-runtime-distribution.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

MINISD="$WORK/minisd"
ROOTFS="$WORK/rootfs.tar.gz"
ROOTFS_META="$WORK/rootfs.manifest.json"
OUTPUT="$WORK/runtime-distribution.json"

printf '#!/bin/sh\necho minisd-test\n' > "$MINISD"
chmod 0755 "$MINISD"
printf 'rootfs-fixture\n' > "$ROOTFS"
ROOTFS_SHA="$(sha256sum "$ROOTFS" | awk '{print $1}')"
UPSTREAM_SHA="$(printf 'upstream-fixture' | sha256sum | awk '{print $1}')"
cat > "$ROOTFS_META" <<EOF
{
  "file": "ubuntu-arm64-rootfs.tar.gz",
  "sha256": "$ROOTFS_SHA",
  "ubuntu": "24.04.3",
  "release": "24.04",
  "arch": "arm64",
  "profile": "base",
  "upstream_sha256": "$UPSTREAM_SHA"
}
EOF

bash "$PREPARE" "$MINISD" "$ROOTFS" "$ROOTFS_META" "$OUTPUT" "2026.08.29.test"
python3 - "$OUTPUT" "$MINISD" "$ROOTFS" <<'PY'
import hashlib
import json
import sys

path, minisd_path, rootfs_path = sys.argv[1:]
with open(path, "r", encoding="utf-8") as fh:
    obj = json.load(fh)

def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        h.update(fh.read())
    return h.hexdigest()

assert obj["distributionReady"] is True
assert obj["protocolVersion"] == 1
assert obj["layoutVersion"] == 2
assert obj["abi"] == "arm64"
assert obj["minisd"]["sha256"] == sha(minisd_path)
assert obj["rootfs"]["sha256"] == sha(rootfs_path)
assert obj["requiredCommands"] == ["python3", "git", "curl"]
PY

# A sidecar digest mismatch must fail before a trusted APK manifest can be emitted.
printf 'tampered\n' >> "$ROOTFS"
if bash "$PREPARE" "$MINISD" "$ROOTFS" "$ROOTFS_META" "$WORK/should-not-exist.json" "2026.08.29.test"; then
  echo "prepare-runtime-distribution unexpectedly accepted a tampered rootfs" >&2
  exit 1
fi

# A non-executable broker artifact must not be accepted as a release input.
chmod 0644 "$MINISD"
if bash "$PREPARE" "$MINISD" "$ROOTFS" "$ROOTFS_META" "$WORK/should-not-exist-2.json" "2026.08.29.test"; then
  echo "prepare-runtime-distribution unexpectedly accepted non-executable minisd" >&2
  exit 1
fi

echo "runtime distribution packaging fail-closed tests passed"
