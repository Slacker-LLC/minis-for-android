#!/usr/bin/env bash
# Build Ubuntu 24.04 LTS arm64 base rootfs.
# Produces dist/ubuntu-arm64-rootfs.tar.gz (+ .sha256, manifest).
set -euo pipefail
umask 022

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${DIST:-$ROOT/dist}"
WORK="${WORK:-/tmp/minis-ubuntu-rootfs}"
REL="${REL:-24.04.3}"
ROOTFS_REVISION="${ROOTFS_REVISION:-1}"
PROVISION_REVISION="${PROVISION_REVISION:-1}"
BASE_NAME="ubuntu-base-${REL}-base-arm64.tar.gz"
BASE_URL="${BASE_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/${BASE_NAME}}"
SUMS_URL="${SUMS_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS}"

[[ "$ROOTFS_REVISION" =~ ^[1-9][0-9]*$ ]] || { echo "error: ROOTFS_REVISION must be positive" >&2; exit 1; }
[[ "$PROVISION_REVISION" =~ ^[1-9][0-9]*$ ]] || { echo "error: PROVISION_REVISION must be positive" >&2; exit 1; }

# Supply-chain ceiling: the default release is pinned in-repo. A non-default
# release must provide EXPECTED_BASE_SHA256 explicitly rather than trusting a
# mutable remote checksum file by itself.
case "$REL" in
  24.04.3) PINNED_BASE_SHA256="7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048" ;;
  *) PINNED_BASE_SHA256="" ;;
