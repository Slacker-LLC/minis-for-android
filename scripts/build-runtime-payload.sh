#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${DIST:-$ROOT/dist}"
export DIST

"$ROOT/scripts/build-minisd-android.sh"
"$ROOT/scripts/build-ubuntu-rootfs.sh"

python3 - "$ROOT" "$DIST" <<'PY'
import hashlib
import json
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
dist = pathlib.Path(sys.argv[2])
binary = dist / "minisd-arm64-v8a"
rootfs = dist / "ubuntu-arm64-rootfs.tar.gz"
rootfs_meta_path = dist / "ubuntu-arm64-rootfs.manifest.json"

def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

rootfs_meta = json.loads(rootfs_meta_path.read_text(encoding="utf-8"))
rootfs_sha = sha256(rootfs)
if rootfs_meta.get("sha256") != rootfs_sha:
    raise SystemExit("rootfs build manifest SHA-256 does not match the archive")
rootfs_version = rootfs_meta.get("version", "")
if not re.fullmatch(r"ubuntu-24\.04-r[1-9][0-9]*-[0-9a-f]{16}", rootfs_version):
    raise SystemExit(f"invalid rootfs version: {rootfs_version}")
if not rootfs_version.endswith(rootfs_sha[:16]):
    raise SystemExit("rootfs version is not derived from the final archive")

cargo = (root / "src/native/minisd/Cargo.toml").read_text(encoding="utf-8")
match = re.search(r'^version\s*=\s*"([^"]+)"', cargo, re.MULTILINE)
if not match:
    raise SystemExit("cannot read minisd version from Cargo.toml")

manifest = {
    "schemaVersion": 2,
    "minisdVersion": match.group(1),
    "minisdSha256": sha256(binary),
    "protocolVersion": 1,
    "layoutVersion": 2,
    "abi": "arm64-v8a",
    "rootfsVersion": rootfs_version,
    "rootfsSha256": rootfs_sha,
    "rootfsRelease": rootfs_meta["release"],
    "rootfsProfile": rootfs_meta["profile"],
    "rootfsUpstreamSha256": rootfs_meta["upstream_sha256"],
    "provisionRevision": rootfs_meta["provisionRevision"],
    "requiredCommands": rootfs_meta["requiredCommands"],
}
(dist / "runtime-manifest.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n",
    encoding="utf-8",
)
PY

"$ROOT/scripts/verify-runtime-payload.sh" "$DIST"
