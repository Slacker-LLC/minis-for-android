# Minis for Android

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

The former privileged broker and the PRoot/Alpine runtime are not active production backends. Generic Agent/model/MCP-controlled Root execution is forbidden; `DirectRootRunner` is internal infrastructure only.

Active guest user data is App-owned and derived from `Context.filesDir`. `/data/adb/minis/rootfs` is Root-owned replaceable runtime state. Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` trees are migration sources only.

## Network compatibility

The loopback HTTP/CONNECT proxy at `127.0.0.1:18787` is a **separate network-compatibility component**, not an inherent part of Root or chroot. Proxying itself does not require Root. In the current Android deployment the helper may be started with privileged identity so its outbound sockets can work around device/VPN/BPF restrictions that affect the App-UID guest. It provides no shell, file, plugin, or generic RPC interface.

If a device can provide correct guest networking without this compatibility path, the architectural Root/chroot model does not depend on proxy semantics. Physical-device validation is still required for VPN, DNS, BPF, SELinux, and OEM-specific behavior.

## Android identity

- `applicationId`: `llc.slacker.minis`
- Android/Kotlin namespace: `com.openminis.app`

The installed application identity and source namespace may differ; a repository-wide package rename is not implied.

Source-first. No production APK release is promised by the repository. Build instructions: [BUILDING.md](BUILDING.md). Runtime details: [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md). Security model: [docs/SECURITY.md](docs/SECURITY.md). Legal lineage: [PROVENANCE.md](PROVENANCE.md).
