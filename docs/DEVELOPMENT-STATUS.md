# Development Status

This document describes the current engineering state of Minis for Android. It is intentionally written at the architecture/capability level instead of duplicating volatile issue counts or release metadata.

## Project state

- Repository: `Slacker-LLC/minis-for-android`
- Primary branch: `master`
- Platform: Android
- applicationId: `dev.openminispet.android`
- Upstream base: [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)
- Public distribution: source-first; no production APK release is promised by the repository at this time

Build metadata such as `versionName` and `versionCode` lives in `src/android/app/build.gradle.kts` and is not a substitute for GitHub release metadata.

## Active architecture

```text
Android app
├─ Agent runtime / sessions / persistence
├─ Provider and model runtime
├─ Tool runtime / permission gates
├─ Android-native tools
├─ jobs / goals / todos / subagents
├─ MCP client + local MCP server
├─ voice / assistant / overlay integrations
└─ minisd root broker
   └─ Ubuntu 24.04 chroot
```

Historical Alpine + PRoot and Web Remote / Cloudflare Tunnel implementations are not part of the active runtime.

## Implemented areas

### Agent runtime

- multi-provider/model support, OAuth/API-key flows, image input, streaming, and tool calls;
- persistent sessions and repository-backed state;
- goals, todos, jobs, subagents, structured questions, memory, and skills;
- tool approval, checkpointing, timeout policy, dangerous-command controls, context pressure, and large-output spill/pruning.

### MCP

- loopback MCP server with bearer authentication;
- caller-aware tool visibility and permission gates;
- confirmation flow for sensitive remote calls;
- external MCP provider integration through the canonical tool registry.

### Android integration

- Compose UI and Room-backed state;
- Accessibility, screenshots, logcat, package/deployment, media, settings, connectivity, and diagnostics tools;
- desktop pet / overlay and default-assistant integration;
- speech recognition, provider ASR/TTS, and system TTS;
- ordinary Android API, Shizuku-compatible bridges, and root capabilities selected by actual availability.

### Linux/root runtime

- Rust `minisd` root broker;
- Ubuntu 24.04 chroot built from a pinned and verified rootfs source;
- mount namespace and explicit host/guest path mapping;
- guest execution under the app UID;
- structured root operations rather than unrestricted remote root shell exposure.

## CI and release engineering

Repository CI currently covers:

- Rust formatting, Clippy, tests, and release build;
- rootfs checksum/failure-path tests;
- Android unit tests;
- Debug and Release lint;
- Debug packaging;
- Release Kotlin compilation and packaging;
- fail-closed release signing;
- release APK verification.

The repository keeps an Android lint baseline for historical debt; new lint findings are expected to remain gated.

## Current risk areas

The project has a larger privilege surface than a conventional Android app. Active work should continue to prioritize:

- provider/network transport boundaries;
- Android foreground-service lifecycle correctness;
- explicit capability state for integrations that require unavailable private build customization;
- process-death recovery and non-duplication of side effects;
- root/SELinux/device-specific compatibility testing;
- reduction of historical lint debt.

Use the live [GitHub Issues](https://github.com/Slacker-LLC/minis-for-android/issues) list for current issue state rather than copying issue numbers into long-lived documentation.

## Platform limitations

- OEM background restrictions may freeze network/CPU when the app is not allowed to run in the background.
- Assistant role, Accessibility, overlay, SAF, microphone, battery exemptions, Shizuku, and root each require their own user/system authorization.
- Root availability does not imply unrestricted SELinux or Linux capability access.
- Long-running work must tolerate Android process/service termination and recover from persisted state.

## Primary source locations

| Area | Path |
|---|---|
| Android app | `src/android/` |
| Android tools | `src/android/app/src/main/java/com/openminis/app/tools/android/` |
| Tool runtime | `src/android/app/src/main/java/com/openminis/app/tools/runtime/` |
| MCP | `src/android/app/src/main/java/com/openminis/app/mcp/` |
| Ubuntu runtime | `src/android/app/src/main/java/com/openminis/app/sandbox/ubuntu/` |
| Root broker | `src/native/minisd/` |
| Rootfs build | `scripts/build-ubuntu-rootfs.sh` |
| CI | `.github/workflows/ci.yml` |

## Documentation rule

If this document conflicts with source code or tests, update this document. Do not preserve obsolete architecture descriptions for historical continuity in a current-status file.
