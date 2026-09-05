# Development Status

> Baseline: `master` at `6f10d1b3f413d37aca5c21465e8e71ef3eb12120` (2026-09-04). For newer commits, re-check source/tests before relying on this snapshot. Intended behavior is defined by the Chinese contracts; confirmed current deviations are listed in `docs/contracts/06-CURRENT-GAPS.md`.

## Project state

- Repository: `Slacker-LLC/minis-for-android`
- Primary branch: `main`
- Platform: rooted Android
- Runtime: native Android app + Rust `minisd` + Ubuntu 24.04 chroot
- `applicationId`: `llc.slacker.minis`
- Android/Kotlin namespace: `com.openminis.app`
- Public distribution: source-first; no production APK release is promised by the repository

Build metadata remains in `src/android/app/build.gradle.kts`.

## Active architecture

```text
Android app
├─ Agent runtime / sessions / Room persistence
├─ Provider and model runtime
├─ Tool registry / permission / approval / checkpoints
├─ Android-native tools
├─ jobs / goals / todos / subagents
├─ MCP client + local MCP server
├─ voice / assistant / overlay integrations
└─ Unix socket RPC
   ↓
minisd root broker
   ├─ canonical /data/adb/minis persistent layout
   └─ private mount namespace + explicit bind mounts + chroot
      ↓
Ubuntu 24.04 userspace
```

The active product runtime is Root-only. PRoot/Alpine compatibility is not an active runtime requirement.

## Persistent runtime contract

Canonical user data is rooted at `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}`. The rootfs is replaceable runtime state. Guest ownership uses the actual App UID/GID rather than a fixed numeric ID. Session execution is expected to use the selected session backing and remain contained below the sessions root.

Runtime distribution consumes the packaged runtime manifest, verifies minisd/rootfs digests, and uses staging/previous/pending/deployed state for rootfs replacement and recovery without replacing user-data roots.

## Confirmed current gaps

The current confirmed repair queue is intentionally narrow:

- #182 — Release/R8 can break RealTimeCutVAD JNI callbacks without the required keep rule.
- #183 — `minis://` path decoding needs double-encoding and literal `+` tolerance.
- #184 — chat message deletion has regressed to UI/memory mutation before durable DB deletion.
- #185 — SOUL default seeding can treat a transient `info` failure as “not found” and overwrite user content.
- #186 — terminal PTY still uses fixed `10000:10000`, ignores `sessionId`, and can bypass session workspace semantics.
- #187 — chat link staging can perform broker/file I/O on the main thread.
- #188 — pasted content can be consumed before the user message is durably persisted.
- #189 — exited PTY children are not consistently reaped by the Kotlin terminal lifecycle.
- #190 — with VPN enabled, the Ubuntu guest can lose usable DNS because the active Android/VPN resolver is not inherited/refreshed correctly.

Speculative hardening and architecture cleanup that lack a demonstrated failure are not mixed into this list. See `06-CURRENT-GAPS.md` for scope and acceptance boundaries.

## CI and release engineering

Repository CI covers documentation provenance checks, Rust formatting/Clippy/tests/release build, rootfs verification, Android minisd cross-compilation, runtime-manifest generation, Android unit tests, lint, runtime packaging, release-signing gates, and APK verification according to the workflow at the referenced commit.

A passing CI run does not replace device verification for Root, SELinux, VPN/DNS, mount, namespace, or OEM lifecycle behavior.

## Platform limitations

- OEM background restrictions may freeze or kill background work.
- Assistant role, Accessibility, overlay, SAF, microphone, battery exemptions, Shizuku, and root require separate user/system authorization.
- Root availability does not imply unrestricted SELinux or Linux capability access.
- Ubuntu chroot shares the Android kernel and is not a VM or strong isolation boundary.

## Primary source locations

| Area | Path |
|---|---|
| Android app | `src/android/` |
| Ubuntu runtime | `src/android/app/src/main/java/com/openminis/app/sandbox/ubuntu/` |
| Root broker | `src/native/minisd/` |
| Persistent layout | `src/native/minisd/src/layout.rs` |
| Rootfs build | `scripts/build-ubuntu-rootfs.sh` |
| CI | `.github/workflows/ci.yml` |

## Documentation rule

If this status document conflicts with final source/tests, source/tests win for current implementation facts and this file must be updated. If implementation violates an intended contract, record the deviation in `06-CURRENT-GAPS.md` rather than silently redefining the contract.
