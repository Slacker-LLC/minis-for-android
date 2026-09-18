#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
android_dir="$repo_root/src/android"
config_file="$android_dir/app/provider-customization.properties"
gradlew="$android_dir/gradlew"
backup_file=""

if [[ -e "$config_file" ]]; then
  backup_file="$(mktemp)"
  cp "$config_file" "$backup_file"
fi

cleanup() {
  rm -f "$config_file"
  if [[ -n "$backup_file" ]]; then
    cp "$backup_file" "$config_file"
    rm -f "$backup_file"
  fi
}
trap cleanup EXIT

if [[ ! -x "$gradlew" ]]; then
  chmod +x "$gradlew"
fi

run_capability() {
  (
    cd "$android_dir"
    ./gradlew -q :app:printProviderCustomizationCapability \
      --no-parallel --max-workers=2 "$@"
  )
}

expect_required_failure() {
  local expected="$1"
  shift
  local output status
  set +e
  output="$(run_capability -PproviderCustomizationRequired=true "$@" 2>&1)"
  status=$?
  set -e
  printf '%s\n' "$output"
  if [[ "$status" -eq 0 ]]; then
    echo "required provider customization unexpectedly succeeded" >&2
    exit 1
  fi
  if ! grep -Fq "$expected" <<<"$output"; then
    echo "required provider customization failed for an unexpected reason" >&2
    exit 1
  fi
}

# 1) Public/unconfigured: build stays valid, but the capability and value are
# explicit rather than an empty-string third state.
rm -f "$config_file"
public_output="$(run_capability)"
printf '%s\n' "$public_output"
grep -Fxq "CLAUDE_OAUTH_CUSTOMIZATION_AVAILABLE=false" <<<"$public_output"
grep -Fxq "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT_STATE=NOT_AVAILABLE_IN_THIS_BUILD" <<<"$public_output"

# 2) Required/private mode: missing file, missing key, and blank value all fail
# during Gradle configuration before any Android task can execute.
rm -f "$config_file"
expect_required_failure \
  "provider-customization.properties is required when -PproviderCustomizationRequired=true."

cat >"$config_file" <<'EOF'
UNRELATED_PROVIDER_VALUE=fixture
EOF
expect_required_failure \
  "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT must be present and non-blank when -PproviderCustomizationRequired=true."

cat >"$config_file" <<'EOF'
ANTHROPIC_OAUTH_IDENTIFIER_PROMPT=
EOF
expect_required_failure \
  "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT must be present and non-blank when -PproviderCustomizationRequired=true."

# 3) Configured fixture: required mode succeeds and reports the capability as
# configured without printing the private customization value.
cat >"$config_file" <<'EOF'
ANTHROPIC_OAUTH_IDENTIFIER_PROMPT=ci-configured-fixture
EOF
configured_output="$(run_capability -PproviderCustomizationRequired=true)"
printf '%s\n' "$configured_output"
grep -Fxq "CLAUDE_OAUTH_CUSTOMIZATION_AVAILABLE=true" <<<"$configured_output"
grep -Fxq "ANTHROPIC_OAUTH_IDENTIFIER_PROMPT_STATE=CONFIGURED" <<<"$configured_output"

printf 'provider customization build-mode checks passed\n'
