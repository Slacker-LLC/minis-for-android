# Build Cleanup Audit

This document began as the Issue #53 build-path audit and now records the post-Direct-Ubuntu cleanup boundary. Historical decisions are retained only where useful; current build truth comes from the final branch source, scripts, and CI.

## Current canonical build entry points

- Android: `src/android/gradlew`
- Direct Ubuntu rootfs: `scripts/build-ubuntu-rootfs.sh`
- Rootfs-only runtime payload: `scripts/build-runtime-payload.sh`
- Network compatibility helper: `scripts/build-root-network-proxy-android.sh`
- rclone Android binding: `deps/build_rclone_android.sh`
- Windows debug convenience wrapper: `scripts/build-android-debug.ps1`
- CI: `.github/workflows/ci.yml`

There is no active privileged-broker build stage, Android client package, socket protocol, broker native source tree, or broker binary packaged into the APK.

## Runtime package split

The runtime payload contains only the verified Ubuntu rootfs archive and manifest. The loopback network helper is an independent native artifact and is verified separately.

The network helper's current privileged deployment identity is not a reason to treat it as part of Root/chroot packaging. HTTP/CONNECT proxying is a separate network-compatibility function.

## Removed/dead paths

The cleanup history includes removal of:

- obsolete developer-only model verifier paths with no current Android/Rust build role;
- the legacy pet-named PowerShell APK wrapper while retaining its useful behavior under `scripts/build-android-debug.ps1`;
- the old privileged-broker native source/build/package path;
- one-off Phase 1/2/3 migration scripts and temporary CI workflow used only during the Direct Ubuntu refactor.

Historical patch snapshots under `docs/archive/snapshots/` are not executable build inputs.

## Regression guards

`scripts/check_build_cleanup.py` and `scripts/test_build_cleanup_guard.py` enforce the active build cleanup boundary. They reject obsolete runtime identities in production source/build/package paths while allowing clearly historical/negative guard text where required to prevent regression.

`scripts/check-runtime-package-boundary.sh` separately enforces Android runtime package ownership and the narrow legacy-package allowlist.

Runtime payload/network/APK verification is additionally covered by:

```text
scripts/test-build-ubuntu-rootfs-verification.sh
scripts/test-runtime-payload-verification.sh
scripts/verify-runtime-payload.sh
scripts/verify-root-network-proxy.sh
scripts/verify-android-16k.sh
scripts/verify-android-bundle.sh
```

## Historical note

Older revisions of this audit named the former privileged broker as the Rust build entry point. That statement is obsolete after the Direct Ubuntu refactor and must not be used as a current build instruction.
