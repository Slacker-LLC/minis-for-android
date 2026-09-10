# Minis for Android

**Chinese contracts define intended behavior. Final target-branch source and tests define the current implementation.** Start with [README.zh-CN.md](README.zh-CN.md), [AGENTS.md](AGENTS.md), and [docs/contracts/](docs/contracts/00-IDENTITY.md). Dated audit baselines and confirmed gaps are tracked in [06-CURRENT-GAPS.md](docs/contracts/06-CURRENT-GAPS.md).

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**Minis for Android** is an independent AI agent runtime for rooted Android devices: a native Android app and Ubuntu 24.04 userspace sharing the Android kernel. It is not a VM. The App owns runtime/session lifecycle; Root is bounded to rootfs, mount/chroot infrastructure, controlled legacy migration, and a loopback-only outbound network proxy.

```text
Android app → ExecutionCoordinator → RootPersistentShell → UbuntuKernel → su → setsid → unshare -m → bind mounts → chroot → setpriv(App UID/GID, no caps) → bash
```

The production runtime is Root-only. PRoot and other userspace-emulation backends are not part of the active runtime contract. The former privileged broker is not an active source/build/runtime component.

Active Linux user data is App-owned and derived from `Context.filesDir`: global workspace/home, global memory/skills/shared/MCP data, and per-session workspace/attachments/offloads/browser backing. `/data/adb/minis/rootfs` is the Root-owned, replaceable Ubuntu rootfs. Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` trees are migration sources only and are not active runtime truth after migration.

Guest commands run as the real Android App UID/GID after `setpriv` clears supplementary groups and Linux capabilities. The standalone Root network proxy is fixed to `127.0.0.1:18787` and only provides bounded HTTP/CONNECT egress; it is not a generic Root command/RPC surface.

Current Android identity:

- `applicationId`: `llc.slacker.minis`
- Android/Kotlin namespace: `com.openminis.app`

The namespace is intentionally allowed to differ from the installed application identity; a mass package rename is not an implicit requirement.

Source-first. No production APK release is promised by the repository. Build instructions: [BUILDING.md](BUILDING.md). Runtime details: [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md). Legal lineage: [PROVENANCE.md](PROVENANCE.md).
