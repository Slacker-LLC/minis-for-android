#!/usr/bin/env bash
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "usage: $0 path/to/app.apk" >&2
  exit 2
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME is required" >&2
  exit 2
fi

ZIPALIGN="${ZIPALIGN:-$(find "$ANDROID_SDK_ROOT/build-tools" -type f \( -name zipalign -o -name zipalign.exe \) -print | sort -V | tail -n1)}"
if [[ -z "$ZIPALIGN" || ! -f "$ZIPALIGN" ]]; then
  echo "zipalign not found" >&2
  exit 2
fi

if command -v cygpath >/dev/null; then
  APK="$(cygpath -am "$APK")"
fi
"$ZIPALIGN" -c -P 16 -v 4 "$APK" >/dev/null

PYTHON=(python3)
if command -v py >/dev/null; then
  PYTHON=(py -3)
fi
"${PYTHON[@]}" - "$APK" <<'PY'
import struct
import sys
import zipfile

apk = sys.argv[1]
bad = []


def load_alignments(data):
    if len(data) < 16 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    endian = "<" if data[5] == 1 else ">" if data[5] == 2 else None
    if endian is None:
        raise ValueError("unsupported ELF byte order")
    if elf_class == 2:
        if len(data) < 64:
            raise ValueError("truncated ELF64 header")
        phoff = struct.unpack_from(endian + "Q", data, 32)[0]
        phentsize = struct.unpack_from(endian + "H", data, 54)[0]
        phnum = struct.unpack_from(endian + "H", data, 56)[0]
        type_size = 4
        align_offset = 48
        align_size = 8
    elif elf_class == 1:
        if len(data) < 52:
            raise ValueError("truncated ELF32 header")
        phoff = struct.unpack_from(endian + "I", data, 28)[0]
        phentsize = struct.unpack_from(endian + "H", data, 42)[0]
        phnum = struct.unpack_from(endian + "H", data, 44)[0]
        type_size = 4
        align_offset = 28
        align_size = 4
    else:
        raise ValueError(f"unsupported ELF class: {elf_class}")

    minimum_entry_size = max(type_size, align_offset + align_size)
    if phentsize < minimum_entry_size:
        raise ValueError("invalid program-header size")
    alignments = []
    for index in range(phnum):
        entry = phoff + index * phentsize
        if entry + minimum_entry_size > len(data):
            raise ValueError("truncated program headers")
        p_type = struct.unpack_from(endian + "I", data, entry)[0]
        if p_type == 1:  # PT_LOAD
            if align_size == 8:
                align = struct.unpack_from(endian + "Q", data, entry + align_offset)[0]
            else:
                align = struct.unpack_from(endian + "I", data, entry + align_offset)[0]
            alignments.append(align)
    if not alignments:
        raise ValueError("ELF has no PT_LOAD segments")
    return alignments


with zipfile.ZipFile(apk) as archive:
    libraries = sorted(name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so"))
    if not libraries:
        raise SystemExit("APK contains no native libraries")
    abis = {name.split('/')[1] for name in libraries}
    for abi in abis:
        for library in ('libgojni.so', 'libpty_bridge.so', 'libjieba_jni.so', 'libminis_crash_handler.so'):
            if f'lib/{abi}/{library}' not in libraries:
                bad.append(f'{abi}: missing required {library}')
    for name in libraries:
        try:
            alignments = load_alignments(archive.read(name))
        except (KeyError, ValueError, struct.error) as error:
            bad.append(f"{name}: {error}")
            continue
        if any(align < 0x4000 or align % 0x4000 != 0 for align in alignments):
            bad.append(f"{name}: LOAD alignments={','.join(hex(align) for align in alignments)}")

if bad:
    raise SystemExit("16 KB ELF alignment check failed:\n" + "\n".join(bad))

print(f"16 KB APK alignment verified: {len(libraries)} native libraries")
PY
