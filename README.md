# Minis for Android

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

**Minis for Android** is an independent, Android-focused AI agent runtime built on the open-source [OpenMinis](https://github.com/OpenMinis/OpenMinis) codebase.

This project keeps the native Android agent experience while developing a separate execution architecture for rooted devices: a Rust root broker (`minisd`), an Ubuntu 24.04 chroot, native Android tools, MCP client/server support, persistent jobs, subagents, and device-level integrations.

> This repository is developed as its own project. OpenMinis is the upstream codebase and origin of substantial shared application code; this repository is not the official OpenMinis Android distribution. See [UPSTREAM.md](UPSTREAM.md) for provenance and synchronization policy.

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
└─ minisd root broker
   └─ unshare + mount + chroot
      └─ Ubuntu 24.04 userspace
```

The Android app remains the single source of truth for agent state, sessions, tool permissions, provider configuration, and persistence. MCP is an integration surface, not a second agent runtime.

## What is different from upstream OpenMinis

OpenMinis prioritizes a portable Android sandbox based on PRoot. Minis for Android intentionally takes a different path on rooted devices:

- Ubuntu 24.04 chroot instead of the upstream Alpine + PRoot runtime;
- `minisd` as a structured root broker rather than exposing arbitrary root shell execution;
- local MCP server plus external MCP provider integration;
- expanded Android-native tool surface;
- goals, todos, jobs, subagents, approval seams, execution checkpoints, and output spill/pruning;
- source-first release engineering with release signing, lint, Android build checks, Rust checks, and rootfs verification in CI.

The project does **not** carry the old Web Remote / Cloudflare Tunnel runtime that existed in earlier experiments.

## Current status

The public repository is under active development and currently distributes source code rather than production APK releases.

Current Android build metadata is defined in `src/android/app/build.gradle.kts`; do not treat `versionName` as a promise that a matching GitHub Release exists.

Important project references:

- [Development status](docs/DEVELOPMENT-STATUS.md)
- [Execution environment](docs/EXECUTION-ENVIRONMENT.md)
- [Security model](docs/SECURITY.md)
- [Build guide](BUILDING.md)
- [Documentation index](docs/README.md)
- [Active issues](https://github.com/Slacker-LLC/minis-for-android/issues)

## Main capabilities

### Agent runtime

- multiple providers, model groups, OAuth/API-key authentication, image input, streaming, and tool calls;
- persistent sessions, memory, skills, goals, todos, jobs, subagents, and structured user questions;
- context-pressure handling, token accounting, tool timeouts, checkpoints, dangerous-command policy, and large-output spill/pruning;
- file, browser, Linux, memory, MCP, and Android-native tools through one runtime.

### Linux runtime

```text
Android kernel
  ↓
minisd (root broker)
  ↓
unshare + mount + chroot
  ↓
Ubuntu 24.04 userspace
```

The guest reuses the Android kernel; it is not a virtual machine and does not boot a separate Linux kernel. The guest runs with the app UID rather than inheriting unrestricted root privileges.

The rootfs is built by `scripts/build-ubuntu-rootfs.sh` and verified against a pinned SHA-256 digest.

### MCP

- local server on loopback (`127.0.0.1:18789` by default);
- bearer-token authentication and caller-aware tool permissions;
- confirmation flow for sensitive remote tools;
- external MCP servers can be connected through the existing tool runtime.

### Android integration

- Accessibility-driven UI operations and diagnostics;
- screenshots, logcat, package/deployment tooling, media and device capabilities;
- desktop pet / overlay integration;
- default digital-assistant / VoiceInteraction integration;
- speech recognition and TTS;
- ordinary Android API, Shizuku-compatible bridges, and root backends selected by capability rather than by a single privilege ladder.

## Build from source

Recommended host: Linux or WSL2.

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"

rustup target add aarch64-unknown-linux-musl
cargo build --release --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml

./scripts/build-ubuntu-rootfs.sh

cd src/android
./gradlew :app:assembleDebug --no-daemon
```

See [BUILDING.md](BUILDING.md) for toolchain versions, tests, release signing, and troubleshooting.

## Repository layout

| Path | Purpose |
|---|---|
| `src/android/` | Android application, Compose UI, Room, providers, MCP, tools |
| `src/native/minisd/` | Rust root broker |
| `src/shared/` | shared runtime resources still used by Android |
| `scripts/` | rootfs, verification, and maintenance scripts |
| `docs/` | current architecture, security, status, and specifications |
| `assets/` | project presentation assets |
| `.github/` | CI and contribution templates |

## Documentation policy

Primary project documentation is written in English. Translations are secondary and must not define behavior that differs from the English document.

When documentation and implementation disagree, use this order of authority:

```text
source code and tests
  > current architecture/security documents
  > README and changelog
  > archived material
```

Archived documents may describe removed experiments and are intentionally non-authoritative.

## Upstream and license

Minis for Android is built on OpenMinis and remains distributed under the [GNU General Public License v3.0](LICENSE). Upstream attribution and synchronization policy are documented in [UPSTREAM.md](UPSTREAM.md); third-party notices are in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
