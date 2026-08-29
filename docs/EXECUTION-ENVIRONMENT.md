# Execution Environment

This document defines the current Minis for Android rooted-device execution model.

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

The Ubuntu environment reuses the Android kernel. It is not a VM and does not boot a second kernel.

## Roles

### Android app

The app owns application/database state, provider/model state, tool registration and permissions, user approvals, execution checkpoints, and runtime readiness/recovery orchestration.

### `minisd`

`minisd` is the privileged root broker and part of the trusted computing base. It exposes a bounded structured RPC surface for preparing and operating the Ubuntu environment.

The broker uses a private Unix socket, peer identity checks, framed messages, bounded request/response sizes, a compile-time capability ceiling, and runtime policy that may restrict but not expand that ceiling.

`minisd` also owns the canonical host-side persistent filesystem layout used by the Linux guest. It prepares and validates those persistent sources before keeper mount-namespace creation.

### Ubuntu guest

The guest is Ubuntu 24.04 userspace entered through a chroot inside the mount namespace prepared by `minisd`.

Root establishes the namespace, mounts, and chroot. Agent commands run under the app guest UID rather than retaining unrestricted root identity.

## Canonical persistent host layout

The root-managed Minis area is `/data/adb/minis`.

| Host path | Purpose / guest mapping |
|---|---|
| `/data/adb/minis/rootfs` | Ubuntu rootfs |
| `/data/adb/minis/workspace` | global `/workspace` backing |
| `/data/adb/minis/sessions` | per-session workspace backing |
| `/data/adb/minis/memory` | `/memory` backing |
| `/data/adb/minis/skills` | `/skills` backing |
| `/data/adb/minis/shared` | `/shared` backing |
| `/data/adb/minis/home` | `/home/minis` backing |
| `/data/adb/minis/run` | broker/runtime state |
| `/data/adb/minis/log` | broker/runtime logs |

Persistent data directories (`workspace`, `sessions`, `memory`, `skills`, `shared`, and `home`) are prepared for the guest UID/GID with mode `0700`. The rootfs, run, and log directories use separate root/runtime modes defined by `src/native/minisd/src/layout.rs`.

The persistent start parameters are fixed. `ubuntu.start` rejects alternate values for rootfs, workspace, sessions, memory, skills, shared data, or HOME rather than silently changing persistence backing.

Persistent sources are validated before keeper startup and must not be tmpfs-backed. A layout that cannot be prepared or validated causes runtime startup to fail closed.

## Guest paths

The current canonical guest data paths are:

| Guest path | Backing |
|---|---|
| `/workspace` | `/data/adb/minis/workspace` or the selected session workspace |
| `/memory` | `/data/adb/minis/memory` |
| `/skills` | `/data/adb/minis/skills` |
| `/shared` | `/data/adb/minis/shared` |
| `/home/minis` | `/data/adb/minis/home` |

When a valid `session_id` is supplied, `minisd` creates that session below `/data/adb/minis/sessions/<session_id>/` and prepares its `workspace`, `attachments`, `offloads`, and `browser` directories under the same containment and ownership checks.

Path handling must reject traversal, NUL input, symlink escapes, and any session path that escapes the configured sessions root.

## Startup order

The rooted Ubuntu startup path is ordered so persistent bind sources exist before the keeper establishes its private mount namespace:

1. validate fixed persistent start parameters;
2. retire stale keeper state when needed;
3. create/repair the `/data/adb/minis` host layout with the required ownership and modes;
4. validate persistent backing, including the tmpfs rejection;
5. validate and prepare the Ubuntu rootfs;
6. spawn the keeper;
7. establish the private mount namespace, bind mounts, and chroot;
8. mark the runtime ready only after keeper readiness is reported.

## Shell behavior

The agent shell targets Ubuntu and executes through Bash. Public tool contracts should expose guest paths such as `/workspace`, not hard-coded host paths.

Shell execution is governed by timeout, dangerous-command policy, output limits/spill, persisted job state, approvals, and checkpoints where applicable.

## Rootfs

`scripts/build-ubuntu-rootfs.sh` downloads a pinned Ubuntu Base archive and verifies its SHA-256 digest before producing the rootfs artifact. Rootfs verification is part of CI and fails closed on checksum mismatch.

## Capability model

Root-provider identity does not prove that every privileged operation is available. Capability checks may depend on actual `su` behavior, Linux capabilities, SELinux context, mount namespace support, OEM policy, and Android version.

Shizuku-compatible bridges, ordinary Android APIs, Accessibility, and root are separate capability paths. Use the narrowest appropriate backend and probe support before execution.

## SELinux

The project does not disable SELinux globally for compatibility. Runtime setup must work under enforcing mode or report a structured unavailable/partial state.

## Failure and recovery

Android may kill the app or foreground services. Long-running work must rely on persisted jobs/checkpoints rather than assuming one process remains alive indefinitely.

A recovered keeper whose persistent bind provenance cannot be proven is not accepted as a known-good canonical layout; the runtime requires an explicit stop/start before Agent execution continues.

After uncertain termination of a side-effecting operation, recovery logic must not blindly repeat it. Unknown outcome remains distinct from clean failure.
