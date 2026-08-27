#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/scripts/build-ubuntu-rootfs.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

REL="test"
BASE_NAME="ubuntu-base-${REL}-base-arm64.tar.gz"
TRUSTED="$TMP/trusted.tar.gz"
CORRUPT="$TMP/corrupt.tar.gz"
printf 'trusted-rootfs-bytes\n' > "$TRUSTED"
printf 'corrupt-rootfs-bytes\n' > "$CORRUPT"
EXPECTED="$(sha256sum "$TRUSTED" | awk '{print $1}')"

expect_failure() {
  local name="$1"
  shift
  if "$@" >"$TMP/$name.log" 2>&1; then
    echo "FAIL: $name unexpectedly succeeded" >&2
    cat "$TMP/$name.log" >&2
    exit 1
  fi
  echo "PASS: $name"
}

# 1) Checksum metadata unavailable must be fatal.
mkdir -p "$TMP/unavailable-src"
cp "$TRUSTED" "$TMP/unavailable-src/$BASE_NAME"
expect_failure checksum_unavailable \
  env REL="$REL" EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TMP/unavailable-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/does-not-exist/SHA256SUMS" \
      WORK="$TMP/work-unavailable" DIST="$TMP/dist-unavailable" \
      "$BUILD"

# 2) The exact requested archive must exist in SHA256SUMS.
mkdir -p "$TMP/missing-src"
cp "$TRUSTED" "$TMP/missing-src/$BASE_NAME"
printf '%s *some-other-file.tar.gz\n' "$EXPECTED" > "$TMP/missing-src/SHA256SUMS"
expect_failure checksum_entry_missing \
  env REL="$REL" EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TMP/missing-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/missing-src/SHA256SUMS" \
      WORK="$TMP/work-missing" DIST="$TMP/dist-missing" \
      "$BUILD"

# 3) A downloaded archive whose bytes do not match the pinned/upstream digest
# must fail before extraction.
mkdir -p "$TMP/mismatch-src"
cp "$CORRUPT" "$TMP/mismatch-src/$BASE_NAME"
printf '%s *%s\n' "$EXPECTED" "$BASE_NAME" > "$TMP/mismatch-src/SHA256SUMS"
expect_failure checksum_mismatch \
  env REL="$REL" EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TMP/mismatch-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/mismatch-src/SHA256SUMS" \
      WORK="$TMP/work-mismatch" DIST="$TMP/dist-mismatch" \
      "$BUILD"

echo "All rootfs verification failure-mode tests passed."
