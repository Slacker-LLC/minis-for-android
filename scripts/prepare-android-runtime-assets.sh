#!/usr/bin/env bash
# Build the trusted runtime payload embedded in the Android APK for issue #43.
# Output layout:
#   <OUT>/runtime/minisd-aarch64
#   <OUT>/runtime/minisd-aarch64.sha256
#   <OUT>/runtime/ubuntu-arm64-rootfs.tar.gz
#   <OUT>/runtime/ubuntu-arm64-rootfs.tar.gz.sha256
#   <OUT>/runtime/ubuntu-arm64-rootfs.manifest.json
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${OUT:-$ROOT/src/android/app/build/generated/runtime-assets}"
WORK="${WORK:-$ROOT/src/android/app/build/tmp/runtime-assets}"
RUNTIME="$OUT/runtime"
CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$WORK/cargo-target}"

mkdir -p "$RUNTIME" "$WORK" "$CARGO_TARGET_DIR"

command -v rustup >/dev/null 2>&1 || {
  echo "error: rustup is required to build the bundled minisd recovery binary" >&2
  exit 1
}
command -v cargo >/dev/null 2>&1 || {
  echo "error: cargo is required to build the bundled minisd recovery binary" >&2
  exit 1
}

rustup target add aarch64-unknown-linux-musl

echo "==> build minisd recovery binary"
CARGO_TARGET_DIR="$CARGO_TARGET_DIR" cargo build --locked --release \
  --target aarch64-unknown-linux-musl \
  --manifest-path "$ROOT/src/native/minisd/Cargo.toml"

MINISD_SRC="$CARGO_TARGET_DIR/aarch64-unknown-linux-musl/release/minisd"
MINISD_OUT="$RUNTIME/minisd-aarch64"
[[ -x "$MINISD_SRC" ]] || { echo "error: missing built minisd: $MINISD_SRC" >&2; exit 1; }
install -m 0755 "$MINISD_SRC" "$MINISD_OUT"
MINISD_SHA="$(sha256sum "$MINISD_OUT" | awk '{print $1}')"
printf '%s  %s\n' "$MINISD_SHA" "$(basename "$MINISD_OUT")" > "$RUNTIME/minisd-aarch64.sha256"

echo "==> build Ubuntu recovery rootfs"
DIST="$RUNTIME" WORK="$WORK/ubuntu-rootfs" "$ROOT/scripts/build-ubuntu-rootfs.sh"

echo "==> Android runtime recovery assets ready"
printf '    minisd %s\n' "$MINISD_SHA"
cat "$RUNTIME/ubuntu-arm64-rootfs.tar.gz.sha256"
