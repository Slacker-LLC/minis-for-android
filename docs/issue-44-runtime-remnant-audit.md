# Issue #44 runtime remnant audit: `default_mount` — historical record

> **Status (2026-09-10): completed historical cleanup.** This file records why `src/android/app/src/main/assets/default_mount/` was removed. The active runtime is now Direct Ubuntu 24.04 and no longer uses the privileged-broker architecture referenced by the original audit.

## Original finding

`src/android/app/src/main/assets/default_mount/` was still packaged as an Android asset tree even though the active runtime no longer consumed it.

The tree contained historical environment material including:

- `root/.ashrc`;
- old profile/hostname/pip configuration;
- desktop/browser opener shims;
- a complete Python `minis-mcp-cli` copy and tests/transports/helpers.

## Decision

Delete the entire `default_mount` tree instead of selectively preserving old userspace material. Any capability that remains active must have an explicit current producer; reintroducing `default_mount` as a compatibility shortcut is forbidden.

`LegacyRuntimeAssetGuardTest` remains the regression protection for this removal.

## Current architecture mapping

The old statement that the production authority chain was Android → privileged broker → Ubuntu chroot is obsolete. Current authority is:

```text
Android App → ExecutionCoordinator / UbuntuKernel / DirectRootRunner
→ su → unshare -m → explicit bind mounts → chroot
→ setpriv(real App UID/GID, clear groups/caps)
→ Ubuntu 24.04 guest
```

The standalone loopback HTTP/CONNECT helper is a separate network-compatibility component; it is not a replacement broker and proxying itself is not intrinsically coupled to Root/chroot.

## Verification

Normal Android build/lint/unit/package CI and runtime/package cleanup guards are the source of truth for proving that production source no longer depends on the removed asset tree. No adb/device evidence is implied by this historical cleanup document.
