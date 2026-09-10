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
