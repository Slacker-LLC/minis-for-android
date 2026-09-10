# Development Status

> This document describes the current state of `refactor/direct-ubuntu-runtime` after PR #235 merged into `main` on 2026-09-10. Source/tests on the branch remain authoritative for this branch; physical-device behavior is tracked separately from CI evidence.

## Project state

- Repository: `Slacker-LLC/minis-for-android`
- Reference branch: `refactor/direct-ubuntu-runtime`
- PR #235: merged into `main` on 2026-09-10
- Platform: rooted Android
- Linux runtime: Android App-owned orchestration + Direct Ubuntu 24.04 chroot
- `applicationId`: `llc.slacker.minis`
- Android/Kotlin namespace: `com.openminis.app`
- Distribution: source-first

## Active architecture

```text
Android app
├─ Agent / sessions / Room / providers / tools / MCP / voice
└─ ExecutionCoordinator / App-owned shell lifecycle
   ↓
UbuntuKernel / DirectRootRunner
   ↓
su → setsid → unshare -m → explicit bind mounts → chroot
   ↓
setpriv(real App UID/GID, clear groups, drop capabilities)
   ↓
Ubuntu 24.04 userspace
```

The former privileged broker is not an active source/build/runtime component. PRoot/Alpine compatibility is not an active backend.

`root.shell` is denied for local Agent and MCP callers. Direct Root execution is reserved for trusted App-owned infrastructure and is not a generic tool surface.

## Storage

`/data/adb/minis/rootfs` is Root-owned replaceable runtime state. Active guest user data is App-owned and derived from `Context.filesDir`: global workspace/home, global memory/skills/shared/MCP data, and per-session workspace/attachments/offloads/browser backing.

Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` trees are one-time migration sources only. The migration marker is `<filesDir>/minis/.root-data-migrated-v1` and is written only after all required copies succeed.

## Network compatibility is separate from Root/chroot

The current build contains a fixed `127.0.0.1:18787` HTTP/CONNECT helper. It is a network-compatibility component, not a property of chroot and not a reason Root exists.

HTTP/CONNECT proxying itself does not require Root. The current Android deployment may start the helper with privileged identity so outbound sockets can avoid restrictions that apply to the App-UID guest on some VPN/BPF configurations. The helper has no shell/file/plugin/generic RPC interface.

Device networking remains an acceptance item: VPN/TUN transitions, DNS selection, `198.18.0.0/15` Fake-IP behavior, Android BPF/UID policy, and OEM differences cannot be proven by host CI.

## Runtime payload/native artifacts

- Rootfs payload: `ubuntu-arm64-rootfs.tar.gz` + rootfs-only `runtime-manifest.json`.
- Network helper: independently built arm64 native artifact, packaged as `libminisnetproxy.so`.
- rclone AAR: separate Android dependency/artifact.
- Obsolete broker binary/socket/runtime package identities are rejected by build/package guards.

## Verification status

The Direct Ubuntu migration passed canonical CI before PR #235 was merged, including rootfs/payload checks, network-helper Rust quality/build/tests, rclone, Android unit tests, Debug/Release lint/build/package verification, 16 KiB checks, fail-closed release signing, and bundle-generated APK validation.

Documentation follow-up commits made on `refactor/direct-ubuntu-runtime` after the merge must be treated as branch-only until separately merged to `main`.

## Remaining validation boundary

CI does **not** prove physical-device operation. Still requiring explicit rooted-device evidence where relevant:

- Root authorization on the target Root solution;
- `su → unshare → mount/bind → chroot → setpriv` behavior under device SELinux policy;
- real App UID/GID ownership and per-session workspace consistency;
- VPN/DNS/BPF/Fake-IP behavior, including guest `curl` / `apt` on representative configurations;
- OEM process/service lifecycle behavior.

Open GitHub Issues remain independent work items. Their descriptions may contain historical runtime terminology and must be re-audited against the final Direct Ubuntu source before being treated as current implementation facts.
