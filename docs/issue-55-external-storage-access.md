# External storage access contract (Issue #55)

## Current runtime path

Mounted external folders are not exposed to the Ubuntu guest through SAF file descriptors. The current path is:

```text
ACTION_OPEN_DOCUMENT_TREE
  -> persisted SAF tree grant
  -> ExternalStorageProvider tree id
  -> raw host path under /storage/...
  -> minisd bind source
  -> minisd mount namespace
  -> /var/minis/mounts/<name> in the Ubuntu guest
```

This distinction matters: the SAF grant remembers the user's selected tree, but minisd ultimately receives a POSIX path. Android permission state must therefore permit the app to read that raw path before it is exported as a runtime bind source.

## Access matrix

| Android version | Raw-path read requirement | Raw-path write requirement | Repository decision |
| --- | --- | --- | --- |
| Android 11+ (API 30+) | `Environment.isExternalStorageManager()` | same | Keep `MANAGE_EXTERNAL_STORAGE` while the runtime uses arbitrary raw `/storage/...` bind sources. A persisted SAF grant does not substitute for this raw-path capability. |
| Android 10 (API 29) | legacy external-storage view + granted `READ_EXTERNAL_STORAGE` | granted `WRITE_EXTERNAL_STORAGE` | Keep `requestLegacyExternalStorage=true` and the API-29 legacy permissions for the Android 10 compatibility path. |
| Android 8-9 (API 26-28) | granted `READ_EXTERNAL_STORAGE` | granted `WRITE_EXTERNAL_STORAGE` | Treat the normal runtime grants as explicit raw-path capabilities; do not assume pre-Q means unrestricted access. |

`preserveLegacyExternalStorage=true` is classified as migration-only compatibility, not as a current minisd requirement. The repository still has an active legacy Android application identity while Issue #52 / PR #69 is in flight, and there is no device evidence proving that upgraded legacy installs no longer depend on the preserved storage view. Removal should happen only with an explicit legacy-install migration/retirement decision rather than as an unrelated #55 cleanup.

## Fail-closed behavior

`ExternalStorageAccessPolicy` is now the single Android-side policy for raw bind-source access.

- On Android 11+, a stored SAF grant does not make a raw path bindable when All Files Access is absent.
- On Android 10, losing the legacy storage view or the read grant makes the raw path unavailable.
- A read-only legacy grant remains usable as a read-only mount; write probing cannot upgrade it.
- `MountedFoldersStore.add()` rejects a new mount if the raw host path cannot be read.
- `resolvePosixPath()` rejects a raw directory when `File.list()` is denied instead of accepting a path that would appear empty in the guest.
- Every runtime bind snapshot rechecks raw-path readability. Revoked access or unmounted media removes the source from the minisd snapshot rather than preserving a stale/empty bind.

This does not grant new storage privileges and does not bypass Android scoped storage. It makes the existing broad-access dependency explicit and observable.

## Why SAF alone is not enough for the current design

SAF is sufficient when application code performs I/O through `ContentResolver` / document-provider APIs. That is not the current guest mount design: the runtime deliberately converts an ExternalStorageProvider tree into a POSIX source path so the Linux guest can use normal filesystem syscalls through a bind mount.

Replacing `MANAGE_EXTERNAL_STORAGE` with pure SAF therefore requires a different architecture (for example a document-provider bridge or mirror layer). That is not a permission cleanup and is outside this issue.

## Concurrent PR boundary

- PR #66 owns runtime distribution, rootfs identity/install/rollback, Package Manager-owned minisd delivery, and broker lifecycle. #55 does not modify those contracts.
- PR #75 owns pre-exec Ubuntu/minisd recovery error classification. #55 does not modify the minisd protocol or recovery path.
- Issue #45 / PR #76 owns privileged-operation modes. Storage special access is not controlled by the Agent and is not routed through that mode switch.
- PR #69 owns Android package/application identity. Any final removal of legacy-install compatibility flags must respect that migration decision.

PR #66 currently changes `AndroidManifest.xml` only in an application-identity action hunk. If #55 later rewrites the storage-permission comments in the same file, both independent hunks must be preserved.

## Verification

Repository unit tests cover the policy matrix, including the important case where SAF-style read/write grants are present but Android 11+ All Files Access is absent.

The following acceptance checks require an authorized physical/emulated Android environment and are not claimed by repository CI in this change:

- SAF grant with All Files Access disabled;
- All Files Access enabled;
- permission revocation and persisted URI grant after reboot;
- real minisd bind mount and guest read/write;
- behavior across representative Android versions and OEM storage implementations.

No adb or device operation is part of this implementation.