# Minis for Android

**Chinese contracts define intended behavior. Current source and tests define what `main` currently implements.** Start with [README.zh-CN.md](README.zh-CN.md), [AGENTS.md](AGENTS.md), and [docs/contracts/](docs/contracts/00-IDENTITY.md). Current implementation gaps are tracked in [06-CURRENT-GAPS.md](docs/contracts/06-CURRENT-GAPS.md).

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**Minis for Android** is an independent AI agent runtime for rooted Android devices: a native Android app, a Rust root broker (`minisd`), and Ubuntu 24.04 userspace sharing the Android kernel. It is not a VM.

```text
Android app → Unix socket RPC → minisd → mount namespace + bind mounts + chroot → Ubuntu 24.04
```

The production runtime is Root-only. PRoot and other userspace-emulation backends are not part of the active runtime contract.

Persistent Linux data is rooted at `/data/adb/minis/`: `workspace`, `sessions`, `memory`, `skills`, `shared`, and `home`. The Ubuntu rootfs is replaceable runtime state and must not replace user data.

Current Android identity:

- `applicationId`: `llc.slacker.minis`
- Android/Kotlin namespace: `com.openminis.app`

The namespace is intentionally allowed to differ from the installed application identity; a mass package rename is not an implicit requirement.

Current main branch status:

- Sandbox DNS refresh flow implemented through `ubuntu.refreshDns` RPC and runtime DNS discovery.
- Tool routing improved with normalized tool names and alias resolution.
- Runtime permission handling updated for config paths and guest home directories.
- Message deletion and pasted text handling updated to preserve database/UI consistency.
- Terminal session handling updated for guest identity, workspace binding, and process cleanup.
- Automated coverage added for the above fixes.

Source-first. No production APK release is promised by the repository. Build instructions: [BUILDING.md](BUILDING.md). Legal lineage: [PROVENANCE.md](PROVENANCE.md).
