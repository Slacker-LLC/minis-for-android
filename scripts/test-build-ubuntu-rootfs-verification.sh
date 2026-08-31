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

# 4) Identical valid inputs must produce the same final privileged rootfs.
mkdir -p "$TMP/valid-rootfs/etc" "$TMP/valid-rootfs/bin"
printf 'VERSION_ID="24.04"\n' > "$TMP/valid-rootfs/etc/os-release"
printf 'root:x:0:0:root:/root:/bin/bash\n' > "$TMP/valid-rootfs/etc/passwd"
printf 'root:x:0:\n' > "$TMP/valid-rootfs/etc/group"
printf '#!/bin/sh\n' > "$TMP/valid-rootfs/bin/bash"
chmod 755 "$TMP/valid-rootfs/bin/bash"
mkdir -p "$TMP/valid-src"
tar -czf "$TMP/valid-src/$BASE_NAME" -C "$TMP/valid-rootfs" .
VALID_SHA="$(sha256sum "$TMP/valid-src/$BASE_NAME" | awk '{print $1}')"
printf '%s *%s\n' "$VALID_SHA" "$BASE_NAME" > "$TMP/valid-src/SHA256SUMS"
for run in one two; do
  env REL="$REL" EXPECTED_BASE_SHA256="$VALID_SHA" \
      BASE_URL="file://$TMP/valid-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/valid-src/SHA256SUMS" \
      WORK="$TMP/work-$run" DIST="$TMP/dist-$run" \
      "$BUILD" >"$TMP/reproducible-$run.log"
done
cmp "$TMP/dist-one/ubuntu-arm64-rootfs.tar.gz" "$TMP/dist-two/ubuntu-arm64-rootfs.tar.gz"
cmp "$TMP/dist-one/ubuntu-arm64-rootfs.manifest.json" "$TMP/dist-two/ubuntu-arm64-rootfs.manifest.json"
(cd "$TMP/dist-one" && sha256sum -c ubuntu-arm64-rootfs.tar.gz.sha256)
echo "PASS: reproducible_rootfs"

echo "All rootfs verification tests passed."
