#!/usr/bin/env bash
# Build Ubuntu 24.04 LTS arm64 base rootfs (Q4 基础档 skeleton).
# Produces dist/ubuntu-arm64-rootfs.tar.gz (+ .sha256, manifest).
# Does NOT qemu-provision python/git; that is ubuntu.adminExec on device (Q3).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="${DIST:-$ROOT/dist}"
WORK="${WORK:-/tmp/minis-ubuntu-rootfs}"
REL="${REL:-24.04.3}"
BASE_NAME="ubuntu-base-${REL}-base-arm64.tar.gz"
BASE_URL="${BASE_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/${BASE_NAME}}"
SUMS_URL="${SUMS_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS}"

mkdir -p "$DIST" "$WORK"
cd "$WORK"

echo "==> download $BASE_NAME"
if [[ ! -f "$BASE_NAME" ]]; then
  curl -fL --retry 3 -o "$BASE_NAME" "$BASE_URL"
fi
if [[ ! -f SHA256SUMS ]]; then
  curl -fL --retry 3 -o SHA256SUMS "$SUMS_URL" || true
fi
if [[ -f SHA256SUMS ]]; then
  if grep -E "[ *]$BASE_NAME\$" SHA256SUMS > check.sha256; then
    sha256sum -c check.sha256
  else
    echo "warn: $BASE_NAME not listed in SHA256SUMS; skipping verify"
    cat SHA256SUMS || true
  fi
fi

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

# resolv.conf must be a regular file (ubuntu-base often ships a systemd symlink)
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

# Prefer Ubuntu ports for arm64 if the stock sources point at archive.ubuntu.com.
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
  "preinstalled": "base-only",
  "note": "python3/git/curl installed on device via ubuntu.adminExec (Q4)"
}
EOF

echo "==> pack"
OUT="$DIST/ubuntu-arm64-rootfs.tar.gz"
tar -czf "$OUT" -C "$STAGE" .
SHA="$(sha256sum "$OUT" | awk '{print $1}')"
printf '%s  %s\n' "$SHA" "$(basename "$OUT")" > "$OUT.sha256"
SIZE="$(wc -c < "$OUT" | tr -d ' ')"
cat > "$DIST/ubuntu-arm64-rootfs.manifest.json" <<EOF
{
  "file": "ubuntu-arm64-rootfs.tar.gz",
  "sha256": "$SHA",
  "bytes": $SIZE,
  "ubuntu": "$VERSION_ID",
  "release": "$REL",
  "arch": "arm64",
  "profile": "base"
}
EOF
echo "==> $OUT"
echo "    sha256 $SHA"
echo "    bytes  $SIZE"
