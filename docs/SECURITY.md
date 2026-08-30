# Security Model

Minis for Android is a high-privilege Android agent project. Root access, MCP, Accessibility, provider credentials, package management, and device-control tools are treated as security boundaries rather than convenience features.

The public repository is source-first. A locally built APK should not be assumed production-ready merely because it compiles.

## Security principles

1. Fail closed when identity, policy, path containment, signing, checksum, or credential requirements are not satisfied.
2. Keep privileged operations structured and narrow.
3. Reuse one canonical tool permission/runtime layer.
4. Treat local agent, MCP callers, Android services, and root broker clients as distinct callers.
5. Require negative tests for security-sensitive behavior.
6. Do not trade away SELinux or platform security globally to make a feature work.

## Credentials

Provider API keys, OAuth tokens, MCP tokens, DebugServer tokens, signing material, and other secrets must not be committed to the repository or returned through diagnostic APIs.

Encrypted storage must fail closed if secure initialization is unavailable. Do not silently downgrade secrets to plaintext storage.

Build-time provider customization is separate from runtime secrets. If a required private integration value is absent, the build/runtime should expose an explicit unavailable state instead of failing only after the user enters the flow.

## Local services

### DebugServer

DebugServer is a development surface. It must remain loopback-bound and debug-only; production artifacts must not accidentally expose debug RPC behavior.

### MCP server

The local MCP server:

- binds to loopback by default;
- requires bearer authentication;
- filters tool visibility and execution by caller/token policy;
- can require user confirmation for sensitive calls;
- must not expose arbitrary root shell or unrestricted host filesystem access to remote callers.

MCP is an integration surface into the existing Android runtime, not a second authority for sessions, tools, or data.

## `minisd` root broker

`minisd` is part of the trusted computing base.

Security model:

```text
client
  ↓ peer identity / framed RPC
minisd
  ↓ compile-time capability ceiling
runtime policy (restrict only)
  ↓ structured privileged operation
Android / namespace / mount / chroot
```

Required properties include:

- private Unix socket;
- peer identity checks;
- explicit request/response framing and size limits;
- concurrent client handling without one slow client blocking the accept loop;
- no runtime policy mechanism capable of expanding the compile-time command/method ceiling;
- bounded stdout/stderr collection while continuously draining child pipes;
- process-tree termination on timeout;
- structured method allowlists rather than arbitrary remote command execution.

Root-provider identity is diagnostic only. `uid=0` does not prove that SELinux, mount, or Linux capabilities permit a requested operation.

## Ubuntu chroot boundary

The Ubuntu guest is not a VM or a complete container security boundary. It shares the Android kernel.

Security rules:

- root establishes the namespace/chroot, but arbitrary agent code runs with the app guest UID;
- persistent guest-data sources are fixed under `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`;
- `minisd` prepares and validates persistent bind sources before keeper mount-namespace creation;
- persistent data directories use the guest UID/GID with mode `0700`;
- non-canonical or tmpfs-backed persistent sources fail closed;
- host/guest mounts are explicit;
- guest paths are canonically contained;
- unnecessary host paths are not made writable;
- mount/chroot operations do not disable global SELinux;
- rootfs input is pinned and SHA-256 verified before use.

## File and path boundaries

File and mount paths must reject traversal, NUL input, canonical escape, and symlink escape where relevant.

SAF-granted external locations and the root-managed `/data/adb/minis` Agent-data sources are separate trust domains. Access across those domains must use the intended Android/root-broker boundary rather than assuming direct path equivalence.

Large tool output should be bounded or spilled to controlled storage instead of being allowed to exhaust memory or IPC buffers.

## Tool authorization

All agent/MCP tools must enter the canonical tool registry and runtime permission layer.

Rules:

- unknown tools default to deny;
- local-only tools are not exposed to MCP callers;
- sensitive remote tools use confirmation or explicit denial as appropriate;
- UI checks are not security boundaries; execution entry points re-check authorization;
- side-effecting tools use checkpoints/approval/recovery semantics where needed.

## Android privilege model

Ordinary Android APIs, Accessibility, Shizuku-compatible bridges, and root are separate capabilities.

Prefer ordinary Android APIs when they can perform the operation. Probe privileged backends only when needed, and return structured unavailable/partial states when a capability is missing.

System-granted roles and permissions such as Accessibility, overlay, microphone, SAF, assistant role, and battery exemptions remain explicit user/system decisions.

## Network transport

Cloud providers, OAuth, update metadata, and credential-bearing requests should use HTTPS.

Local/private HTTP provider endpoints may be supported only through explicit application policy; broad public cleartext endpoints must not become an implicit fallback.

Credential-bearing flows must not follow HTTPS-to-HTTP downgrade redirects.

Network-policy changes require tests for allowed local endpoints, rejected public cleartext endpoints, and downgrade redirects.

## Release security

Release signing is fail-closed. Debug signing must never be accepted as a production release fallback.

Repository CI checks release-signing failure paths, Debug/Release lint, Debug/Release packaging, and the final release APK.

APKs/AABs are build artifacts and are not committed to Git.

## Process death and uncertain outcomes

Android may terminate the process or foreground service while work is in progress.

Recovery must distinguish:

- operation never started;
- operation failed cleanly;
- operation completed;
- operation outcome is unknown.

An unknown side-effecting outcome must not be retried blindly.

## Security test expectations

Security-sensitive changes should include cases equivalent to:

```text
allowed request        -> succeeds
unauthorized request   -> denied
invalid input          -> denied
boundary-sized input   -> bounded
IPC/process failure    -> fail closed
restart/recovery       -> no duplicate side effect
```

Use the live GitHub Issues list for open hardening work instead of embedding volatile issue status in this document.
