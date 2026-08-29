#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

CANONICAL='io.github.slackerllc.minis'
TEST_NAMESPACE='io.github.slackerllc.minis.test'
LEGACY_APP_ID='dev.openminispet.android'
LEGACY_PACKAGE='com.openminis.app'
LEGACY_PATH='com/openminis/app'
LEGACY_JNI='Java_com_openminis_app'
BUILD='src/android/app/build.gradle.kts'
MANIFEST='src/android/app/src/main/AndroidManifest.xml'

fail() {
  echo "android identity regression: $*" >&2
  exit 1
}

grep -Fq "namespace = \"$CANONICAL\"" "$BUILD" || fail 'canonical namespace missing'
grep -Fq "testNamespace = \"$TEST_NAMESPACE\"" "$BUILD" || fail 'canonical testNamespace missing'
grep -Fq "applicationId = \"$CANONICAL\"" "$BUILD" || fail 'canonical applicationId missing'
grep -Fq 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"' "$BUILD" || fail 'instrumentation runner changed unexpectedly'

for source_set in main test androidTest; do
  [[ ! -e "src/android/app/src/${source_set}/java/${LEGACY_PATH}" ]] || fail "legacy source root remains in $source_set"
done
[[ -d src/android/app/src/main/java/io/github/slackerllc/minis ]] || fail 'canonical main source root missing'
[[ -d src/android/app/src/test/java/io/github/slackerllc/minis ]] || fail 'canonical unit-test source root missing'

# Authorities remain derived from applicationId; process suffix and public deep-link
# contracts are intentionally stable across the package migration.
grep -Fq 'android:authorities="${applicationId}.shizuku"' "$MANIFEST" || fail 'Shizuku authority is no longer applicationId-derived'
grep -Fq 'android:authorities="${applicationId}.fileprovider"' "$MANIFEST" || fail 'FileProvider authority is no longer applicationId-derived'
grep -Fq 'android:process=":pet"' "$MANIFEST" || fail 'pet process suffix changed'
grep -Fq 'android:scheme="minis"' "$MANIFEST" || fail 'minis deep-link scheme changed'
grep -Fq 'android:host="app.minis.love"' "$MANIFEST" || fail 'HTTPS app-link host changed'

# Narrow guard: historical/legal/provenance documentation may name the legacy
# identities, but active Android/native/build/CI code must not regress to them.
legacy_regex="${LEGACY_APP_ID//./\\.}|${LEGACY_PACKAGE//./\\.}|${LEGACY_PATH//\//\\/}|${LEGACY_JNI}"
remaining="$(git grep -n -E "$legacy_regex" -- \
  src/android src/native scripts .github/workflows \
  ':!scripts/test-android-identity.sh' 2>/dev/null || true)"
if [[ -n "$remaining" ]]; then
  printf '%s\n' "$remaining" >&2
  fail 'legacy identity remains in active code/build paths'
fi

echo "Android identity guard passed: $CANONICAL"
