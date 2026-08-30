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

# Invalid runtime revision metadata must fail before any network/extraction work.
expect_failure rootfs_revision_zero \
  env REL="$REL" ROOTFS_REVISION=0 EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TRUSTED" SUMS_URL="file://$TMP/missing" \
      WORK="$TMP/work-rev" DIST="$TMP/dist-rev" "$BUILD"
expect_failure provision_revision_placeholder \
  env REL="$REL" PROVISION_REVISION=managed EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TRUSTED" SUMS_URL="file://$TMP/missing" \
      WORK="$TMP/work-prov" DIST="$TMP/dist-prov" "$BUILD"

# 1) Checksum metadata unavailable must be fatal.
mkdir -p "$TMP/unavailable-src"
cp "$TRUSTED" "$TMP/unavailable-src/$BASE_NAME"
expect_failure checksum_unavailable \
  env REL="$REL" EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TMP/unavailable-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/does-not-exist/SHA256SUMS" \
      WORK="$TMP/work-unavailable" DIST="$TMP/dist-unavailable" "$BUILD"

# 2) The exact requested archive must exist in SHA256SUMS.
mkdir -p "$TMP/missing-src"
cp "$TRUSTED" "$TMP/missing-src/$BASE_NAME"
printf '%s *some-other-file.tar.gz\n' "$EXPECTED" > "$TMP/missing-src/SHA256SUMS"
expect_failure checksum_entry_missing \
  env REL="$REL" EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TMP/missing-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/missing-src/SHA256SUMS" \
      WORK="$TMP/work-missing" DIST="$TMP/dist-missing" "$BUILD"

# 3) Corrupt downloaded bytes must fail before extraction.
mkdir -p "$TMP/mismatch-src"
cp "$CORRUPT" "$TMP/mismatch-src/$BASE_NAME"
printf '%s *%s\n' "$EXPECTED" "$BASE_NAME" > "$TMP/mismatch-src/SHA256SUMS"
expect_failure checksum_mismatch \
  env REL="$REL" EXPECTED_BASE_SHA256="$EXPECTED" \
      BASE_URL="file://$TMP/mismatch-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/mismatch-src/SHA256SUMS" \
      WORK="$TMP/work-mismatch" DIST="$TMP/dist-mismatch" "$BUILD"

# 4) A minimal trusted Ubuntu-like base exercises the successful packaging path
# twice. Deterministic tar/gzip settings must yield an identical final artifact.
FIXTURE="$TMP/fixture-root"
mkdir -p "$FIXTURE/etc"
printf 'root:x:0:0:root:/root:/bin/sh\n' > "$FIXTURE/etc/passwd"
printf 'root:x:0:\n' > "$FIXTURE/etc/group"
printf 'VERSION_ID="24.04"\n' > "$FIXTURE/etc/os-release"
mkdir -p "$TMP/fixture-src"
tar -czf "$TMP/fixture-src/$BASE_NAME" -C "$FIXTURE" .
FIXTURE_SHA="$(sha256sum "$TMP/fixture-src/$BASE_NAME" | awk '{print $1}')"
printf '%s *%s\n' "$FIXTURE_SHA" "$BASE_NAME" > "$TMP/fixture-src/SHA256SUMS"

for run in a b; do
  env REL="$REL" ROOTFS_REVISION=7 PROVISION_REVISION=3 \
      EXPECTED_BASE_SHA256="$FIXTURE_SHA" \
      BASE_URL="file://$TMP/fixture-src/$BASE_NAME" \
      SUMS_URL="file://$TMP/fixture-src/SHA256SUMS" \
      WORK="$TMP/work-$run" DIST="$TMP/dist-$run" "$BUILD" >/dev/null
  sha256sum -c "$TMP/dist-$run/ubuntu-arm64-rootfs.tar.gz.sha256" >/dev/null
  grep -Eq '"version": "ubuntu-24.04-r7-[0-9a-f]{16}"' "$TMP/dist-$run/ubuntu-arm64-rootfs.manifest.json"
  grep -Fq '"arch": "arm64-v8a"' "$TMP/dist-$run/ubuntu-arm64-rootfs.manifest.json"
  grep -Fq '"provisionRevision": 3' "$TMP/dist-$run/ubuntu-arm64-rootfs.manifest.json"
  grep -Fq '"requiredCommands": ["python3", "git", "curl"]' "$TMP/dist-$run/ubuntu-arm64-rootfs.manifest.json"
done
cmp "$TMP/dist-a/ubuntu-arm64-rootfs.tar.gz" "$TMP/dist-b/ubuntu-arm64-rootfs.tar.gz"
cmp "$TMP/dist-a/ubuntu-arm64-rootfs.manifest.json" "$TMP/dist-b/ubuntu-arm64-rootfs.manifest.json"
echo "PASS: reproducible_final_rootfs"

echo "All rootfs verification and reproducibility tests passed."
