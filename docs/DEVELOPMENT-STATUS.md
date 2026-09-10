# Development Status

> This document describes the `refactor/direct-ubuntu-runtime` runtime state during the direct-Ubuntu migration. Final source/tests and CI on the final branch SHA remain authoritative. Historical audit baselines are retained in `docs/contracts/06-CURRENT-GAPS.md` and issue/archive documents.

## Project state

- Repository: `Slacker-LLC/minis-for-android`
- Product branch under validation: `refactor/direct-ubuntu-runtime`
- Platform: rooted Android
- Runtime: Android App-owned execution + direct Ubuntu 24.04 chroot
- `applicationId`: `llc.slacker.minis`
- Android/Kotlin namespace: `com.openminis.app`
- Public distribution: source-first

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

The former privileged broker is no longer an active source/build/runtime component. PRoot/Alpine compatibility is not an active runtime requirement.

## Storage contract

`/data/adb/minis/rootfs` is the Root-owned replaceable Ubuntu rootfs. Active guest user data is App-owned and derived from `Context.filesDir`: global workspace/home, global memory/skills/shared/MCP data, and per-session backing.

Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` trees are migration sources only. Direct runtime copies them once into App storage before guest startup and records `.root-data-migrated-v1` after successful migration.

## Network compatibility

Guest HTTP/HTTPS uses a standalone Root helper at `127.0.0.1:18787`. It exists to preserve outbound connectivity in Android/VPN/BPF cases where the non-root guest UID may be blocked. The helper only implements bounded HTTP/CONNECT forwarding and is not a generic Root service.

Real VPN/DNS switching remains a device-verification concern even when host tests and native builds pass.

## Runtime payload and native artifacts

- Runtime payload: `ubuntu-arm64-rootfs.tar.gz` + rootfs-only `runtime-manifest.json`.
- Root network helper: separately built `minis-root-network-proxy-arm64-v8a`, packaged as `libminisnetproxy.so`.
- rclone AAR remains a separate Android dependency/artifact.
- Obsolete broker binary/socket/manifest fields are rejected by regression guards.

## CI and verification

Canonical CI is expected to cover documentation provenance, build cleanup/boundary guards, rootfs verification, root-network-proxy Rust quality and Android cross-build, rclone, Android unit/lint/build paths, 16 KiB native alignment, release-signing gates, APK verification, and release bundle checks.

A passing CI run does not replace physical-device verification for Root authorization, SELinux, VPN/DNS, mount behavior, namespace semantics, or OEM lifecycle.

## Primary source locations

| Area | Path |
|---|---|
| Android app | `src/android/` |
| Direct Ubuntu runtime | `src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/` |
| Runtime coordination | `src/android/app/src/main/java/com/openminis/app/runtime/` |
| Root network proxy | `src/native/root-network-proxy/` |
| Rootfs build | `scripts/build-ubuntu-rootfs.sh` |
| Runtime payload verifier | `scripts/verify-runtime-payload.sh` |
| Package boundary guard | `scripts/check-runtime-package-boundary.sh` |
| CI | `.github/workflows/ci.yml` |

## Current validation boundary

The migration is not considered complete merely because source changes landed. Completion requires the final branch SHA to pass the requested compile/unit/payload/native/package checks, canonical full CI, and a residue scan showing no production broker classes/binaries/socket contracts. Physical-device behavior must be reported separately if not tested.
