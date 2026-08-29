# Execution Environment

This document describes the **current** Minis for Android execution model. Historical PRoot/Alpine design notes belong in `docs/archive/` and are not part of this contract.

## Overview

The rooted-device Linux path is:

```text
Android kernel
  ↓
Android app
  ↓ Unix socket RPC
minisd (root broker)
  ↓
unshare + mount + chroot
  ↓
Ubuntu 24.04 userspace
```

The Ubuntu environment reuses the Android kernel. It is not a VM and does not boot a second kernel.

## Roles

### Android app

The app owns:

- agent/session state;
- provider/model state;
- tool registration and permissions;
- workspace/memory/skills/shared host directories;
- user approvals and execution checkpoints;
- runtime readiness and recovery logic.

The app is not replaced by the Linux guest.

### `minisd`

`minisd` is the privileged root broker and part of the trusted computing base. It performs a small set of structured operations needed to prepare and operate the Ubuntu environment.

The broker uses a private Unix socket, peer identity checks, framed messages, bounded request/response sizes, a compile-time capability ceiling, and runtime policy that may restrict but not expand that ceiling.

Long-running privileged operations are not allowed to hold a global broker state lock indefinitely.

### Ubuntu guest

The guest is Ubuntu 24.04 userspace running inside a chroot/mount namespace prepared by `minisd`.

The guest runs under the app UID rather than retaining root identity. Root is used to establish the environment, not as the default identity for arbitrary agent code.

## Host and guest paths

The rootfs itself lives under the root-managed Minis area:

```text
/data/adb/minis/rootfs
```

Application-owned data directories are created under the app's private files directory and bind-mounted into the guest. The exact host prefix is determined from `Context.filesDir` at runtime.

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

Path resolution must enforce canonical containment and reject `..`, NUL, and escape attempts.

## Shell behavior

The agent shell targets Ubuntu and executes through Bash. The public tool contract should describe the workspace as `/workspace` without exposing a fake or hard-coded host path.

Shell execution is governed by timeout, dangerous-command policy, output limits/spill, job state, approval, and checkpoint rules where applicable.

## Rootfs

`scripts/build-ubuntu-rootfs.sh` downloads a pinned Ubuntu Base archive and verifies its SHA-256 digest before producing the rootfs build artifact.

The base rootfs is intentionally small. Additional packages can be provisioned on device.

Rootfs verification is part of CI and must fail closed if the pinned archive digest does not match.

## Root is not a capability shortcut

Root providers such as KernelSU, Magisk, and APatch are diagnostic/runtime backends, not proof that every privileged operation is allowed.

Capability checks may depend on:

- actual `su` behavior;
- Linux capability bits;
- SELinux context and enforcing mode;
- mount namespace support;
- OEM policy;
- Android API/version constraints.

Do not infer full capability from `uid=0` alone.

## Shizuku-compatible bridges

Shizuku, Sui, AXManager, and ordinary Android SDK APIs are separate capability paths. They are not lower or higher rungs of one universal privilege ladder.

Use the narrowest available backend for each operation and probe support before use.

## SELinux

The project must not disable SELinux globally for compatibility. Root/chroot behavior is expected to work under enforcing mode or report a structured unavailable/partial state.

## Failure and recovery

Android may kill the app or foreground services. Long-running agent work must therefore rely on persisted jobs/checkpoints rather than assuming one process remains alive indefinitely.

After uncertain termination of a side-effecting operation, recovery logic must not blindly repeat the operation. An unknown tool outcome must remain distinguishable from a clean failure.

## Historical architecture

The current project does not use the upstream/historical Alpine + PRoot runtime as its active Android execution backend.

Archived PRoot documents may be useful for provenance or upstream comparison, but new code and documentation must target the `minisd` + Ubuntu architecture unless the project explicitly changes direction.
