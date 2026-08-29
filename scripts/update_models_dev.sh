#!/usr/bin/env bash
# Refresh the models.dev catalog consumed by the Android application.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="$REPO_ROOT/src/android/app/src/main/assets/models-dev-api.json"

curl --fail --silent --show-error --location \
  https://models.dev/api.json \
  -o "$OUTPUT"

echo "Updated Android models.dev API data: $OUTPUT"
