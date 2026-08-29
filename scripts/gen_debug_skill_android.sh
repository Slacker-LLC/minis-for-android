#!/usr/bin/env bash
# Stage optional DebugServer skill assets into the Android debug-only source set.
#
# The DebugServer is a development surface only. It binds 127.0.0.1:5321 and
# requires the per-install token for every request, including requests forwarded
# through adb. Generated assets live under src/debug and therefore do not ship in
# release APKs.
#
# DO NOT COMMIT the generated src/debug output.
set -eu

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILL_DIR="$REPO_ROOT/.claude/skills/debug-server"
OUT_DIR="$REPO_ROOT/src/android/app/src/debug/assets/debug-skill"
APP_ID="io.github.slackerllc.minis"

mkdir -p "$OUT_DIR/examples"

if [ -f "$SKILL_DIR/SKILL.md" ]; then
  cp -f "$SKILL_DIR/SKILL.md" "$OUT_DIR/SKILL.md"
else
  printf '# DebugServer skill unavailable\n\nSKILL.md was not found at build time.\n' > "$OUT_DIR/SKILL.md"
fi

cat > "$OUT_DIR/examples/minis_rpc_android.py" <<'PYCLIENT'
#!/usr/bin/env python3
"""Minis for Android DebugServer client (stdlib only).

Usage:
    python3 minis_rpc_android.py [--host HOST:PORT] [--token TOKEN] <method> [params-json]

Setup:
    adb forward tcp:5321 tcp:5321
    export MINIS_DEBUG_TOKEN="$(adb shell run-as io.github.slackerllc.minis cat files/debug_server_token)"

Example:
    python3 minis_rpc_android.py debug.appInfo '{}'

Security contract:
  - DebugServer listens only on 127.0.0.1:5321 on the Android device.
  - Requests use plaintext JSON-RPC 2.0 over HTTP on that loopback-only socket.
  - Every request requires the per-install token, including adb-forwarded traffic.
  - Supply the token through X-Minis-Token or Authorization: Bearer.
"""

import json
import os
import sys
import urllib.error
import urllib.request


def call(host: str, method: str, params: dict, token: str | None) -> dict:
    if not token:
        raise SystemExit(
            "DebugServer token is required. Read it with:\n"
            "  adb shell run-as io.github.slackerllc.minis cat files/debug_server_token\n"
            "then pass --token or set MINIS_DEBUG_TOKEN."
        )

    body = json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": method, "params": params,
    }).encode()
    headers = {
        "Content-Type": "application/json",
        "X-Minis-Token": token,
    }
    req = urllib.request.Request(
        f"http://{host}/",
        data=body,
        headers=headers,
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode(errors="replace")
        if exc.code == 401:
            raise SystemExit(
                "401 Unauthorized — the DebugServer token is missing or incorrect.\n"
                "Read it with:\n"
                "  adb shell run-as io.github.slackerllc.minis cat files/debug_server_token\n"
                f"server said: {payload}"
            )
        raise SystemExit(f"HTTP {exc.code}: {payload}")


def main() -> None:
    args = sys.argv[1:]
    host = "localhost:5321"
    token = os.environ.get("MINIS_DEBUG_TOKEN")

    while args and args[0].startswith("--"):
        if args[0] == "--host":
            if len(args) < 2:
                raise SystemExit("--host requires HOST:PORT")
            host = args[1]
            args = args[2:]
        elif args[0] == "--token":
            if len(args) < 2:
                raise SystemExit("--token requires a token")
            token = args[1]
            args = args[2:]
        else:
            raise SystemExit(f"unknown flag {args[0]}")

    if not args:
        raise SystemExit(__doc__)

    method = args[0]
    params = json.loads(args[1]) if len(args) > 1 else {}
    print(json.dumps(call(host, method, params, token), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
PYCLIENT

cat > "$OUT_DIR/examples/curl.md" <<'CURLDOC'
# Minis for Android DebugServer — curl quickstart

The debug server binds only to device loopback on port **5321**. Every request
requires the per-install token.

```bash
adb forward tcp:5321 tcp:5321
TOK=$(adb shell run-as io.github.slackerllc.minis cat files/debug_server_token)

curl -s http://localhost:5321/ \
  -H "X-Minis-Token: $TOK" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"debug.appInfo","params":{}}'
```

The bootstrap routes are authenticated too:

```bash
curl -s http://localhost:5321/skill \
  -H "X-Minis-Token: $TOK" \
  -H 'Accept: text/markdown'

curl -s http://localhost:5321/skill/examples/python \
  -H "X-Minis-Token: $TOK" \
  > minis_rpc_android.py
```

Do not expose port 5321 to a LAN or public interface. Use `adb forward` for host
access during development.
CURLDOC

echo "[gen_debug_skill_android] staged debug-only assets -> $OUT_DIR"
