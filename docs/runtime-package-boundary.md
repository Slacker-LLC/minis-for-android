# Android runtime package boundary

This document describes the active package ownership after the direct-Ubuntu refactor. Historical package-migration intent is subordinate to the final target-branch source and `scripts/check-runtime-package-boundary.sh`.

The runtime authority chain is:

```text
Android app
  → ExecutionCoordinator / App-owned shell lifecycle
  → UbuntuKernel / DirectRootRunner
  → su → setsid → unshare -m → explicit bind mounts → chroot
  → setpriv(real App UID/GID, clear groups, drop capabilities)
  → Ubuntu 24.04 guest
```

There is no active privileged broker package, socket protocol, or generic Root RPC in this boundary.

## Active packages

| Package/component | Responsibility |
| --- | --- |
| `com.openminis.app.runtime.ExecutionCoordinator` | Android-side orchestration of guest commands, per-session serialization, cancellation, and shell lifecycle. |
| `com.openminis.app.runtime.RuntimePathRegistry` | App-visible host/guest path registry, SAF mount snapshot, bind inputs, TZ and Android-side helpers. |
| `com.openminis.app.runtime.ExternalMountCoordinator` | Read-only enforcement and compatibility seam for user-selected SAF mounts. Actual host paths are resolved from persisted SAF grants by the direct runtime. |
| `com.openminis.app.runtime.ubuntu.UbuntuRuntime` | Public readiness/execution facade for the Ubuntu backend. |
| `com.openminis.app.runtime.ubuntu.UbuntuKernel` | Rootfs readiness, one-time legacy-data migration, per-shell namespace/bind/chroot construction, and guest privilege drop. |
| `com.openminis.app.runtime.ubuntu.DirectRootRunner` | Internal launcher for App-constructed Root infrastructure scripts. It is not an Agent/MCP command surface. |
| `com.openminis.app.runtime.ubuntu.UbuntuPaths` | Active App-owned backing paths, guest aliases, session containment, and legacy migration-source constants. |
| `com.openminis.app.runtime.ubuntu.RootNetworkProxy` | Starts/verifies the fixed loopback Root egress helper and provides guest proxy environment variables. |
| `com.openminis.app.runtime.guest.*` | Guest-to-Android command/offload bridge. These expose approved Android capabilities to guest commands; they do not own Root or Ubuntu lifecycle. |
| `com.openminis.app.runtime.terminal.*` | Terminal output/timeout policy and active terminal responsibilities. |

`MinisKernel` was replaced by `RuntimePathRegistry`, and `MountedFolderCoordinator` was replaced by `ExternalMountCoordinator`. Neither legacy type may return as an active runtime abstraction.

## Explicit legacy package allowlist

The old `com.openminis.app.sandbox` package is not an active runtime module. It is retained only for the compatibility surfaces enforced by the boundary guard:

- `RootfsManager.kt` — rootfs probe/repair/tar compatibility logic still reused by `UbuntuKernel`;
- `TerminalSession.kt` — retained compatibility API while active terminal responsibilities live under the runtime package;
- `TarExtractionTest.kt` — unit test for the retained rootfs parser.

No other production class may be added under `com.openminis.app.sandbox`. `scripts/check-runtime-package-boundary.sh` enforces this allowlist.

## Current runtime contract

The package refactor must preserve these direct-runtime boundaries:

- Rootfs: `/data/adb/minis/rootfs`, Root-owned and replaceable.
- Active guest user data: App-owned paths derived from `Context.filesDir`.
- Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}`: migration sources only.
- Guest workspace: `/workspace`, bound from the selected global or per-session App-owned backing.
- Guest identity: current Android App UID/GID, with supplementary groups and Linux capabilities cleared by `setpriv`.
- Root network proxy: fixed `127.0.0.1:18787`, HTTP/CONNECT egress only, no command/file/generic RPC surface.
- Root execution: only App-constructed infrastructure through `DirectRootRunner`; Agent/model/MCP output must never become arbitrary Root commands.

## Regression policy

CI fails if:

- a new active class appears under the legacy `sandbox` package outside the explicit allowlist;
- `RootfsManager` or `TerminalSession` is moved into the active runtime tree without an intentional replacement task;
- `MinisKernel` or `MountedFolderCoordinator` returns as an active type;
- any obsolete privileged-client/protocol/bootstrap/config-bridge type returns under Android sources;
- any obsolete broker binary, socket path, runtime package, or build task returns;
- mounted-folder code reintroduces userspace-emulation ownership instead of direct SAF resolution + bind mounts;
- guest privilege drop stops using the real App UID/GID or stops dropping capabilities; or
- the fixed loopback Root network helper grows a generic Root command/RPC surface.

The executable guard is `scripts/check-runtime-package-boundary.sh`; this document describes that boundary but does not replace the guard.
