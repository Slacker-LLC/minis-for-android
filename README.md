# Minis for Android

**Chinese documents are authoritative.** Start with [README.zh-CN.md](README.zh-CN.md), [AGENTS.md](AGENTS.md), and [docs/contracts/](docs/contracts/00-IDENTITY.md). This English file is a summary only and must not define different behavior.

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**Minis for Android** is an independent AI agent runtime for rooted Android devices: a native app, a Rust root broker (`minisd`), and Ubuntu 24.04 userspace sharing the Android kernel (not a VM).

```text
Android app → Unix socket RPC → minisd → mount namespace + bind mounts + chroot → Ubuntu 24.04
```

Persistent Linux data is contracted at `/data/adb/minis/` (`workspace`, `sessions`, `memory`, `skills`, `shared`, `home`). Non-canonical and tmpfs-backed sources must fail closed. **Implementation on `master` is not fully aligned**; see `docs/contracts/06-CURRENT-GAPS.md`.

Source-first. No production APK is promised. Target `applicationId` is `llc.slacker.minis` (not yet applied in Gradle).

Build: [BUILDING.md](BUILDING.md). License: [GPL-3.0](LICENSE). Legal lineage: [PROVENANCE.md](PROVENANCE.md).
