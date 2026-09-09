#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${DIST:-$ROOT/dist}"
TARGET="aarch64-linux-android"
API_LEVEL="${ANDROID_MIN_SDK:-26}"
NDK_VERSION="${MINIS_NDK_VERSION:-28.2.13676358}"

case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64"; TOOL_SUFFIX=""; CLANG_SUFFIX="" ;;
  Darwin) HOST_TAG="darwin-x86_64"; TOOL_SUFFIX=""; CLANG_SUFFIX="" ;;
  MINGW*|MSYS*) HOST_TAG="windows-x86_64"; TOOL_SUFFIX=".exe"; CLANG_SUFFIX=".cmd" ;;
  *) echo "error: unsupported host for root-network-proxy Android build" >&2; exit 2 ;;
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
if [[ "$HOST_TAG" == "windows-x86_64" ]]; then NDK="$(cygpath -u "$NDK")"; fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CLANG="$TOOLCHAIN/${TARGET}${API_LEVEL}-clang$CLANG_SUFFIX"
READELF="$TOOLCHAIN/llvm-readelf$TOOL_SUFFIX"
[[ -f "$CLANG" && -f "$READELF" ]] || { echo "error: Android NDK toolchain unavailable: $TOOLCHAIN" >&2; exit 2; }
rustup target list --installed | grep -Fxq "$TARGET" || { echo "error: Rust target $TARGET is not installed" >&2; exit 2; }

if [[ "$HOST_TAG" == "windows-x86_64" ]]; then
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$(cygpath -w "$CLANG")"
else
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CLANG"
fi
export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }-C relocation-model=pic -C link-arg=-pie -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384"

cargo build --locked --release --target "$TARGET" --manifest-path "$ROOT/src/native/root-network-proxy/Cargo.toml"
BINARY="$ROOT/src/native/root-network-proxy/target/$TARGET/release/minis-root-network-proxy"
[[ -f "$BINARY" ]] || { echo "error: root network proxy build output missing" >&2; exit 1; }

HEADER="$($READELF -h -l -d -W "$BINARY")"
grep -Eq 'Class:[[:space:]]+ELF64' <<<"$HEADER" || { echo "error: root network proxy is not ELF64" >&2; exit 1; }
grep -Eq 'Machine:[[:space:]]+AArch64' <<<"$HEADER" || { echo "error: root network proxy is not AArch64" >&2; exit 1; }
grep -Eq 'Type:[[:space:]]+DYN' <<<"$HEADER" || { echo "error: root network proxy is not PIE/ET_DYN" >&2; exit 1; }
grep -Fq '/system/bin/linker64' <<<"$HEADER" || { echo "error: root network proxy is not linked for Android" >&2; exit 1; }
if awk '$1 == "LOAD" && $NF != "0x4000" { bad=1 } END { exit bad ? 0 : 1 }' <<<"$HEADER"; then
  echo "error: root network proxy LOAD segments are not all 16 KB aligned" >&2
  exit 1
fi
grep -Fq 'libc.so' <<<"$HEADER" || { echo "error: root network proxy has no Android libc dependency" >&2; exit 1; }
if grep -Eq 'ld-linux|libglibc' <<<"$HEADER"; then echo "error: root network proxy unexpectedly depends on glibc" >&2; exit 1; fi

mkdir -p "$DIST"
install -m 0755 "$BINARY" "$DIST/minis-root-network-proxy-arm64-v8a"
sha256sum "$DIST/minis-root-network-proxy-arm64-v8a" > "$DIST/minis-root-network-proxy-arm64-v8a.sha256"
echo "==> $DIST/minis-root-network-proxy-arm64-v8a"
