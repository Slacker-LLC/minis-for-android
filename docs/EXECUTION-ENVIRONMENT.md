# Execution Environment

This document describes the current rooted-device execution contract for Minis for Android.

## Overview

```text
Android app
  ↓
ExecutionCoordinator / App-owned persistent shell
  ↓
UbuntuKernel / DirectRootRunner
  ↓
su → setsid → unshare -m
  ↓ explicit bind mounts
chroot /data/adb/minis/rootfs
  ↓
setpriv(real App UID/GID, clear groups, drop capabilities)
  ↓
Ubuntu 24.04 userspace
```

The Ubuntu environment reuses the Android kernel. It is not a VM or complete container security boundary. PRoot/Alpine and the former privileged broker are not active production backends.

## Roles

### Android app

The app owns application/database state, provider/model state, tool registration and permissions, user approvals, execution checkpoints, session selection, path resolution, shell lifecycle, and runtime readiness/recovery orchestration.

### Root infrastructure

Root is used narrowly for operations that genuinely require privilege: Root capability probing, rootfs validation/repair, private mount namespace creation, bind mounts, entering the chroot, and controlled one-time legacy-data migration.

`DirectRootRunner` is internal infrastructure and must never accept raw model-controlled commands. The local Agent may use the structured `root.shell` capability (`tool` basename plus `args`); trusted Android system-directory resolution, argv bounds, timeout/output limits, and process cleanup remain mandatory. `root.shell` is local-only and invisible to MCP; it is not a host-filesystem or generic RPC surface.

### Ubuntu guest

Guest commands run under the real Android App UID/GID after `setpriv` clears supplementary groups and Linux capabilities. A fixed identity such as `10000:10000` is not a valid runtime contract.

Public tool contracts use guest paths such as `/workspace`, `/memory`, `/skills`, `/shared`, `/home/minis`, and `/var/minis/...` instead of exposing host paths as the default model interface.

## Storage

Root-owned runtime state:

| Host path | Purpose |
|---|---|
| `/data/adb/minis/rootfs` | replaceable Ubuntu rootfs |

Active guest user data is App-owned and derived from `Context.filesDir`:

| Backing | Guest role |
|---|---|
| `minis/workspace` | global `/workspace` when explicitly non-session |
| `minis-sessions/<session_id>/...` | per-session workspace/attachments/offloads/browser |
| `minis-global/memory` | `/memory` |
| `minis-global/skills` | `/skills` |
| `minis-global/shared` | `/shared` |
| `minis-global/mcp-servers` | `/var/minis/mcp-servers` |
| `minis/home` | `/home/minis` |

Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` paths are migration sources only. After `.root-data-migrated-v1` is written, they are not active bind sources.

## Session execution

With a valid `session_id`, execution must use the corresponding App-owned session backing. Terminal, Agent shell, attachments, links, offloads, browser data, and file access must see the same session workspace.

Each shell gets its own mount namespace. Namespace mounts disappear with that shell/process tree.

## Startup/readiness

The Direct Ubuntu readiness path must, at minimum:

1. initialize App-owned paths;
2. verify required Root capability;
3. inspect/repair the Ubuntu rootfs;
4. verify `unshare`, `mount`, `chroot`, `setsid`, and guest `setpriv` prerequisites;
5. perform any required legacy-data migration before guest writes;
6. provision/refresh the Ubuntu userspace and guest command bridge;
7. create per-shell namespace/binds/chroot;
8. drop to the real App UID/GID and start bash.

Readiness is fail-closed: a missing required stage must not silently fall back to PRoot, Alpine, host shell, or Root guest execution.

## Network and DNS

Network compatibility is a **separate subsystem** from the Root/chroot execution chain.

The current implementation can provide guest HTTP/HTTPS through `http://127.0.0.1:18787`, served by the standalone `minis-root-network-proxy` helper. The name reflects the current privileged deployment mode, not a protocol requirement: HTTP/CONNECT proxying does not inherently require Root.

On devices where Android/VPN/BPF policy blocks outbound sockets made by the App-UID guest, the helper may run with privileged identity and establish the outbound connection on the guest's behalf. On a platform/configuration where App-UID guest networking works directly, Direct Ubuntu itself does not conceptually require a proxy.

The helper remains deliberately narrow: fixed loopback listener, HTTP absolute-form/CONNECT only, bounded headers/concurrency, ordinary private/loopback target rejection, `198.18.0.0/15` Fake-IP compatibility, and no shell/file/plugin/generic RPC surface.

DNS handling and route behavior must follow the active Android network. Host tests prove parser/policy/build behavior only; VPN switching, DNS selection, Fake-IP handling, and Android UID/BPF routing require real-device validation.

## Rootfs lifecycle

The Ubuntu rootfs is runtime state, not user data. Runtime upgrade/recovery may replace `/data/adb/minis/rootfs`, but must not replace App-owned workspace, sessions, memory, skills, shared data, MCP data, or home.

## Shutdown and recovery

Normal runtime Stop follows the same ownership chain in reverse: interactive
`TerminalSession` PTYs are stopped first, then the per-session
`ExecutionCoordinator` shells, then the standalone `RootNetworkProxy`. The
App-owned `NativeOffloadServer` and `GuestCommandBridge` are process-wide
owners; they are stopped during application teardown rather than per-session
runtime Stop.

Rootfs replacement, managed-rootfs configuration writes, and external-mount
reconciliation use the runtime lifecycle gate. They stop guest owners before
changing Root-owned state, and a new shell can be prepared only after the
maintenance operation releases that gate. A failed readiness check also
invalidates the shell generation so a command that raced the failure cannot
silently recreate an old shell.

If Android force-kills the App, normal Stop code may not run. The next Root-
authorized readiness pass reconciles only this runtime's Root-owned PID
markers, and signals a PID only while its current command line/environment
still identifies the expected helper. Backgrounding the App does not stop an
active Agent runtime: the foreground service keeps active work alive. A real
process death is recovered on the next launch instead.

## SELinux and capability model

The project does not globally disable SELinux for compatibility. Root-provider identity does not prove every mount/capability operation is permitted. Ordinary Android APIs, Accessibility, Shizuku-compatible bridges, Root, and the network-compatibility helper are separate capability paths.

## Failure and recovery

Android/OEM policy may terminate the app or services. Side-effecting work must distinguish clean failure from unknown outcome and must not blindly replay an operation whose outcome is uncertain.
