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

The app owns agent/session state, provider/model state, tool registration and permissions, workspace-related host paths, user approvals, execution checkpoints, and runtime readiness/recovery logic.

### `minisd`

`minisd` is the privileged root broker and part of the trusted computing base. It exposes a bounded structured RPC surface for preparing and operating the Ubuntu environment.

The broker uses a private Unix socket, peer identity checks, framed messages, bounded request/response sizes, a compile-time capability ceiling, and runtime policy that may restrict but not expand that ceiling.

### Ubuntu guest

The guest is Ubuntu 24.04 userspace entered through a chroot inside the mount namespace prepared by `minisd`.

Root establishes the namespace, mounts, and chroot. Agent commands run under the app guest UID rather than retaining unrestricted root identity.

## Host and guest paths

The rootfs lives under the root-managed Minis area:

```text
/data/adb/minis/rootfs
```

On the current `master` baseline, application-owned workspace/memory/skills/shared directories are resolved from the app's private files directory and bind-mounted into the guest. The exact host prefix is determined from `Context.filesDir` at runtime.

Canonical guest mappings include:

| Guest path | Purpose |
|---|---|
| `/workspace` | main agent workspace |
| `/var/minis/workspace` | workspace alias |
| `/var/minis/attachments` | attachments |
| `/var/minis/offloads` | tool/native offloads |
| `/var/minis/browser` | browser data |
| `/memory` / `/var/minis/memory` | persistent memory |
| `/skills` / `/var/minis/skills` | skills |
| `/shared` / `/var/minis/shared` | shared files |

Path resolution must enforce canonical containment and reject traversal, NUL input, and escape attempts.

## Shell behavior

The agent shell targets Ubuntu and executes through Bash. Public tool contracts should expose guest paths such as `/workspace`, not invent hard-coded host paths.

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

After uncertain termination of a side-effecting operation, recovery logic must not blindly repeat it. Unknown outcome remains distinct from clean failure.
