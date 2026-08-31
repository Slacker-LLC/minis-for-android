#!/usr/bin/env bash
set -euo pipefail

INPUT="${1:-}"
if [[ -z "$INPUT" || ! -e "$INPUT" ]]; then
  echo "usage: $0 path/to/dist-or.apk" >&2
  exit 2
fi

python3 - "$INPUT" <<'PY'
import hashlib
import io
import json
import pathlib
import re
import sys
import tarfile
import zipfile

source = pathlib.Path(sys.argv[1])
if source.is_dir():
    names = {
        "minisd": "minisd-arm64-v8a",
        "rootfs": "ubuntu-arm64-rootfs.tar.gz",
        "manifest": "runtime-manifest.json",
    }
    def read(name):
        path = source / names[name]
        if not path.is_file():
            raise SystemExit(f"runtime payload is missing: {path}")
        return path.read_bytes()
else:
    names = {
        "minisd": "lib/arm64-v8a/libminisd.so",
        "rootfs": "assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz",
        "manifest": "assets/minis-runtime/runtime-manifest.json",
    }
    archive = zipfile.ZipFile(source)
    def read(name):
        path = names[name]
        try:
            return archive.read(path)
        except KeyError:
            raise SystemExit(f"runtime payload is missing: {path}") from None

minisd = read("minisd")
rootfs = read("rootfs")
manifest_raw = read("manifest")
if not minisd.startswith(b"\x7fELF"):
    raise SystemExit("packaged minisd is not an ELF binary")
if len(minisd) < 20 or minisd[4] != 2 or minisd[5] != 1:
    raise SystemExit("packaged minisd is not little-endian ELF64")
if int.from_bytes(minisd[16:18], "little") != 3:
    raise SystemExit("packaged minisd is not PIE/ET_DYN")
if int.from_bytes(minisd[18:20], "little") != 183:
    raise SystemExit("packaged minisd is not AArch64")
if not rootfs.startswith(b"\x1f\x8b"):
    raise SystemExit("packaged rootfs is not gzip data")
try:
    with tarfile.open(fileobj=io.BytesIO(rootfs), mode="r:gz") as tar:
        members = {member.name.removeprefix("./").rstrip("/"): member for member in tar.getmembers()}
        required_rootfs = {
            "etc/os-release",
            "etc/passwd",
            "etc/group",
            "etc/minis/rootfs.json",
            "workspace",
            "memory",
            "skills",
            "shared",
            "proc",
            "sys",
            "dev",
            "tmp",
            "run",
            "var/minis",
        }
        missing_rootfs = sorted(required_rootfs - members.keys())
        if missing_rootfs:
            raise SystemExit(f"packaged rootfs is missing layout entries: {', '.join(missing_rootfs)}")
        if "bin/bash" not in members and "usr/bin/bash" not in members and "bin/sh" not in members:
            raise SystemExit("packaged rootfs has no shell")
        metadata_file = tar.extractfile(members["etc/minis/rootfs.json"])
        if metadata_file is None:
            raise SystemExit("packaged rootfs metadata is not a regular file")
        rootfs_metadata = json.load(metadata_file)
except (tarfile.TarError, json.JSONDecodeError) as error:
    raise SystemExit(f"packaged rootfs archive is invalid: {error}") from None

try:
    manifest = json.loads(manifest_raw.decode("utf-8"))
except (UnicodeDecodeError, json.JSONDecodeError) as error:
    raise SystemExit(f"runtime manifest is invalid JSON: {error}") from None

minisd_sha = hashlib.sha256(minisd).hexdigest()
rootfs_sha = hashlib.sha256(rootfs).hexdigest()
required = {
    "schemaVersion": 2,
    "protocolVersion": 1,
    "layoutVersion": 2,
    "abi": "arm64-v8a",
    "rootfsProfile": "base",
}
for key, expected in required.items():
    if manifest.get(key) != expected:
        raise SystemExit(f"runtime manifest {key} must be {expected!r}")
if manifest.get("minisdSha256") != minisd_sha:
    raise SystemExit("runtime manifest minisd SHA-256 mismatch")
if manifest.get("rootfsSha256") != rootfs_sha:
    raise SystemExit("runtime manifest rootfs SHA-256 mismatch")
pinned_upstream = "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
if manifest.get("rootfsUpstreamSha256") != pinned_upstream:
    raise SystemExit("runtime manifest does not use the pinned Ubuntu upstream SHA-256")
if rootfs_metadata.get("upstream_sha256") != pinned_upstream:
    raise SystemExit("packaged rootfs metadata does not use the pinned Ubuntu upstream SHA-256")
if rootfs_metadata.get("distro") != "ubuntu" or rootfs_metadata.get("profile") != "base":
    raise SystemExit("packaged rootfs metadata has an unsupported identity")
version = manifest.get("rootfsVersion", "")
if not re.fullmatch(r"ubuntu-24\.04-r[1-9][0-9]*-[0-9a-f]{16}", version):
    raise SystemExit("runtime manifest has invalid rootfsVersion")
if not version.endswith(rootfs_sha[:16]):
    raise SystemExit("runtime rootfsVersion does not match the rootfs digest")
if not str(manifest.get("rootfsRelease", "")).startswith("24.04"):
    raise SystemExit("runtime manifest has unsupported rootfs release")
if manifest.get("rootfsRelease") != rootfs_metadata.get("release"):
    raise SystemExit("runtime manifest release does not match rootfs metadata")
if not isinstance(manifest.get("provisionRevision"), int) or manifest["provisionRevision"] <= 0:
    raise SystemExit("runtime manifest provisionRevision must be positive")
if manifest.get("requiredCommands") != ["python3", "git", "curl"]:
    raise SystemExit("runtime manifest requiredCommands mismatch")
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", manifest.get("minisdVersion", "")):
    raise SystemExit("runtime manifest has invalid minisdVersion")

print(f"runtime payload verified: minisd={minisd_sha} rootfs={rootfs_sha}")
PY
