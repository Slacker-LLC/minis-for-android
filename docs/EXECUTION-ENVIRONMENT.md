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

The Ubuntu environment reuses the Android kernel. It is not a VM or a complete container security boundary. The active product runtime is Root-only; PRoot/Alpine and the former root broker are not active backends.

## Roles

### Android app

The app owns application/database state, provider/model state, tool registration and permissions, user approvals, execution checkpoints, session selection, path resolution, shell lifecycle, and runtime readiness/recovery orchestration.

### Root infrastructure

Root is used narrowly to validate/repair the rootfs, establish each shell's private mount namespace, apply explicit bind mounts, enter the chroot, perform the one-time legacy data migration, and start the fixed loopback network proxy.

`DirectRootRunner` is internal infrastructure. It is not an Agent/MCP command surface and must not accept model-controlled commands.

### Ubuntu guest

Guest commands run under the real Android App UID/GID after `setpriv` clears supplementary groups and Linux capabilities. A fixed identity such as `10000:10000` is not a valid runtime contract.

Public tool contracts use guest paths such as `/workspace`, `/memory`, `/skills`, `/shared`, `/home/minis`, and `/var/minis/...` rather than host paths.

## Host storage

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

Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` paths are migration sources only. After `.root-data-migrated-v1` is written they are not active bind sources.

## Session execution

With a valid `session_id`, execution must use the corresponding App-owned session backing. The same session view must be shared by terminal, Agent shell, attachments, links, offloads, browser data, and file access.

Each shell gets its own mount namespace. Namespace mounts disappear with that shell/process tree.

## Startup order

1. initialize App-owned paths;
2. verify Root authorization;
3. inspect/repair the Ubuntu rootfs;
4. verify `unshare`, `mount`, `chroot`, `setsid`, and guest `setpriv`;
5. ensure the Root network proxy is ready;
6. provision the Ubuntu userspace;
7. migrate legacy Root-owned user data if the marker is absent;
8. install/refresh the guest command bridge;
9. create per-shell namespace/binds/chroot;
10. drop to App UID/GID and start bash.

Readiness is fail-closed: a missing required stage must not silently fall back to another backend.

## Network and DNS

Guest HTTP/HTTPS uses `http://127.0.0.1:18787`, served by the standalone Root helper `minis-root-network-proxy`. The helper exists because some Android/VPN/BPF configurations can block outbound connections made by the non-root guest UID while Root egress remains usable.

The proxy is deliberately narrow: fixed loopback listener, HTTP absolute-form/CONNECT only, bounded headers/concurrency, ordinary private/loopback target rejection, and no command/file/RPC surface. The `198.18.0.0/15` Fake-IP range is explicitly allowed for VPN/TUN compatibility.

DNS is derived from Android network information with controlled fallbacks. CI proves parser/policy/build behavior; real VPN switching and OEM networking still require device verification.

## Rootfs lifecycle

The Ubuntu rootfs is runtime state, not user data. Runtime upgrade/recovery may replace `/data/adb/minis/rootfs`, but it must not replace App-owned workspace, sessions, memory, skills, shared data, MCP data, or home.

## SELinux and capability model

The project does not globally disable SELinux for compatibility. Root-provider identity does not prove every mount/capability operation is allowed. Ordinary Android APIs, Accessibility, Shizuku-compatible bridges, and root remain separate capability paths.

## Failure and recovery

Android/OEM policy may terminate the app or services. Side-effecting work must distinguish clean failure from unknown outcome and must not blindly replay an operation whose outcome is uncertain.
