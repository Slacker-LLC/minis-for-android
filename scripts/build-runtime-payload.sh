#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${DIST:-$ROOT/dist}"
export DIST

bash "$ROOT/scripts/build-ubuntu-rootfs.sh"

python3 - "$DIST" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

dist = pathlib.Path(sys.argv[1])
rootfs = dist / "ubuntu-arm64-rootfs.tar.gz"
rootfs_meta_path = dist / "ubuntu-arm64-rootfs.manifest.json"

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

meta = json.loads(rootfs_meta_path.read_text(encoding="utf-8"))
rootfs_sha = sha256(rootfs)
if meta.get("sha256") != rootfs_sha:
    raise SystemExit("rootfs build manifest SHA-256 does not match archive")
version = meta.get("version", "")
if not re.fullmatch(r"ubuntu-24\.04-r[1-9][0-9]*-[0-9a-f]{16}", version):
    raise SystemExit(f"invalid rootfs version: {version}")
if not version.endswith(rootfs_sha[:16]):
    raise SystemExit("rootfs version is not derived from final archive")

manifest = {
    "schemaVersion": 3,
    "distro": "ubuntu",
    "release": meta["release"],
    "arch": "arm64",
    "profile": meta["profile"],
    "revision": meta["rootfsRevision"],
    "rootfsVersion": version,
    "rootfsSha256": rootfs_sha,
    "upstreamSha256": meta["upstream_sha256"],
    "provisionRevision": meta["provisionRevision"],
    "requiredCommands": meta["requiredCommands"],
}
(dist / "runtime-manifest.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
)
PY

bash "$ROOT/scripts/verify-runtime-payload.sh" "$DIST"
