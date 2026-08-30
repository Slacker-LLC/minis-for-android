# Android runtime package boundary

Issue #54 replaces the historical `com.openminis.app.sandbox.*` ownership model with packages that describe the current privileged Ubuntu runtime.

The runtime authority chain is:

`Android app -> minisd root broker -> mount namespace -> chroot -> Ubuntu 24.04 guest`

The current runtime keeps the protocol, privileged data paths, guest paths,
user-data layout, rootfs layout, UID policy, mount behavior, and command
semantics stable while moving the broker executable into the APK transaction.

## Active packages

| Package/component | Responsibility |
| --- | --- |
| `com.openminis.app.runtime.ExecutionCoordinator` | Android-side orchestration of guest commands and per-session serialization. Runtime readiness/execution is delegated to `UbuntuRuntime`; privileged infrastructure belongs to minisd. |
| `com.openminis.app.runtime.RuntimePathRegistry` | Android-side host/guest path registry, SAF mount snapshot, bind-mount inputs, proxy/TZ helpers. It does not own processes, mount namespaces, or chroot lifecycle. |
| `com.openminis.app.runtime.ExternalMountCoordinator` | SAF folder -> resolved host path -> minisd bind-mount input -> `/var/minis/mounts/<name>` guest-path contract, including read-only enforcement used by file tools. |
| `com.openminis.app.runtime.minisd.*` | minisd bootstrap/client/protocol/config bridge. minisd is the privileged broker and owns the root-side socket/process boundary. |
| `com.openminis.app.runtime.ubuntu.*` | Ubuntu runtime lifecycle plus host/guest path mapping requested through minisd. minisd owns the keeper mount namespace and chroot. |
| `com.openminis.app.runtime.guest.*` | Guest-to-Android offload/RPC handlers and compatibility transport. These expose Android capabilities to guest commands; they do not own Ubuntu lifecycle. |
| `com.openminis.app.runtime.terminal.TerminalSanitizer` | Output sanitization shared by command/UI surfaces. |
| `com.openminis.app.runtime.terminal.ShellTimeoutPolicy` | Command timeout classification policy. |

`MinisKernel` was renamed to `RuntimePathRegistry`, and its lifecycle-shaped `boot/isBooted` API became `initialize/isInitialized`. The object was already a path/mount registry; the new name/API states that responsibility directly.

`MountedFolderCoordinator` was renamed to `ExternalMountCoordinator`. Its contract is expressed as SAF -> host path -> minisd-owned bind mount -> guest path. It no longer documents legacy userspace bind ownership.

## Explicit legacy allowlist

The old `com.openminis.app.sandbox` package is not an active runtime module. It is retained only for these compatibility shells:

- `RootfsManager.kt` — existing UI/POSIX-tar compatibility surface. Replacement/removal is owned by the rootfs/recovery/distribution work, not Issue #54.
- `TerminalSession.kt` — disabled PTY compatibility API used so the existing terminal UI still compiles while no interactive Ubuntu PTY exists.

`TarExtractionTest.kt` remains beside `RootfsManager` because it exercises the retained parser inside that compatibility shell.

No other production class may be added under `com.openminis.app.sandbox`. `scripts/check-runtime-package-boundary.sh` enforces this allowlist in CI.

## Runtime contract

The runtime preserves these values:

- protocol version: `1`
- broker socket: app-UID-scoped Linux abstract sockets (`@minis.minisd.root.<uid>.v1` and `@minis.minisd.app.<uid>.v1`)
- broker binary: `ApplicationInfo.nativeLibraryDir/libminisd.so`, packaged as `lib/arm64-v8a/libminisd.so`
- rootfs: `/data/adb/minis/runtime/rootfs/current` (legacy fallback:
  `/data/adb/minis/rootfs`)
- rootfs recovery registry: `/data/adb/minis/runtime/rootfs/{versions,staging,current}`;
  `current` is the native runtime activation point and is switched only after
  validation
- host workspace: `/data/adb/minis/workspace`
- guest workspace: `/workspace`

The broker policy is loaded into memory at bootstrap and the watchdog holds an
app-process lease. No broker `.sock`, `.pid`, policy file, or executable is
created under `/data/adb/minis`; Package Manager owns the native executable's
install, upgrade, and uninstall lifecycle. Persistent user data and rootfs
remain under `/data/adb/minis` and are not deleted by ordinary APK uninstall.
Recovery validates the archive before registering a revision and updates the
`current` symlink only after validation. A failed pointer update leaves the
previous rootfs active; a failed post-switch health check restores the
`previous` pointer.

## Regression policy

CI fails if:

- a new active class appears under the legacy `sandbox` package;
- `RootfsManager` or `TerminalSession` is moved into the active runtime tree;
- `MinisKernel` or `MountedFolderCoordinator` returns as an active type;
- active minisd/Ubuntu/offload packages return under `com.openminis.app.sandbox.*`;
- mounted-folder documentation reintroduces legacy userspace bind ownership; or
- the APK/native-library and abstract-socket boundary is bypassed by a new
  externally staged `minisd` executable.
