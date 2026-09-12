# Security Model

Minis for Android is a high-privilege Android agent project. Root access, MCP, Accessibility, provider credentials, package management, and device-control tools are security boundaries rather than convenience features.

## Security principles

1. Fail closed when identity, policy, path containment, signing, checksum, credential, rootfs, or direct-runtime prerequisites are not satisfied.
2. Keep privileged operations App-owned, narrow, and separate from Agent/model command input.
3. Reuse one canonical tool permission/runtime layer.
4. Treat local Agent, MCP callers, Android services, Root infrastructure, and network compatibility as distinct capability domains.
5. Require negative tests for security-sensitive behavior.
6. Do not disable SELinux or platform protections globally to make a feature work.

## Credentials and local services

Provider API keys, OAuth tokens, MCP tokens, DebugServer tokens, signing material, and other secrets must not be committed to the repository or returned through diagnostic APIs. Secure-storage failures must not downgrade secrets to plaintext.

DebugServer remains loopback-bound and debug-only. The local MCP server binds loopback by default, requires bearer authentication, filters tools by caller policy, and must not expose arbitrary Root shell or unrestricted host filesystem access.

## Direct Root boundary

There is no production privileged broker or generic Root RPC in the current architecture.

`DirectRootRunner` is an internal launcher used by trusted Android runtime code for operations that require privilege, such as Root capability probing, rootfs repair, mount namespace/bind/chroot setup, controlled one-time migration, and tightly scoped maintenance. A local Agent may also request the structured `root.shell` tool: it supplies only an executable basename and argv, which are resolved from trusted Android system directories and bounded before execution. Agent, MCP, Provider, and model output must not flow as a raw command string into its script input.

`root.shell` is local-only and hidden from MCP; it has no `command` string, host-filesystem API, or generic RPC transport. Guest execution is not Root execution: after Root establishes the namespace/chroot, `setpriv` switches to the real App UID/GID, clears supplementary groups, and drops Linux capabilities before bash starts.

Root-provider identity is diagnostic only. `uid=0` does not prove SELinux, mount, namespace, or capability operations are permitted.

## Network compatibility proxy

The standalone `minis-root-network-proxy` is a separate network compatibility component. Its current name/deployment reflects that it may run with privileged identity on Android; **HTTP/CONNECT proxying itself is not a Root primitive and is not part of the chroot security boundary.**

Current hard limits:

- listener fixed to `127.0.0.1:18787`;
- HTTP absolute-form and CONNECT only;
- no shell, filesystem, plugin, configuration, or generic RPC API;
- bounded request headers and connection concurrency;
- ordinary loopback/private/link-local/broadcast destinations rejected;
- `198.18.0.0/15` retained only for explicit VPN Fake-IP compatibility;
- controlled Android DNS discovery/fallback behavior.

On devices where App-UID guest sockets work correctly, the Direct Ubuntu architecture does not conceptually require privileged proxy egress. On devices where Android VPN/BPF/UID policy blocks those sockets, privileged helper egress may be used as a compatibility mechanism. This distinction must remain explicit in code comments and documentation.

## Ubuntu chroot boundary

The Ubuntu guest is not a VM or complete container security boundary. It shares the Android kernel.

Active user data is App-owned; `/data/adb/minis/rootfs` is Root-owned replaceable runtime state. Per-session data must bind only from its corresponding App-owned session backing. SAF external locations remain a separate grant-based trust domain and are bound read-only when the effective grant is read-only.

Host/guest mounts are explicit, guest paths are contained, global SELinux is not disabled, and rootfs input is pinned/checksum/manifest verified before use.

## File and path boundaries

File and mount paths must reject traversal, NUL input, canonical escape, and relevant symlink escape. App-owned data, App cache/staging, SAF locations, and Root-owned rootfs are distinct storage domains and must not be silently substituted for one another.

Large tool output should be bounded or spilled to controlled storage.

## Tool authorization

All Agent/MCP tools enter the canonical tool registry and runtime permission layer. Unknown tools default deny; local-only tools are not exposed to MCP; UI checks are not execution authorization; side-effecting tools retain checkpoint/approval/recovery semantics.

No tool may convert internal Direct Ubuntu Root infrastructure into a model-controlled shell.

## Network transport

Cloud providers, OAuth, update metadata, and credential-bearing requests should use HTTPS. Local/private HTTP provider endpoints require explicit application policy; broad public cleartext must not become an implicit fallback. Credential-bearing flows must not follow HTTPS-to-HTTP downgrade redirects.

The local compatibility proxy is an internal guest-egress path, not permission to expose a remote proxy service.

## Release security

Release signing is fail-closed. Debug signing must never be accepted as a production release fallback. Runtime packaging verifies the rootfs-only manifest and network helper independently; obsolete privileged-broker binaries/socket contracts are rejected by regression guards.

APKs/AABs are build artifacts and are not committed to Git.

## Process death and uncertain outcomes

Recovery must distinguish operation never started, clean failure, completed, and outcome unknown. Unknown side effects must not be blindly retried.

## Device-verification boundary

CI and host tests can prove payload integrity, native proxy policy/build behavior, package contents, and Android compile/unit behavior. They cannot prove Root authorization, SELinux behavior, real mount semantics, VPN/DNS/BPF/Fake-IP behavior, or OEM process lifecycle on a physical device. Those claims require explicit device tests.
