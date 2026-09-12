# Android runtime package boundary

This document describes active package ownership after the Direct Ubuntu refactor. Final branch source and `scripts/check-runtime-package-boundary.sh` are authoritative.

```text
Android app
  → ExecutionCoordinator / App-owned shell lifecycle
  → UbuntuKernel / DirectRootRunner
  → su → setsid → unshare -m → explicit bind mounts → chroot
  → setpriv(real App UID/GID, clear groups, drop capabilities)
  → Ubuntu 24.04 guest
```

There is no active privileged-broker package, socket protocol, or generic Root RPC in this boundary.

## Active packages

| Package/component | Responsibility |
| --- | --- |
| `com.openminis.app.runtime.ExecutionCoordinator` | Android-side orchestration of guest commands, per-session serialization, cancellation, and shell lifecycle. |
| `com.openminis.app.runtime.RuntimePathRegistry` | App-visible host/guest path registry, SAF mount snapshot, bind inputs, read-only enforcement, TZ and Android-side helpers. |
| `com.openminis.app.runtime.ubuntu.UbuntuRuntime` | Public readiness/execution facade for the Ubuntu backend. |
| `com.openminis.app.runtime.ubuntu.UbuntuKernel` | Rootfs readiness, one-time legacy-data migration, per-shell namespace/bind/chroot construction, and guest privilege drop. |
| `com.openminis.app.runtime.ubuntu.DirectRootRunner` | Internal launcher for App-constructed Root infrastructure scripts. Not an Agent/MCP command surface. |
| `com.openminis.app.runtime.ubuntu.UbuntuPaths` | App-owned backing paths, guest aliases, session containment, and legacy migration-source constants. |
| `com.openminis.app.runtime.ubuntu.RootNetworkProxy` | Android integration for the current loopback network compatibility helper and guest proxy environment. Its name/deployment does not make proxying part of the Root/chroot authority boundary. |
| `com.openminis.app.runtime.guest.*` | Guest-to-Android command/offload bridge; exposes approved Android capabilities without owning Root/Ubuntu lifecycle. |
| `com.openminis.app.runtime.terminal.*` | Terminal output/timeout policy and active terminal responsibilities. |

`MinisKernel` and `MountedFolderCoordinator` were replaced by the active `RuntimePathRegistry` and `UbuntuPaths` responsibilities. Neither legacy type may return as an active runtime abstraction.

## Legacy package allowlist

The old `com.openminis.app.sandbox` package is not an active runtime module. It remains only for explicitly guarded compatibility files:

- `RootfsManager.kt` — retained rootfs parser/probe/repair compatibility logic reused by Direct Ubuntu;
- `TerminalSession.kt` — retained compatibility API while active terminal responsibilities live under runtime packages;
- `TarExtractionTest.kt` — unit test for the retained rootfs parser.

No other production class may be added there without an intentional boundary change. `scripts/check-runtime-package-boundary.sh` enforces the allowlist.

## Current runtime contract

- Rootfs: `/data/adb/minis/rootfs`, Root-owned and replaceable.
- Active guest user data: App-owned paths derived from `Context.filesDir`.
- Historical `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}`: migration sources only.
- Guest workspace: `/workspace`, bound from selected global/per-session App-owned backing.
- Guest identity: current Android App UID/GID, supplementary groups/capabilities cleared by `setpriv`.
- Root infrastructure scripts remain App-constructed through `DirectRootRunner`; the local Agent may use structured `root.shell` through the tool registry, but Agent/model/MCP output must never become an arbitrary raw Root command.
- Network compatibility: current helper listens on `127.0.0.1:18787` and provides HTTP/CONNECT only. HTTP/CONNECT proxying itself does not require Root; privileged deployment is an Android egress compatibility choice.

## Regression policy

CI fails if:

- a new active class appears under the legacy `sandbox` package outside the allowlist;
- `MinisKernel` or `MountedFolderCoordinator` returns as an active type;
- obsolete privileged-client/protocol/bootstrap/config-bridge types return;
- obsolete broker binary/socket/runtime package/build tasks return;
- mounted-folder code reintroduces userspace-emulation ownership instead of direct SAF resolution + bind mounts;
- guest privilege drop stops using the real App UID/GID or stops dropping capabilities;
- internal Root infrastructure becomes Agent/model/MCP-controlled; or
- the loopback network helper grows shell/file/plugin/generic RPC capability or is misused as a remote proxy service.

The executable guard is `scripts/check-runtime-package-boundary.sh`; this document describes the boundary but does not replace the guard.
