# Issue #34: exposing Minis as an MCP server — implementation record

> **Status (2026-09-10): historical implementation record.** Current behavior must be verified against the branch source/tests. This document does not define a second runtime/security authority and must not be used to reintroduce old Root/broker assumptions.

## Purpose

Issue #34 added configuration for the existing on-device MCP server instead of creating another protocol stack.

The intended exposure boundary remains deliberately narrow:

- stateless Streamable HTTP / JSON-RPC 2.0;
- loopback endpoint `http://127.0.0.1:18789/mcp`;
- Bearer token required on every request;
- explicit opt-in enablement;
- no configured token → fail closed;
- tool visibility filtered by the canonical `ToolPermissionManager` and token scope.

LAN/public binding, arbitrary bind hosts, TLS termination, and remote discovery are not implied by this implementation record.

## Credential behavior

Settings manages its own credential without deleting unrelated credentials. Rotation invalidates the previous bearer value. The generated Settings credential should use cryptographically secure randomness and an explicit tool scope; an empty legacy scope must not accidentally mean unrestricted MCP-visible access.

Selecting a tool in the Settings scope never overrides central permission policy. A centrally denied tool remains denied. In particular, generic Root execution such as `root.shell` is not an MCP capability.

## UI behavior

Settings may expose the local MCP server state, fixed loopback endpoint, credential generation/rotation/revocation, editable tool scope, and copyable client configuration.

The copied configuration is for a client that can actually reach the Android loopback namespace. `127.0.0.1` is intentionally not a LAN/public address.

## Failure/security behavior

- enabling without a usable credential fails closed;
- bind/start failures must remain observable without pretending the server is running;
- disabling stops the listener/supervisor/keep-alive path;
- bearer values must not be logged or returned through unrelated diagnostics;
- MCP exposure must not bypass Tool Registry, approvals, App-owned session semantics, or Direct Ubuntu security boundaries.

## Runtime relationship

The local MCP server is independent from the Direct Ubuntu Root/chroot path and independent from the loopback guest network compatibility proxy. Do not route MCP through `minis-root-network-proxy` and do not give MCP callers access to `DirectRootRunner`.

## Verification

Current source/tests and canonical CI are authoritative for token scope, loopback binding, tool filtering, rotation/revocation, Android compilation, lint, and packaging. A live external MCP client connection still requires device/integration evidence when claimed.

### Device evidence (2026-09-19, Xiaomi 24129PN74C / HyperOS / Android 37)

A live client connection was made through `adb forward tcp:18789` on a debug build, with the credential set through the debug RPC (`debug.mcp.settoken`, removed again afterwards by blanking `shared_prefs/minis_mcp_prefs.xml` via `run-as`). Observed contract, which a client has to follow exactly:

| Step | Observed |
|---|---|
| Start | `debug.mcp.start` → `{started:true}`; `debug.mcp.status` → `{running:true, configured:true, port:18789}`. Without a credential the start is **refused** (`{started:false}`) — the fail-closed rule holds on the device, not only in tests |
| Endpoint | `POST /mcp`; a request to `/` answers `404 {"error":"not found"}`. `Accept: application/json, text/event-stream` |
| Auth | `Authorization: Bearer <token>`; without it `401 {"error":"unauthorized"}` |
| `initialize` | `200`, `protocolVersion: 2025-06-18`, `capabilities.tools`, `serverInfo: {name: "minis", version: "0.1.0"}` |
| `tools/list` | `200`, **58 tools**; wire names are the underscored `apiName` form (`android_context`, `linux_file_list`, `android_alarm_set`, …) |
| Confirm gate | A tool that needs approval answers `error.code -32001`, `message "confirm_required"`, `data {confirm_id, expires_in_ms 120000}` |
| Approval | `debug.mcp.confirm.answer {confirm_id, method}` where **`method` is the canonical dotted name** (`android.context` for the wire name `android_context`) → `{approved:true, result:"OK"}`; passing the wire name yields `WRONG_METHOD` |
| Retry | The ticket must be sent as a **sibling of `name`/`arguments`** in the tool-call params (`{\"name\":…, \"arguments\":{}, \"confirm_id\":…}`) with **unchanged arguments** — the ticket is bound to caller + method + the canonical arguments digest — and then answers `200` with the tool result (verified: `isError:false`, keys `battery/foreground/location/network/ok/screen/time`) |
| Boundary refusal | `linux_file_list` without a usable path answers `isError:true`, `BAD_PARAMS: path is outside the Minis guest namespace or unavailable` |
