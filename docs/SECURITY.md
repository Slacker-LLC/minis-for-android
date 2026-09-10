# Security Model

Minis for Android is a high-privilege Android agent project. Root access, MCP, Accessibility, provider credentials, package management, and device-control tools are security boundaries rather than convenience features.

## Security principles

1. Fail closed when identity, policy, path containment, signing, checksum, credential, rootfs, or direct-runtime prerequisites are not satisfied.
2. Keep privileged operations App-owned, narrow, and separate from Agent/model command input.
3. Reuse one canonical tool permission/runtime layer.
4. Treat local Agent, MCP callers, Android services, and internal Root infrastructure as distinct callers.
5. Require negative tests for security-sensitive behavior.
6. Do not trade away SELinux or platform security globally to make a feature work.

## Credentials and local services

Provider API keys, OAuth tokens, MCP tokens, DebugServer tokens, signing material, and other secrets must not be committed to the repository or returned through diagnostic APIs. Secure storage failures must not downgrade secrets to plaintext.

DebugServer remains loopback-bound and debug-only. The local MCP server binds loopback by default, requires bearer authentication, filters tools by caller policy, and must not expose arbitrary Root shell or unrestricted host filesystem access.

## Direct Root boundary

There is no production root broker or generic Root RPC in the current architecture.

`DirectRootRunner` is an internal launcher used by trusted Android runtime code for fixed or programmatically constructed infrastructure operations: Root probing, rootfs repair, mount namespace/bind/chroot setup, one-time legacy migration, and tightly scoped runtime maintenance. Agent, MCP, Provider, and model output must not flow directly into its script input.

Guest execution is not Root execution. After Root establishes the namespace and chroot, `setpriv` switches to the real App UID/GID, clears supplementary groups, and drops inheritable/ambient/bounding capabilities before bash is started.

Root-provider identity is diagnostic only. `uid=0` does not prove that SELinux, mount, or Linux capabilities permit a requested operation.

## Root network proxy

The standalone `minis-root-network-proxy` is part of the trusted runtime infrastructure but is intentionally single-purpose:

- listener fixed to `127.0.0.1:18787`;
- HTTP absolute-form and CONNECT only;
- no shell, filesystem, plugin, configuration, or generic RPC API;
- bounded request header and concurrent connection counts;
- ordinary loopback/private/link-local/broadcast destinations rejected;
- `198.18.0.0/15` retained only for explicit VPN Fake-IP compatibility;
- Android DNS discovery and controlled fallback resolution.

This helper preserves guest network compatibility without recreating a privileged broker.

## Ubuntu chroot boundary

The Ubuntu guest is not a VM or complete container security boundary. It shares the Android kernel.

Active user data is App-owned; `/data/adb/minis/rootfs` is Root-owned replaceable runtime state. Per-session data must bind only from its corresponding App-owned session backing. SAF external locations remain a separate grant-based trust domain and are bound read-only when the effective grant is read-only.

Host/guest mounts are explicit, guest paths are contained, global SELinux is not disabled, and rootfs input is pinned/checksum/manifest verified before use.

## File and path boundaries

File and mount paths must reject traversal, NUL input, canonical escape, and relevant symlink escape. App-owned data, App cache/staging, SAF locations, and Root-owned rootfs are distinct storage domains and must not be silently substituted for one another.

Large tool output should be bounded or spilled to controlled storage instead of exhausting memory.

## Tool authorization

All Agent/MCP tools enter the canonical tool registry and runtime permission layer. Unknown tools default deny; local-only tools are not exposed to MCP; UI checks are not execution authorization; side-effecting tools retain checkpoint/approval/recovery semantics.

No tool is allowed to turn direct-runtime Root infrastructure into a model-controlled shell.

## Network transport

Cloud providers, OAuth, update metadata, and credential-bearing requests should use HTTPS. Local/private HTTP provider endpoints require explicit application policy; broad public cleartext must not become an implicit fallback. Credential-bearing flows must not follow HTTPS-to-HTTP downgrade redirects.

The local Root network proxy is an internal guest egress path, not permission to expose a remote proxy service.

## Release security

Release signing is fail-closed. Debug signing must never be accepted as a production release fallback. Runtime packaging verifies the rootfs-only manifest and standalone Root network proxy independently; obsolete broker binaries/socket contracts are rejected by regression guards.

APKs/AABs are build artifacts and are not committed to Git.

## Process death and uncertain outcomes

Recovery must distinguish operation never started, clean failure, completed, and outcome unknown. Unknown side effects must not be blindly retried.

## Device-verification boundary

CI and host tests can prove payload integrity, Rust policy, build compatibility, package contents, and Android compile/unit behavior. They cannot prove Root authorization, SELinux behavior, real mount semantics, VPN switching, or OEM process lifecycle on a physical device. Those claims require explicit device tests.
