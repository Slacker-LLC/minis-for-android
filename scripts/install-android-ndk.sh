#!/usr/bin/env bash
# Installs the pinned Android NDK with sdkmanager and exports ANDROID_NDK_HOME for later CI steps.
# The download from dl.google.com sometimes arrives truncated ("Error on ZipFile unknown archive"),
# so a failed or partial install is wiped and retried instead of failing the whole job.
set -euo pipefail

NDK_VERSION="${MINIS_NDK_VERSION:-28.2.13676358}"
ANDROID_HOME="${ANDROID_HOME:?ANDROID_HOME is not set}"
ndk_dir="$ANDROID_HOME/ndk/$NDK_VERSION"
clang="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
attempts=5

for attempt in $(seq 1 "$attempts"); do
  if [ -x "$clang" ] && [ -f "$ndk_dir/source.properties" ]; then
    break
  fi
  echo "Installing NDK $NDK_VERSION (attempt $attempt/$attempts)"
  rm -rf "$ndk_dir" "$ANDROID_HOME/.temp"
  if ! "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$NDK_VERSION"; then
    echo "sdkmanager failed on attempt $attempt" >&2
  fi
  if [ ! -x "$clang" ] && [ "$attempt" -lt "$attempts" ]; then
    sleep $((attempt * 10))
  fi
done

test -x "$clang" || { echo "NDK $NDK_VERSION is not usable after $attempts attempts" >&2; exit 1; }

if [ -n "${GITHUB_ENV:-}" ]; then
  echo "ANDROID_NDK_HOME=$ndk_dir" >> "$GITHUB_ENV"
fi
