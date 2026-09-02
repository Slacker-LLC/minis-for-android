# Android DebugServer API

This document describes the current **Minis for Android** DebugServer. It is a developer-only JSON-RPC surface for inspecting and exercising a debug build.

The runtime method catalogue returned by `rpc.discover` and the Android source are authoritative. Do not copy method lists from upstream iOS documentation.

## Availability

DebugServer exists only in `BuildConfig.DEBUG` builds. `MinisApp` does not start it in release builds, and release builds do not register the shell-side `minis-debug` offload handler.

Default endpoint:

```text
http://127.0.0.1:5321
```

The server binds **device loopback only**. It is not a LAN service and should not be proxied to a public interface.

For host-side development, use ADB forwarding:

```bash
adb forward tcp:5321 tcp:5321
```

## Authentication

Every request requires the per-install DebugServer token, including requests that arrive through `adb forward`.

Read the token from an authorized development device:

```bash
TOK=$(adb shell run-as llc.slacker.minis cat files/debug_server_token)
```

Send it using either:

```http
X-Minis-Token: <token>
```

or:

```http
Authorization: Bearer <token>
```

The server fails closed when the expected token is empty, missing, or incorrect.

## JSON-RPC

RPC requests use JSON-RPC 2.0 over HTTP POST:

```bash
curl -s http://localhost:5321/ \
  -H "X-Minis-Token: $TOK" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"debug.appInfo","params":{}}'
```

Request shape:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "rpc.discover",
  "params": {}
}
```

Successful responses use the normal JSON-RPC `result` envelope. Invalid requests and method failures use the JSON-RPC `error` envelope.

## Runtime discovery

Do not maintain a second handwritten list of RPC methods in this document. Ask the running build:

```bash
curl -s http://localhost:5321/ \
  -H "X-Minis-Token: $TOK" \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"rpc.discover","params":{}}'
```

`rpc.discover` returns the current platform/build metadata plus the supported method catalogue, parameter schemas, return descriptions, and examples. The catalogue is generated from `DebugMethodRegistry` and is intended to stay aligned with `DebugRPCHandler.dispatch`.

The current surface includes development and inspection namespaces for areas such as:

- app/debug information and screenshots;
- logs and crash reports;
- browser inspection;
- provider/model configuration;
- chat/session operations;
- goals, todos, plans, jobs, approvals, and feedback;
- skills, memory, environments, and mounted storage;
- MCP configuration and local MCP lifecycle;
- scheduled tasks;
- Android/offload debug harness operations.

Exact method names and fields must come from `rpc.discover` on the build being tested.

## Bootstrap routes

Authenticated GET routes expose machine-readable schema and optional debug-skill assets:

```text
GET /schema
GET /
GET /skill
GET /skill/examples/python
GET /skill/examples/curl
```

These routes use the same token gate as RPC POST requests. They are not public or unauthenticated bootstrap endpoints.

Example:

```bash
curl -s http://localhost:5321/skill \
  -H "X-Minis-Token: $TOK" \
  -H 'Accept: text/markdown'
```

The optional generated skill assets are staged only into the Android debug source set by `scripts/gen_debug_skill_android.sh` when the local skill source exists.

## Security constraints

DebugServer is powerful enough to inspect application state and invoke development-only operations. The security contract is therefore:

```text
DEBUG build only
  + 127.0.0.1 bind only
  + per-install token on every request
  + no release registration of debug-only handlers
```

Do not weaken the loopback bind, remove the token requirement, log the token, bundle debug skill assets in release builds, or expose DebugServer through a general-purpose tunnel.

The request parser also bounds header/body sizes and applies socket timeouts; changes to those limits are security-sensitive and should include negative tests.

## Source of truth

Relevant Android sources:

```text
src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt
src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt
src/android/app/src/main/java/com/openminis/app/debug/DebugMethodRegistry.kt
src/android/app/src/main/java/com/openminis/app/MinisApp.kt
scripts/gen_debug_skill_android.sh
```

When this document disagrees with a running debug build, use `rpc.discover` and the source for that commit, then fix the documentation.
