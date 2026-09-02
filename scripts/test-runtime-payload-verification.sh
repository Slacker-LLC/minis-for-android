#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERIFY="$ROOT/scripts/verify-runtime-payload.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

make_fixture() {
  local dir="$1"
  mkdir -p "$dir"
  python3 - "$dir" <<'PY'
import hashlib
import io
import json
import pathlib
import sys
import tarfile

root = pathlib.Path(sys.argv[1])
elf = bytearray(64)
elf[:4] = b"\x7fELF"
elf[4] = 2
elf[5] = 1
elf[16:18] = (3).to_bytes(2, "little")
elf[18:20] = (183).to_bytes(2, "little")
(root / "minisd-arm64-v8a").write_bytes(elf)
minisd = (root / "minisd-arm64-v8a").read_bytes()
rootfs_metadata = json.dumps({
    "distro": "ubuntu",
    "version": "24.04",
    "release": "24.04.3",
    "arch": "arm64",
    "profile": "base",
    "revision": 1,
    "upstream_sha256": "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048",
}).encode()
with tarfile.open(root / "ubuntu-arm64-rootfs.tar.gz", "w:gz") as tar:
    for name in (
        "workspace", "memory", "skills", "shared", "proc", "sys", "dev", "tmp", "run", "var/minis",
    ):
        info = tarfile.TarInfo(name)
        info.type = tarfile.DIRTYPE
        tar.addfile(info)
    for name, content in {
        "etc/os-release": b'VERSION_ID="24.04"\n',
        "etc/passwd": b"root:x:0:0:root:/root:/bin/bash\n",
        "etc/group": b"root:x:0:\n",
        "etc/minis/rootfs.json": rootfs_metadata,
        "bin/bash": b"#!/bin/sh\n",
    }.items():
        info = tarfile.TarInfo(name)
        info.size = len(content)
        info.mode = 0o755 if name == "bin/bash" else 0o644
        tar.addfile(info, io.BytesIO(content))
rootfs = (root / "ubuntu-arm64-rootfs.tar.gz").read_bytes()
rootfs_sha = hashlib.sha256(rootfs).hexdigest()
manifest = {
    "schemaVersion": 2,
    "minisdVersion": "0.1.0",
    "minisdSha256": hashlib.sha256(minisd).hexdigest(),
    "protocolVersion": 1,
    "layoutVersion": 2,
    "abi": "arm64-v8a",
    "rootfsVersion": f"ubuntu-24.04-r1-{rootfs_sha[:16]}",
    "rootfsSha256": rootfs_sha,
    "rootfsRelease": "24.04.3",
    "rootfsProfile": "base",
    "rootfsUpstreamSha256": "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048",
    "provisionRevision": 1,
    "requiredCommands": ["python3", "git", "curl"],
}
(root / "runtime-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
PY
}

expect_failure() {
  local name="$1"
  local dir="$TMP/$name"
  shift
  make_fixture "$dir"
  "$@" "$dir"
  if "$VERIFY" "$dir" >"$TMP/$name.log" 2>&1; then
    echo "FAIL: $name unexpectedly succeeded" >&2
    exit 1
  fi
  echo "PASS: $name"
}

make_fixture "$TMP/valid"
"$VERIFY" "$TMP/valid"

expect_failure tampered_minisd sh -c 'printf tampered >> "$1/minisd-arm64-v8a"' _
expect_failure missing_rootfs sh -c 'rm "$1/ubuntu-arm64-rootfs.tar.gz"' _
expect_failure invalid_manifest sh -c 'printf "{}" > "$1/runtime-manifest.json"' _

echo "All runtime payload verification tests passed."
