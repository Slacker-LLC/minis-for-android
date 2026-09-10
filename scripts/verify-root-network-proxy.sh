#!/usr/bin/env bash
set -euo pipefail
INPUT="${1:-}"
[[ -n "$INPUT" && -e "$INPUT" ]] || { echo "usage: $0 path/to/dist-or.apk" >&2; exit 2; }

python3 - "$INPUT" <<'PY'
import hashlib
import pathlib
import re
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
if source.is_dir():
    binary_path = source / "minis-root-network-proxy-arm64-v8a"
    checksum_path = source / "minis-root-network-proxy-arm64-v8a.sha256"
    if not binary_path.is_file() or not checksum_path.is_file():
        raise SystemExit("root network proxy payload is incomplete")
    binary = binary_path.read_bytes()
    expected = checksum_path.read_text(encoding="utf-8").split()[0].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise SystemExit("root network proxy checksum file is invalid")
    if hashlib.sha256(binary).hexdigest() != expected:
        raise SystemExit("root network proxy SHA-256 mismatch")
else:
    with zipfile.ZipFile(source) as archive:
        path = "lib/arm64-v8a/libminisnetproxy.so"
        try:
            binary = archive.read(path)
        except KeyError:
            raise SystemExit(f"root network proxy is missing from APK: {path}") from None
        if "lib/arm64-v8a/libminisd.so" in archive.namelist():
            raise SystemExit("obsolete libminisd.so is still packaged")

if not binary.startswith(b"\x7fELF") or len(binary) < 20:
    raise SystemExit("root network proxy is not an ELF binary")
if binary[4] != 2 or binary[5] != 1:
    raise SystemExit("root network proxy is not little-endian ELF64")
if int.from_bytes(binary[16:18], "little") != 3:
    raise SystemExit("root network proxy is not PIE/ET_DYN")
if int.from_bytes(binary[18:20], "little") != 183:
    raise SystemExit("root network proxy is not AArch64")
print(f"root network proxy verified: sha256={hashlib.sha256(binary).hexdigest()}")
PY
