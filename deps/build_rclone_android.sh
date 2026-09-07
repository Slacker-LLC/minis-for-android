#!/usr/bin/env bash
# Build rclone as an Android .aar via gomobile.
#
# Android is the EASIER of the two platforms here: rclone ships an official
# gomobile binding (librclone/gomobile) and upstream documents this exact
# command. iOS is the one upstream has not tested.
#
# One deviation from upstream's command: their gomobile package hardcodes
# `backend/all` (all 70 backends). We bind ./gomobile instead, which re-exports
# the same API against the trimmed backend list in backends/backends.go.
#
# Output: deps/build/rclone/rclone.aar
# Import the complete AAR from src/android/app/libs/rclone.aar.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/deps/rclone-mobile"
BUILD="$ROOT/deps/build/rclone"

command -v go >/dev/null || { echo "error: go toolchain not found" >&2; exit 1; }
command -v gomobile >/dev/null || {
  echo "error: gomobile not installed. Run:" >&2
  echo "  go install golang.org/x/mobile/cmd/gomobile@latest" >&2
  echo "  gomobile init" >&2
  exit 1
}
[ -n "${ANDROID_NDK_HOME:-}${ANDROID_HOME:-}" ] || {
  echo "error: set ANDROID_NDK_HOME (or ANDROID_HOME) first" >&2; exit 1; }
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/${MINIS_NDK_VERSION:-28.0.12433566}}"

mkdir -p "$BUILD"
cd "$SRC"

# The pinned NDK r28+ defaults to 16 KB ELF alignment. Keep both the cgo
# linker flags and Go's external-linker flags explicit as a guard for local
# overrides to an older NDK: gomobile's generated c-shared build uses cgo and
# the final ELF is produced by the NDK linker.
export CGO_LDFLAGS="${CGO_LDFLAGS:+$CGO_LDFLAGS }-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

# Match both app ABIs, including the emulator binding.
gomobile bind -v \
  -target=android/arm64,android/amd64 \
  -androidapi 24 \
  -javapkg=com.openminis.rclone \
  -ldflags='-s -w -linkmode=external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"' \
  -o "$BUILD/rclone.aar" \
  ./gomobile

READELF="${READELF:-$(command -v llvm-readelf || command -v readelf || true)}"
if [[ -z "$READELF" ]]; then
  echo "error: llvm-readelf or readelf is required to verify rclone 16 KB ELF alignment" >&2
  exit 1
fi

CHECK_DIR="$(mktemp -d)"
trap 'rm -rf "$CHECK_DIR"' EXIT
for ABI in arm64-v8a x86_64; do
  unzip -p "$BUILD/rclone.aar" "jni/$ABI/libgojni.so" > "$CHECK_DIR/libgojni.so"
  HEADER="$("$READELF" -lW "$CHECK_DIR/libgojni.so")"
  if awk '$1 == "LOAD" { count++; if ($NF != "0x4000") bad=1 } END { exit (bad || !count) ? 0 : 1 }' <<<"$HEADER"; then
    echo "error: rclone $ABI libgojni.so LOAD segments are not all 16 KB aligned" >&2
    awk '$1 == "LOAD"' <<<"$HEADER" >&2
    exit 1
  fi
done

echo "==> $BUILD/rclone.aar"
du -sh "$BUILD/rclone.aar" | awk '{print "    size:", $1}'
