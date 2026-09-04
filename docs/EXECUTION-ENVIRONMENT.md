# Execution Environment

This document describes the current rooted-device execution contract for Minis for Android. Current implementation deviations are listed explicitly instead of being hidden behind the target architecture.

## Overview

```text
Android kernel
  ↓
Android app
  ↓ Unix socket RPC
minisd (root broker)
  ↓
private mount namespace
  ↓ explicit bind mounts
chroot
  ↓
Ubuntu 24.04 userspace
```

The Ubuntu environment reuses the Android kernel. It is not a VM and is not a complete container security boundary. The active product runtime is Root-only; PRoot/Alpine compatibility is not part of this execution model.

## Roles

### Android app

The app owns application/database state, provider/model state, tool registration and permissions, user approvals, execution checkpoints, session selection, and runtime readiness/recovery orchestration.

### `minisd`

`minisd` is the privileged root broker and part of the trusted computing base. It owns the bounded privileged RPC surface, prepares the canonical host-side persistent layout, establishes the private namespace/binds/chroot, and launches guest execution under the dynamically resolved App guest UID/GID.

A fixed UID/GID such as `10000:10000` is not a valid runtime contract.

### Ubuntu guest

The guest is Ubuntu 24.04 userspace entered through the chroot prepared by `minisd`. Public tool contracts use guest paths such as `/workspace`, `/memory`, `/skills`, `/shared`, and `/home/minis` rather than host paths.

## Canonical host layout

Root: `/data/adb/minis`.

| Host path | Purpose / guest mapping |
|---|---|
| `/data/adb/minis/rootfs` | replaceable Ubuntu rootfs |
| `/data/adb/minis/workspace` | global `/workspace` backing where a non-session flow explicitly uses it |
| `/data/adb/minis/sessions` | per-session backing |
| `/data/adb/minis/memory` | `/memory` |
| `/data/adb/minis/skills` | `/skills` |
| `/data/adb/minis/shared` | `/shared` |
| `/data/adb/minis/home` | `/home/minis` |
| `/data/adb/minis/run` | broker/runtime state |
| `/data/adb/minis/log` | broker/runtime logs |

Persistent user-data directories use the real App guest UID/GID and mode `0700`. Rootfs/run/log use their runtime-specific root ownership/modes.

Persistent sources must be canonical, contained, non-symlink escape paths and not tmpfs-backed. Alternate persistence backing must fail closed.

## Session execution

With a valid `session_id`, execution must use the session backing below `/data/adb/minis/sessions/<session_id>/`. Session data such as `workspace`, `attachments`, `offloads`, and `browser` stays under the same containment and ownership checks.

A terminal or helper path must not silently fall back to fixed `10000:10000` plus global `/workspace` when the selected chat/runtime session is session-scoped.

## Startup order

1. validate the fixed persistent parameters;
2. retire stale keeper state when required;
3. create/repair `/data/adb/minis` ownership and modes using the actual App identity;
4. validate containment/backing and reject invalid persistent sources;
5. validate or recover the Ubuntu rootfs;
6. start the keeper and establish namespace/binds/chroot;
7. expose READY only after the runtime readiness checks succeed.

The broker must remain independently startable when the rootfs needs repair.

## Rootfs lifecycle

The Ubuntu rootfs is runtime state, not user data. Runtime upgrade/recovery may replace `/data/adb/minis/rootfs`, but it must not replace `workspace`, `sessions`, `memory`, `skills`, `shared`, or `home`.

## Network and DNS

The guest uses Android's network stack. DNS must follow the currently effective Android network, including VPN-provided resolvers, and must refresh when the active network/VPN changes. Public DNS fallback is a separate policy decision and must not substitute for correctly inheriting the active VPN/system resolver.

## Current implementation deviations

At the 2026-09-04 audit baseline (`master` `6f10d1b3f413d37aca5c21465e8e71ef3eb12120`):

- #186: the terminal PTY path does not yet fully follow the dynamic guest identity/session workspace contract.
- #190: VPN-enabled devices can leave the Ubuntu guest without a usable current DNS resolver.

These are implementation gaps, not alternate supported architectures.

## SELinux and capability model

The project does not globally disable SELinux for compatibility. Root-provider identity does not prove every mount/capability operation is allowed. Ordinary Android APIs, Accessibility, Shizuku-compatible bridges, and root remain separate capability paths.

## Failure and recovery

Android/OEM policy may terminate the app or services. Side-effecting work must distinguish clean failure from unknown outcome and must not blindly replay an operation whose outcome is uncertain.
