#!/usr/bin/env bash
set -euo pipefail

BUNDLE="${1:?usage: verify-android-bundle.sh app.aab}"
: "${BUNDLETOOL_JAR:?Set BUNDLETOOL_JAR to bundletool-all.jar}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
umask 077
CHECK_DIR="$(mktemp -d)"
trap 'rm -rf "$CHECK_DIR"' EXIT
if command -v cygpath >/dev/null; then
  BUNDLE="$(cygpath -am "$BUNDLE")"
  BUNDLETOOL_JAR="$(cygpath -am "$BUNDLETOOL_JAR")"
  CHECK_DIR="$(cygpath -am "$CHECK_DIR")"
fi
java -jar "$BUNDLETOOL_JAR" validate --bundle="$BUNDLE" >/dev/null
SIGNING=()
if [[ -n "${RELEASE_KEYSTORE:-}" ]]; then
  printf '%s' "${RELEASE_STORE_PASSWORD:?}" > "$CHECK_DIR/store-password"
  printf '%s' "${RELEASE_KEY_PASSWORD:?}" > "$CHECK_DIR/key-password"
  SIGNING=("--ks=$RELEASE_KEYSTORE" "--ks-key-alias=${RELEASE_KEY_ALIAS:?}"
    "--ks-pass=file:$CHECK_DIR/store-password" "--key-pass=file:$CHECK_DIR/key-password")
fi
# Verify the actual APK generated from the bundle, including compressed JNI
# packaging required by the native runtime. The disposable APK is never distributed.
java -jar "$BUNDLETOOL_JAR" build-apks --bundle="$BUNDLE" \
  --output="$CHECK_DIR/bundle.apks" --mode=universal "${SIGNING[@]}"
unzip -p "$CHECK_DIR/bundle.apks" universal.apk > "$CHECK_DIR/universal.apk"
bash "$ROOT/scripts/verify-android-16k.sh" "$CHECK_DIR/universal.apk"
