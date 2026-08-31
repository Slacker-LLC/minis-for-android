#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${DIST:-$ROOT/dist}"
TARGET="aarch64-linux-android"
API_LEVEL="${ANDROID_MIN_SDK:-26}"
NDK_VERSION="${MINIS_NDK_VERSION:-27.0.12077973}"

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64"; TOOL_SUFFIX=""; CLANG_SUFFIX="" ;;
  Darwin) HOST_TAG="darwin-x86_64"; TOOL_SUFFIX=""; CLANG_SUFFIX="" ;;
  MINGW*|MSYS*) HOST_TAG="windows-x86_64"; TOOL_SUFFIX=".exe"; CLANG_SUFFIX=".cmd" ;;
  *)
    echo "error: build-minisd-android.sh requires Linux, macOS, or Git Bash on Windows" >&2
    exit 2
    ;;
esac

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
  NDK="$ANDROID_NDK_HOME"
elif [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
  NDK="$ANDROID_NDK_ROOT"
elif [[ -n "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}" ]]; then
  NDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}/ndk/$NDK_VERSION"
else
  echo "error: set ANDROID_SDK_ROOT or ANDROID_NDK_HOME" >&2
  exit 2
fi

if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  NDK="$(cygpath -u "$NDK")"
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CLANG="$TOOLCHAIN/${TARGET}${API_LEVEL}-clang$CLANG_SUFFIX"
READELF="$TOOLCHAIN/llvm-readelf$TOOL_SUFFIX"
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  tools_available=false
  [[ -f "$CLANG" && -f "$READELF" ]] && tools_available=true
else
  tools_available=false
  [[ -x "$CLANG" && -x "$READELF" ]] && tools_available=true
fi
if [[ "$tools_available" != true ]]; then
  echo "error: Android NDK $NDK_VERSION toolchain is unavailable under $TOOLCHAIN" >&2
  exit 2
fi
if ! rustup target list --installed | grep -Fxq "$TARGET"; then
  echo "error: Rust target $TARGET is not installed; run: rustup target add $TARGET" >&2
  exit 2
fi

if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$(cygpath -w "$CLANG")"
else
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
fi
export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }-C relocation-model=pic -C link-arg=-pie -C link-arg=-Wl,-z,max-page-size=16384"

cargo build \
  --locked \
  --release \
  --target "$TARGET" \
  --manifest-path "$ROOT/src/native/minisd/Cargo.toml"

BINARY="$ROOT/src/native/minisd/target/$TARGET/release/minisd"
[[ -f "$BINARY" ]] || { echo "error: minisd build output is missing: $BINARY" >&2; exit 1; }

HEADER="$($READELF -h -l -d -W "$BINARY")"
grep -Eq 'Class:[[:space:]]+ELF64' <<<"$HEADER" || { echo "error: minisd is not ELF64" >&2; exit 1; }
grep -Eq 'Machine:[[:space:]]+AArch64' <<<"$HEADER" || { echo "error: minisd is not AArch64" >&2; exit 1; }
grep -Eq 'Type:[[:space:]]+DYN' <<<"$HEADER" || { echo "error: minisd is not PIE/ET_DYN" >&2; exit 1; }
grep -Fq '/system/bin/linker64' <<<"$HEADER" || { echo "error: minisd is not linked for Android" >&2; exit 1; }
if awk '$1 == "LOAD" && $NF != "0x4000" { bad=1 } END { exit bad ? 0 : 1 }' <<<"$HEADER"; then
  echo "error: minisd LOAD segments are not all 16 KB aligned" >&2
  exit 1
fi
grep -Fq 'libc.so' <<<"$HEADER" || { echo "error: minisd has no Android libc dependency" >&2; exit 1; }
if grep -Eq 'ld-linux|libglibc' <<<"$HEADER"; then
  echo "error: minisd unexpectedly depends on glibc" >&2
  exit 1
fi

mkdir -p "$DIST"
install -m 0755 "$BINARY" "$DIST/minisd-arm64-v8a"
sha256sum "$DIST/minisd-arm64-v8a" > "$DIST/minisd-arm64-v8a.sha256"
echo "==> $DIST/minisd-arm64-v8a"
