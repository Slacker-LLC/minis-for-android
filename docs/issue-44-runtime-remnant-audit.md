# Issue #44 runtime remnant audit: `default_mount`

## Finding

`src/android/app/src/main/assets/default_mount/` was still packaged as an Android asset tree even though the active runtime no longer consumes it.

The tree contained historical environment material including:

- `root/.ashrc`;
- an old `etc/profile.d/minis.sh` and hostname/pip configuration;
- desktop/browser opener shims;
- a complete Python `minis-mcp-cli` copy and its tests/transports/helpers.

## Why it is dead

The current production authority chain is Android -> minisd -> private mount namespace -> Ubuntu chroot.

On current `master`:

- `RootfsManager.applyDefaultMountOverlay()` is a no-op;
- `MinisApp` has no call that installs or applies `default_mount`;
- `scripts/build-ubuntu-rootfs.sh` creates the Ubuntu hostname/profile/layout directly and does not copy this asset tree;
- PR #66's runtime-distribution work does not reference `default_mount` and does not list any of its files as changed.

Therefore packaging this directory adds stale runtime code/data to the APK without creating the corresponding guest files at runtime. Keeping it is actively misleading because the files look authoritative while the Ubuntu builder/minisd path is the real authority.

## Decision

Delete the entire `default_mount` tree instead of selectively preserving Alpine/ash-era pieces.

This change does **not** claim that every Issue #44 cleanup item is complete. It deliberately avoids files owned by concurrent migrations:

- PR #66: runtime distribution/rootfs lifecycle;
- PR #69: Android package/application identity;
- PR #61: build-path cleanup;
- PR #73: storage/runtime follow-up scope;
- PR #75: runtime recovery error model.

Any live capability formerly represented by a deleted asset must have an explicit current producer. Reintroducing the old directory as a compatibility shortcut is forbidden; the JVM regression guard checks that it stays absent.

## Verification

`LegacyRuntimeAssetGuardTest` runs in the normal Android JVM unit-test job and fails if `src/main/assets/default_mount` returns.

Normal Android build/lint/package CI is the source of truth for proving that no production source depends on the deleted assets. No adb or device operation is used by this change.