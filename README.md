# Minis for Android

> **Minis for Android** is an independent AI agent runtime for rooted Android devices: the agent loop, tools,
> MCP, skills, memory, voice and an Ubuntu 24.04 chroot all run on the device, with no first-party backend.
> Project description: [docs/PROJECT.md](docs/PROJECT.md) · Localization: [docs/I18N.md](docs/I18N.md) ·
> Reference index: [docs/REFERENCES.md](docs/REFERENCES.md) · Document index: [docs/README.md](docs/README.md)

**Chinese contracts define intended behavior. Final branch source and tests define current implementation.** Start with [README.zh-CN.md](README.zh-CN.md), [AGENTS.md](AGENTS.md), and [docs/contracts/](docs/contracts/00-IDENTITY.md).

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**Minis for Android** is an independently maintained AI agent runtime for rooted Android devices. The active Linux environment is Ubuntu 24.04 userspace running on the Android kernel through a direct chroot path; it is not a VM.

```text
Android app → ExecutionCoordinator → RootPersistentShell → UbuntuKernel
            → su → setsid → unshare -m → bind mounts → chroot
            → setpriv(real App UID/GID, clear groups/caps) → bash
```

The former privileged broker and the retired compatibility runtimes are not active production backends. Raw Agent/model/MCP-controlled Root shell or RPC execution is forbidden. The local Agent may use the upstream-compatible structured `root.shell` capability (`tool` basename plus `args`); it is local-only, bounded, and separate from the internal `DirectRootRunner` infrastructure.

Active guest user data is App-owned and derived from `Context.filesDir`. `/data/adb/minis/rootfs` is Root-owned replaceable runtime state. Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` trees are migration sources only.

## Status

The mainline is `main` in `Slacker-LLC/minis-for-android`. The integrated Eta capability line is part of this independent product.

Baseline on 2026-09-20 (Debug): `:app:testDebugUnitTest` **2432 tests / 0 failures**; `:app:lintDebug`
**0 errors** (`186 warnings / 6 hints` are pre-existing); `app-debug.apk` ≈ **114 MB** with the runtime
payload. Interface text lives in resources with **eight locales** — English default plus Simplified Chinese,
Traditional Chinese, Japanese, Korean, German, French and Russian; Simplified and Traditional Chinese are at
full coverage, the other four still fall back to English for part of their strings ([docs/I18N.md](docs/I18N.md)).

Development history and the unverified list: [docs/development/PROGRESS.md](docs/development/PROGRESS.md).

## Network compatibility

The loopback HTTP/CONNECT proxy at `127.0.0.1:18787` is a **separate network-compatibility component**, not an inherent part of Root or chroot. Proxying itself does not require Root. In the current Android deployment the helper may be started with privileged identity so its outbound sockets can work around device/VPN/BPF restrictions that affect the App-UID guest. It provides no shell, file, plugin, or generic RPC interface.

If a device can provide correct guest networking without this compatibility path, the architectural Root/chroot model does not depend on proxy semantics. Physical-device validation is still required for VPN, DNS, BPF, SELinux, and OEM-specific behavior.

## Android identity

- `applicationId`: `llc.slacker.minis`
- Android/Kotlin namespace: `com.openminis.app`

The installed application identity and source namespace may differ; a repository-wide package rename is not implied.

## Remaining gaps

Guest `minis-mcp-cli` is still missing; native MCP support does not replace that CLI, and full device acceptance remains incomplete. The other four locales still fall back to English for part of their strings. See [docs/contracts/06-CURRENT-GAPS.md](docs/contracts/06-CURRENT-GAPS.md), the [Chinese documentation index](docs/README.md), the [device report](docs/REAL-DEVICE-TEST-REPORT.md), and `docs/development/PROGRESS.md` for the per-slice record and the unverified list.

Source-first. No production APK release is promised by the repository. Build instructions: [BUILDING.md](BUILDING.md). Runtime details: [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md). Security model: [docs/SECURITY.md](docs/SECURITY.md). Legal lineage: [PROVENANCE.md](PROVENANCE.md).
