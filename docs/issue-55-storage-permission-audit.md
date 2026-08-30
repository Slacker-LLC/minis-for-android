# Issue #55 permission audit decisions

This file records the current classification of the storage permissions and legacy flags named by Issue #55. It complements `issue-55-external-storage-access.md`, which defines runtime behavior.

| Manifest surface | Classification | Current reason / exit condition |
| --- | --- | --- |
| `MANAGE_EXTERNAL_STORAGE` | Current and required by the existing raw-path mount design | Android 11+ mounted folders are converted from SAF tree URIs to arbitrary `/storage/...` POSIX paths before minisd bind-mounts them. Remove only after the guest mount design stops depending on arbitrary raw shared-storage paths. |
| `READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | Compatibility | Raw-path read permission for pre-R legacy storage and compatibility with older media/storage flows. API 30+ external mounts use All Files Access instead. |
| `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`) | Android 10-and-older compatibility | Required only for raw-path writes on the legacy storage path. It is not used as an Android 11+ substitute for All Files Access. |
| `requestLegacyExternalStorage=true` | Android 10 compatibility | Android documentation recommends retaining the flag when an app still needs its Android 10 legacy-storage behavior. Android 11+ ignores it for apps targeting Android 11+. |
| `preserveLegacyExternalStorage=true` | Migration-only compatibility | Not required by minisd. Retained until the legacy application-identity migration/retirement decision is complete; the repository has no device evidence proving that every upgraded legacy install can safely discard the preserved view. |
| persisted SAF tree grant | Current and required, but insufficient for raw bind access | Remembers the user's selected tree and survives process death/reboot. It does not replace raw `/storage/...` access when minisd consumes a POSIX bind source. |

## Removed rationale

The active implementation must not justify these permissions with PRoot. PRoot is not the authority that consumes the resolved host path. The current authority chain is Android SAF/path resolution -> minisd mount namespace -> Ubuntu chroot.

The production logic changed in this branch follows that model. Historical comments elsewhere in the repository are cleanup targets for Issue #44 if they are not part of the storage contract itself; they must not be treated as implementation evidence.

## Security constraint

All Files Access remains broad special access. The application does not obtain it silently. The mounted-folders UI sends the user to the system special-access page, and `ExternalStorageAccessPolicy` checks the resulting state. When the state is absent or later revoked, the runtime bind snapshot fails closed.

A future architecture that performs all mounted-folder I/O through SAF/document-provider APIs could remove the raw-path requirement, but that is a runtime design change rather than a permission-only edit.
