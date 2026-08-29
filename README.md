# Minis for Android

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**Minis for Android** is an Android AI agent runtime for rooted devices. It combines a native Android application with a Rust root broker (`minisd`), an Ubuntu 24.04 userspace, Android-native tools, MCP integrations, persistent agent state, jobs, subagents, voice/assistant features, and device-level automation.

Chinese overview: [README.zh-CN.md](README.zh-CN.md)

## Architecture

```text
Android native app
├─ Agent loop / sessions / Room / repositories
├─ Provider and model runtime
├─ Tool runtime / approvals / checkpoints / jobs
├─ Android-native tools
├─ MCP client + local MCP server
├─ Voice / assistant / overlay integrations
└─ Unix socket RPC
   ↓
minisd root broker
   ↓
private mount namespace + bind mounts + chroot
   ↓
Ubuntu 24.04 userspace
```

The Android app remains the source of truth for agent state, sessions, tool permissions, provider configuration, and persistence. `minisd` performs narrowly scoped privileged runtime setup; the Linux guest reuses the Android kernel and runs agent code under the app guest UID.

## Current status

The repository is under active development and currently distributes source code rather than promising production APK releases. Build metadata is defined in `src/android/app/build.gradle.kts`.

Important references:

- [Development status](docs/DEVELOPMENT-STATUS.md)
- [Execution environment](docs/EXECUTION-ENVIRONMENT.md)
- [Security model](docs/SECURITY.md)
- [Build guide](BUILDING.md)
- [Documentation index](docs/README.md)
- [Source provenance and attribution](PROVENANCE.md)
- [Active issues](https://github.com/Slacker-LLC/minis-for-android/issues)

## Main capabilities

- multiple providers, model groups, OAuth/API-key authentication, image input, streaming, and tool calls;
- persistent sessions, memory, skills, goals, todos, jobs, subagents, and structured user questions;
- file, browser, Linux, memory, MCP, and Android-native tools through one runtime;
- Accessibility, screenshots, logcat, package/deployment, media, assistant/overlay, speech recognition, and TTS integrations.

## Linux runtime

```text
Android kernel
  ↓
minisd
  ↓
mount namespace + bind mounts + chroot
  ↓
Ubuntu 24.04 userspace
```

The rootfs is built by `scripts/build-ubuntu-rootfs.sh` and verified against a pinned SHA-256 digest. The guest shares the Android kernel; it is not a virtual machine.

## Build from source

Use [BUILDING.md](BUILDING.md) as the authoritative build and release guide.

Minimal debug APK entry point:

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

## Repository layout

| Path | Purpose |
|---|---|
| `src/android/` | Android application, Compose UI, Room, providers, MCP, tools |
| `src/native/minisd/` | Rust root broker |
| `src/shared/` | shared runtime resources used by Android |
| `scripts/` | rootfs, verification, and maintenance scripts |
| `docs/` | current architecture, security, status, specifications, and archive |
| `.github/` | CI and contribution templates |

## Documentation policy

Current-state documentation describes Minis for Android directly. Historical architecture is isolated under `docs/archive/`; source lineage and required attribution are isolated in [PROVENANCE.md](PROVENANCE.md) and [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

Authority order:

```text
source code and tests
  > current architecture/security documents
  > README and changelog
  > archived historical material
```

## License and provenance

Minis for Android is distributed under the [GNU General Public License v3.0](LICENSE). Source lineage, attribution obligations, and historical origin are documented in [PROVENANCE.md](PROVENANCE.md); third-party notices are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