esac
EXPECTED_BASE_SHA256="${EXPECTED_BASE_SHA256:-$PINNED_BASE_SHA256}"
if [[ ! "$EXPECTED_BASE_SHA256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "error: no valid pinned EXPECTED_BASE_SHA256 for Ubuntu Base release $REL" >&2
  exit 1
fi
EXPECTED_BASE_SHA256="${EXPECTED_BASE_SHA256,,}"

mkdir -p "$DIST" "$WORK"
cd "$WORK"

echo "==> download $BASE_NAME"
if [[ ! -f "$BASE_NAME" ]]; then
  curl -fL --retry 3 -o "$BASE_NAME" "$BASE_URL"
fi

echo "==> verify Ubuntu Base checksum"
# Always refresh upstream metadata. Failure is fatal; cached/stale metadata is
# not accepted as proof for a privileged rootfs build.
curl -fL --retry 3 -o SHA256SUMS.tmp "$SUMS_URL"
mv SHA256SUMS.tmp SHA256SUMS

UPSTREAM_SHA256="$(awk -v name="$BASE_NAME" '
  {
    file=$2
    sub(/^\*/, "", file)
    if (file == name) { print tolower($1); found=1; exit }
  }
  END { if (!found) exit 1 }
' SHA256SUMS)" || {
  echo "error: $BASE_NAME is not listed in $SUMS_URL" >&2
  exit 1
}

if [[ ! "$UPSTREAM_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: invalid SHA-256 entry for $BASE_NAME in upstream checksum file" >&2
  exit 1
fi
if [[ "$UPSTREAM_SHA256" != "$EXPECTED_BASE_SHA256" ]]; then
  echo "error: upstream SHA-256 differs from repository pin" >&2
  echo "       expected $EXPECTED_BASE_SHA256" >&2
  echo "       upstream $UPSTREAM_SHA256" >&2
  exit 1
fi

ACTUAL_BASE_SHA256="$(sha256sum "$BASE_NAME" | awk '{print tolower($1)}')"
if [[ "$ACTUAL_BASE_SHA256" != "$EXPECTED_BASE_SHA256" ]]; then
  echo "error: Ubuntu Base archive SHA-256 mismatch" >&2
  echo "       expected $EXPECTED_BASE_SHA256" >&2
  echo "       actual   $ACTUAL_BASE_SHA256" >&2
  exit 1
fi
printf '%s  %s\n' "$EXPECTED_BASE_SHA256" "$BASE_NAME" > verified-base.sha256
sha256sum -c verified-base.sha256

STAGE="$WORK/rootfs"
rm -rf "$STAGE"
mkdir -p "$STAGE"
echo "==> extract"
tar -xzf "$BASE_NAME" -C "$STAGE"

echo "==> overlay minis layout"
mkdir -p \
  "$STAGE/workspace" \
  "$STAGE/memory" \
  "$STAGE/skills" \
  "$STAGE/shared" \
  "$STAGE/mnt" \
  "$STAGE/var/minis" \
  "$STAGE/etc/minis" \
  "$STAGE/etc/profile.d" \
  "$STAGE/dev" \
  "$STAGE/proc" \
  "$STAGE/sys" \
  "$STAGE/tmp" \
  "$STAGE/run"

if ! grep -q ':10000:10000:' "$STAGE/etc/passwd" 2>/dev/null; then
  printf 'minis:x:10000:10000:Minis:/workspace:/bin/bash\n' >> "$STAGE/etc/passwd"
fi
if ! grep -q ':10000:' "$STAGE/etc/group" 2>/dev/null; then
  printf 'minis:x:10000:\n' >> "$STAGE/etc/group"
fi

ln -sfn /workspace "$STAGE/var/minis/workspace"
ln -sfn /workspace/attachments "$STAGE/var/minis/attachments"
ln -sfn /workspace/offloads "$STAGE/var/minis/offloads"
ln -sfn /workspace/browser "$STAGE/var/minis/browser"
ln -sfn /memory "$STAGE/var/minis/memory"
ln -sfn /skills "$STAGE/var/minis/skills"
ln -sfn /shared "$STAGE/var/minis/shared"

rm -f "$STAGE/etc/resolv.conf"
printf '# generated placeholder; minisd overwrites on ubuntu.start\nnameserver 8.8.8.8\nnameserver 8.8.4.4\n' \
  > "$STAGE/etc/resolv.conf"

if [[ ! -s "$STAGE/etc/hosts" ]]; then
  printf '127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' > "$STAGE/etc/hosts"
fi
printf 'minis\n' > "$STAGE/etc/hostname"

cat > "$STAGE/etc/profile.d/minis.sh" <<'EOF'
export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin"
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
export NO_COLOR=1
export PYTHONDONTWRITEBYTECODE=1
export GOMAXPROCS=2
umask 022
EOF

if [[ -f "$STAGE/etc/apt/sources.list" ]]; then
  sed -i 's|http://archive.ubuntu.com/ubuntu|http://ports.ubuntu.com/ubuntu-ports|g' "$STAGE/etc/apt/sources.list" || true
  sed -i 's|http://security.ubuntu.com/ubuntu|http://ports.ubuntu.com/ubuntu-ports|g' "$STAGE/etc/apt/sources.list" || true
fi
if [[ -f "$STAGE/etc/apt/sources.list.d/ubuntu.sources" ]]; then
  sed -i 's|http://archive.ubuntu.com/ubuntu|http://ports.ubuntu.com/ubuntu-ports|g' "$STAGE/etc/apt/sources.list.d/ubuntu.sources" || true
  sed -i 's|http://security.ubuntu.com/ubuntu|http://ports.ubuntu.com/ubuntu-ports|g' "$STAGE/etc/apt/sources.list.d/ubuntu.sources" || true
fi

VERSION_ID="$(sed -n 's/^VERSION_ID=//p' "$STAGE/etc/os-release" | tr -d '"')"
cat > "$STAGE/etc/minis/rootfs.json" <<EOF
{
  "distro": "ubuntu",
  "version": "${VERSION_ID}",
  "release": "${REL}",
  "arch": "arm64",
  "profile": "base",
  "revision": ${ROOTFS_REVISION},
  "preinstalled": "base-only",
  "source_url": "${BASE_URL}",
  "upstream_sha256": "${EXPECTED_BASE_SHA256}",
  "note": "python3/git/curl installed on device via ubuntu.provision"
}
EOF

echo "==> deterministic pack"
OUT="$DIST/ubuntu-arm64-rootfs.tar.gz"
TMP_TAR="$DIST/ubuntu-arm64-rootfs.tar"
find "$STAGE" -exec touch -h -d '@0' {} +
rm -f "$OUT" "$TMP_TAR"
tar --sort=name --mtime='@0' --owner=0 --group=0 --numeric-owner \
  --pax-option=delete=atime,delete=ctime -cf "$TMP_TAR" -C "$STAGE" .
gzip -n -9 -c "$TMP_TAR" > "$OUT"
rm -f "$TMP_TAR"
SHA="$(sha256sum "$OUT" | awk '{print tolower($1)}')"
VERSION="ubuntu-24.04-r${ROOTFS_REVISION}-${SHA:0:16}"
printf '%s  %s\n' "$SHA" "$(basename "$OUT")" > "$OUT.sha256"
SIZE="$(wc -c < "$OUT" | tr -d ' ')"
cat > "$DIST/ubuntu-arm64-rootfs.manifest.json" <<EOF
{
  "schemaVersion": 1,
  "file": "ubuntu-arm64-rootfs.tar.gz",
  "sha256": "$SHA",
  "bytes": $SIZE,
  "version": "$VERSION",
  "ubuntu": "$VERSION_ID",
  "release": "$REL",
  "arch": "arm64-v8a",
  "profile": "base",
  "rootfsRevision": $ROOTFS_REVISION,
  "provisionRevision": $PROVISION_REVISION,
  "source_url": "$BASE_URL",
  "checksums_url": "$SUMS_URL",
  "upstream_sha256": "$EXPECTED_BASE_SHA256",
  "requiredCommands": ["python3", "git", "curl"]
}
EOF
echo "==> $OUT"
echo "    version $VERSION"
echo "    sha256 $SHA"
echo "    bytes  $SIZE"
echo "    upstream $EXPECTED_BASE_SHA256"
