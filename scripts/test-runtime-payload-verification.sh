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
import hashlib, io, json, pathlib, sys, tarfile
root = pathlib.Path(sys.argv[1])
metadata = {
    "distro": "ubuntu", "version": "24.04", "release": "24.04.3", "arch": "arm64",
    "profile": "base", "revision": 1,
    "upstream_sha256": "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048",
}
with tarfile.open(root / "ubuntu-arm64-rootfs.tar.gz", "w:gz") as tar:
    for name in ("etc", "etc/minis", "workspace", "memory", "skills", "shared", "proc", "sys", "dev", "tmp", "run", "var", "var/minis"):
        info = tarfile.TarInfo(name); info.type = tarfile.DIRTYPE; tar.addfile(info)
    for name, content in {
        "etc/os-release": b'VERSION_ID="24.04"\n', "etc/passwd": b"root:x:0:0:root:/root:/bin/bash\n",
        "etc/group": b"root:x:0:\n", "etc/minis/rootfs.json": json.dumps(metadata).encode(), "bin/bash": b"#!/bin/sh\n",
    }.items():
        info = tarfile.TarInfo(name); info.size = len(content); info.mode = 0o755 if name == "bin/bash" else 0o644
        tar.addfile(info, io.BytesIO(content))
rootfs = (root / "ubuntu-arm64-rootfs.tar.gz").read_bytes()
digest = hashlib.sha256(rootfs).hexdigest()
manifest = {
    "schemaVersion": 3, "distro": "ubuntu", "release": "24.04.3", "arch": "arm64", "profile": "base",
    "revision": 1, "rootfsVersion": f"ubuntu-24.04-r1-{digest[:16]}", "rootfsSha256": digest,
    "upstreamSha256": "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048",
    "provisionRevision": 1, "requiredCommands": ["python3", "git", "curl"],
}
(root / "runtime-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
PY
}

mutate_tar() {
  local dir="$1" mode="$2"
  python3 - "$dir" "$mode" <<'PY'
import io, json, pathlib, sys, tarfile
root = pathlib.Path(sys.argv[1]); mode = sys.argv[2]
src = root / "ubuntu-arm64-rootfs.tar.gz"
members = []
with tarfile.open(src, "r:gz") as tar:
    for member in tar.getmembers():
        data = tar.extractfile(member).read() if member.isfile() else None
        members.append((member, data))
with tarfile.open(src, "w:gz") as tar:
    for member, data in members:
        if mode == "malformed_metadata" and member.name == "etc/minis/rootfs.json": data = b"{}"
        if mode == "unsafe_symlink" and member.name == "workspace":
            member = tarfile.TarInfo("workspace"); member.type = tarfile.SYMTYPE; member.linkname = "../../data"
            data = None
        tar.addfile(member, io.BytesIO(data) if data is not None else None)
    if mode == "duplicate_entry":
        dup = tarfile.TarInfo("workspace"); dup.type = tarfile.DIRTYPE; tar.addfile(dup)
PY
}

expect_failure() {
  local name="$1"; shift
  local dir="$TMP/$name"
  make_fixture "$dir"
  "$@" "$dir"
  if "$VERIFY" "$dir" >"$TMP/$name.log" 2>&1; then
    echo "FAIL: $name unexpectedly succeeded" >&2; exit 1
  fi
  echo "PASS: $name"
}

make_fixture "$TMP/valid"
"$VERIFY" "$TMP/valid"
expect_failure tampered_rootfs sh -c 'printf tampered >> "$1/ubuntu-arm64-rootfs.tar.gz"' _
expect_failure missing_rootfs sh -c 'rm "$1/ubuntu-arm64-rootfs.tar.gz"' _
expect_failure wrong_upstream_sha sh -c 'python3 - "$1/runtime-manifest.json" <<"PY"\nimport json, pathlib, sys\np=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d["upstreamSha256"]="0"*64; p.write_text(json.dumps(d))\nPY' _
expect_failure wrong_arch sh -c 'python3 - "$1/runtime-manifest.json" <<"PY"\nimport json, pathlib, sys\np=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d["arch"]="x86_64"; p.write_text(json.dumps(d))\nPY' _
expect_failure invalid_revision sh -c 'python3 - "$1/runtime-manifest.json" <<"PY"\nimport json, pathlib, sys\np=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d["revision"]=0; p.write_text(json.dumps(d))\nPY' _
expect_failure obsolete_broker_field sh -c 'python3 - "$1/runtime-manifest.json" <<"PY"\nimport json, pathlib, sys\np=pathlib.Path(sys.argv[1]); d=json.loads(p.read_text()); d["minisdSha256"]="0"*64; p.write_text(json.dumps(d))\nPY' _

# Tar structural failures use direct fixture mutations.
for mode in unsafe_symlink duplicate_entry malformed_metadata; do
  dir="$TMP/$mode"; make_fixture "$dir"; mutate_tar "$dir" "$mode"
  if "$VERIFY" "$dir" >"$TMP/$mode.log" 2>&1; then echo "FAIL: $mode unexpectedly succeeded" >&2; exit 1; fi
  echo "PASS: $mode"
done
echo "All rootfs-only runtime payload verification tests passed."
