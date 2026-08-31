# Development Status

> 行为以中文合同为准：`docs/contracts/`。实现缺口见 `docs/contracts/06-CURRENT-GAPS.md`。本文是能力备忘，不能覆盖合同，也不能把 Gaps 写成已完成。

This document describes the current engineering state of Minis for Android at the architecture/capability level.

## Project state

- Repository: `Slacker-LLC/minis-for-android`
- Primary branch: `master`
- Platform: Android
- Public distribution: source-first; no production APK release is promised by the repository at this time

Build metadata such as `versionName` and `versionCode` lives in `src/android/app/build.gradle.kts`.

## Active architecture

```text
Android app
├─ Agent runtime / sessions / application persistence
├─ Provider and model runtime
├─ Tool runtime / permission gates
├─ Android-native tools
├─ jobs / goals / todos / subagents
├─ MCP client + local MCP server
├─ voice / assistant / overlay integrations
└─ Unix socket RPC
   ↓
minisd root broker
   ├─ canonical /data/adb/minis persistent layout
   └─ private mount namespace + bind mounts + chroot
      ↓
Ubuntu 24.04 userspace
```

## Implemented areas

- multi-provider/model support, OAuth/API-key flows, image input, streaming, and tool calls;
- persistent sessions, goals, todos, jobs, subagents, structured questions, memory, and skills;
- loopback MCP server with bearer authentication and external MCP integration;
- Compose UI, Room-backed application state, Accessibility and Android-native system tools;
- voice, assistant, overlay, ASR/TTS integrations;
- Rust `minisd`, verified Ubuntu 24.04 rootfs, private mount namespace, explicit bind mounts, chroot entry, and guest execution under the app UID;
- user-owned Standard/Full Root access modes, structured `root.exec`/`root.fullExec`, exact one-shot confirmation binding, and a persistent Full Access warning;
- fixed Linux guest-data sources under `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`, prepared before keeper startup and rejected if non-canonical or tmpfs-backed.

## CI and release engineering

Repository CI covers documentation provenance checks, Rust formatting/Clippy/tests/release build, rootfs checksum and reproducibility, Android arm64 minisd cross-compilation, bound runtime-manifest generation, Android unit tests, Debug/Release lint, verified Debug/Release runtime packaging, fail-closed release signing, and release APK verification.

## Current risk areas

Priorities include provider/network transport boundaries, foreground-service lifecycle correctness, explicit capability state, process-death recovery, root/SELinux/device-specific compatibility, and reduction of historical lint debt.

Use the live [GitHub Issues](https://github.com/Slacker-LLC/minis-for-android/issues) list for current issue state.

## Platform limitations

- OEM background restrictions may freeze network/CPU when background execution is restricted.
- Assistant role, Accessibility, overlay, SAF, microphone, battery exemptions, Shizuku, and root require separate user/system authorization.
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
| Persistent layout contract | `src/native/minisd/src/layout.rs` |
| Persistent Ubuntu runtime | `src/native/minisd/src/ubuntu_persistent.rs` |
| Rootfs build | `scripts/build-ubuntu-rootfs.sh` |
| CI | `.github/workflows/ci.yml` |

## Documentation rule

If this document conflicts with source code or tests, update this document. Historical explanations belong in `docs/archive/`; source lineage belongs in `PROVENANCE.md`.
