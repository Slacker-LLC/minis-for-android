#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 MINISD ROOTFS_TAR ROOTFS_MANIFEST OUTPUT_JSON RUNTIME_VERSION" >&2
  exit 64
}

[[ $# -eq 5 ]] || usage
MINISD="$1"
ROOTFS_TAR="$2"
ROOTFS_MANIFEST="$3"
OUTPUT="$4"
RUNTIME_VERSION="$5"

[[ -s "$MINISD" ]] || { echo "minisd missing or empty: $MINISD" >&2; exit 65; }
[[ -x "$MINISD" ]] || { echo "minisd is not executable: $MINISD" >&2; exit 66; }
[[ -s "$ROOTFS_TAR" ]] || { echo "rootfs archive missing or empty: $ROOTFS_TAR" >&2; exit 67; }
[[ -s "$ROOTFS_MANIFEST" ]] || { echo "rootfs manifest missing or empty: $ROOTFS_MANIFEST" >&2; exit 68; }
[[ "$RUNTIME_VERSION" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || {
  echo "invalid runtime version: $RUNTIME_VERSION" >&2
  exit 69
}
command -v sha256sum >/dev/null || { echo "sha256sum not found" >&2; exit 70; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 71; }

MINISD_SHA="$(sha256sum "$MINISD" | awk '{print $1}')"
ROOTFS_SHA="$(sha256sum "$ROOTFS_TAR" | awk '{print $1}')"

python3 - "$ROOTFS_MANIFEST" "$ROOTFS_SHA" "$MINISD_SHA" "$OUTPUT" "$RUNTIME_VERSION" <<'PY'
import json
import pathlib
import re
import sys

manifest_path, actual_rootfs_sha, minisd_sha, output_path, runtime_version = sys.argv[1:]
with open(manifest_path, "r", encoding="utf-8") as fh:
    rootfs = json.load(fh)

expected_rootfs_sha = str(rootfs.get("sha256", "")).lower()
if not re.fullmatch(r"[0-9a-f]{64}", expected_rootfs_sha):
    raise SystemExit("rootfs manifest has invalid sha256")
if expected_rootfs_sha != actual_rootfs_sha.lower():
    raise SystemExit("rootfs archive sha256 does not match rootfs manifest")

ubuntu = str(rootfs.get("ubuntu", ""))
release = str(rootfs.get("release", ""))
arch = str(rootfs.get("arch", ""))
profile = str(rootfs.get("profile", ""))
upstream = str(rootfs.get("upstream_sha256", "")).lower()
if not ubuntu.startswith("24.04") or not release.startswith("24.04"):
    raise SystemExit(f"unsupported Ubuntu rootfs: ubuntu={ubuntu} release={release}")
if arch != "arm64":
    raise SystemExit(f"unsupported rootfs arch: {arch}")
if profile != "base":
    raise SystemExit(f"unsupported rootfs profile: {profile}")
if not re.fullmatch(r"[0-9a-f]{64}", upstream):
    raise SystemExit("rootfs manifest has invalid upstream_sha256")

out = {
    "schemaVersion": 1,
    "runtimeVersion": runtime_version,
    "protocolVersion": 1,
    "layoutVersion": 2,
    "abi": "arm64",
    "distributionReady": True,
    "minisd": {
        "source": "external_staged",
        "file": "minisd-arm64",
        "stagedPath": "/data/local/tmp/minis-runtime/minisd-arm64",
        "sha256": minisd_sha.lower(),
    },
    "rootfs": {
        "source": "external_staged",
        "file": "ubuntu-arm64-rootfs.tar.gz",
        "stagedPath": "/data/local/tmp/minis-runtime/ubuntu-arm64-rootfs.tar.gz",
        "sha256": actual_rootfs_sha.lower(),
        "version": f"ubuntu-{ubuntu}",
        "release": release,
        "profile": profile,
        "upstreamSha256": upstream,
    },
    "provisionRevision": 1,
    "requiredCommands": ["python3", "git", "curl"],
}

path = pathlib.Path(output_path)
path.parent.mkdir(parents=True, exist_ok=True)
with path.open("w", encoding="utf-8") as fh:
    json.dump(out, fh, ensure_ascii=False, indent=2, sort_keys=False)
    fh.write("\n")
PY

echo "runtime manifest: $OUTPUT"
echo "  runtime: $RUNTIME_VERSION"
echo "  minisd sha256: $MINISD_SHA"
echo "  rootfs sha256: $ROOTFS_SHA"
