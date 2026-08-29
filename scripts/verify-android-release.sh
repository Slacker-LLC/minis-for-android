#!/usr/bin/env bash
set -euo pipefail

APK="${1:-}"
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "usage: $0 path/to/app-release.apk" >&2
  exit 2
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME is required" >&2
  exit 2
fi

APKSIGNER="$(find "$ANDROID_SDK_ROOT/build-tools" -type f -name apksigner -print | sort -V | tail -n1)"
if [[ -z "$APKSIGNER" || ! -x "$APKSIGNER" ]]; then
  echo "apksigner not found" >&2
  exit 2
fi

CERT="$($APKSIGNER verify --print-certs "$APK")"
printf '%s\n' "$CERT"
if grep -Fq 'CN=Android Debug' <<<"$CERT"; then
  echo "release APK is signed with an Android debug certificate" >&2
  exit 1
fi

if unzip -Z1 "$APK" | grep -Eq '(^|/)debug-skill(/|$)'; then
  echo "release APK contains debug-server skill assets" >&2
  exit 1
fi

if ! unzip -Z1 "$APK" | grep -Fxq 'assets/runtime-distribution.json'; then
  echo "release APK is missing assets/runtime-distribution.json" >&2
  exit 1
fi

RUNTIME_MANIFEST_JSON="$(unzip -p "$APK" assets/runtime-distribution.json)"
export RUNTIME_MANIFEST_JSON
python3 - <<'PY'
import json
import os
import re

try:
    obj = json.loads(os.environ["RUNTIME_MANIFEST_JSON"])
except Exception as exc:
    raise SystemExit(f"invalid packaged runtime-distribution.json: {exc}")

required = {
    "schemaVersion": 1,
    "protocolVersion": 1,
    "layoutVersion": 2,
    "abi": "arm64",
}
for key, expected in required.items():
    if obj.get(key) != expected:
        raise SystemExit(f"runtime distribution {key}={obj.get(key)!r}, expected {expected!r}")

if not isinstance(obj.get("runtimeVersion"), str) or not obj["runtimeVersion"]:
    raise SystemExit("runtime distribution runtimeVersion missing")
if not isinstance(obj.get("provisionRevision"), int) or obj["provisionRevision"] <= 0:
    raise SystemExit("runtime distribution provisionRevision invalid")

ready = obj.get("distributionReady") is True
if ready:
    sha = re.compile(r"^[0-9a-fA-F]{64}$")
    for component in ("minisd", "rootfs"):
        digest = (obj.get(component) or {}).get("sha256", "")
        if not sha.fullmatch(str(digest)):
            raise SystemExit(f"distributionReady=true but {component}.sha256 is invalid")
    print("runtime distribution manifest: deployable")
else:
    print("runtime distribution manifest: fail-closed (external release artifacts not injected)")
PY
unset RUNTIME_MANIFEST_JSON

# R8 should eliminate the debug RPC server because every startup/reference is
# guarded by BuildConfig.DEBUG=false in release. Scan every DEX for the source
# descriptor/string as a regression backstop.
while IFS= read -r dex; do
  if unzip -p "$APK" "$dex" | strings | grep -Fq 'com/openminis/app/debug/DebugServer'; then
    echo "release APK still contains DebugServer in $dex" >&2
    exit 1
  fi
done < <(unzip -Z1 "$APK" | grep -E '^classes([0-9]+)?\.dex$')

echo "release APK verification passed"
