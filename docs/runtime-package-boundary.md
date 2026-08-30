# Android runtime package boundary

The current runtime authority chain is:

```text
Android app -> APK-owned minisd root broker -> mount namespace -> chroot -> Ubuntu 24.04 guest
```

Runtime package ownership follows current responsibilities and the production distribution contract. The broker executable, IPC policy, versioned rootfs, and persistent Agent data have distinct owners and lifecycles.

## Active Android packages

| Package/component | Responsibility |
|---|---|
| `io.github.slackerllc.minis.runtime.ExecutionCoordinator` | Android-side guest execution orchestration and per-session serialization. |
| `io.github.slackerllc.minis.runtime.RuntimePathRegistry` | Host/guest path registry, external mount inputs, proxy/TZ helpers; does not own privileged process or namespace lifecycle. |
| `io.github.slackerllc.minis.runtime.ExternalMountCoordinator` | External folder -> resolved host path -> minisd bind input -> guest mount contract. |
| `io.github.slackerllc.minis.runtime.minisd.*` | APK-owned broker bootstrap/client/protocol/config bridge. |
| `io.github.slackerllc.minis.runtime.distribution.*` | Runtime manifest validation, installed identity probing, install/upgrade/provision/rollback orchestration. |
| `io.github.slackerllc.minis.runtime.ubuntu.*` | Ubuntu lifecycle and guest execution requested through minisd. |
| `io.github.slackerllc.minis.runtime.guest.*` | Guest-to-Android capability/offload surface. |
| `io.github.slackerllc.minis.runtime.terminal.*` | Current terminal/output policy helpers. |

No active minisd/Ubuntu/distribution component belongs under the compatibility `sandbox` package.

## Narrow compatibility package

`io.github.slackerllc.minis.sandbox` is not a general runtime namespace. Its production allowlist is intentionally narrow:

- `RootfsManager.kt` — retained rootfs health/install/recovery compatibility surface used by the current distribution manager;
- `TerminalSession.kt` — retained terminal compatibility surface.

Parser tests that specifically exercise the retained RootfsManager parser may remain beside that compatibility surface. `scripts/check-runtime-package-boundary.sh` rejects new active production classes under `sandbox`.

## APK-owned broker boundary

Production minisd is exactly the executable packaged as:

```text
lib/arm64-v8a/libminisd.so
```

Package Manager installs it into:

```text
ApplicationInfo.nativeLibraryDir/libminisd.so
```

Android production code must not stage or copy the broker to App-writable storage or `/data/adb/minis/bin/minisd`.

Production broker IPC uses app-UID-scoped abstract sockets:

```text
@minis.minisd.root.<uid>.v1
@minis.minisd.app.<uid>.v1
```

Policy is passed through `--policy-json`; Android does not create a production broker policy file. The Android lease supervisor binds broker lifetime to App UID, PID, and procfs process start time. Production therefore does not depend on filesystem broker `.sock`, `.pid`, or policy files.

The Rust standalone CLI retains filesystem sockets and `--policy PATH` only for explicit development compatibility. Both are rejected unless `--dev-filesystem-ipc` is present. That guard is required by the runtime boundary CI check.

## APK runtime asset boundary

The authoritative runtime package contains exactly one matching identity chain:

```text
lib/arm64-v8a/libminisd.so
assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz
assets/minis-runtime/runtime-manifest.json
```

`runtime-manifest.json` must be schema version 2 and declare:

- protocol version `1`;
- layout version `2`;
- ABI `arm64-v8a`;
- exact minisd SHA-256;
- exact final rootfs tar SHA-256;
- `rootfsVersion = ubuntu-24.04-rN-<final SHA first 16>`;
- Ubuntu release/profile/pinned upstream SHA-256;
- positive provision revision;
- non-empty required guest commands.

Debug and Release are not separate runtime distributions. Both consume `packageRuntimeAssets`, which depends on the pinned deterministic rootfs producer and the verified minisd producer. Both use the same APK runtime verifier.

## Versioned rootfs boundary

Replaceable system revisions are root-managed below:

```text
/data/adb/minis/runtime/rootfs/versions/
/data/adb/minis/runtime/rootfs/staging/
/data/adb/minis/runtime/rootfs/current
/data/adb/minis/runtime/rootfs/previous
/data/adb/minis/runtime/rootfs/pending
```

A revision is registered only after archive SHA, required layout, Ubuntu identity, profile, ABI, and upstream identity validation. `current` is the activation point. `previous` supports rollback and `pending` records an interrupted switch.

Persistent Agent data is outside this replaceable revision tree.

## Persistent data boundary

The fixed persistent sources of truth are:

```text
/data/adb/minis/workspace
/data/adb/minis/sessions
/data/adb/minis/memory
/data/adb/minis/skills
/data/adb/minis/shared
/data/adb/minis/home
```

They are prepared before keeper namespace creation, use explicit ownership/mode handling, reject alternate paths and tmpfs-backed persistence, and are excluded from rootfs replacement.

Existing App-private storage is only a migration source. The migration marker is committed only after data copy, ownership/mode correction, SELinux relabel, and compatibility aliases complete.

## Stable runtime contracts

The current boundary preserves:

- protocol version `1`;
- guest workspace `/workspace`;
- fixed host workspace `/data/adb/minis/workspace`;
- persistent home `/data/adb/minis/home` -> `/home/minis`;
- per-session containment under `/data/adb/minis/sessions`;
- keeper-owned private mount namespace and chroot;
- structured fail-closed runtime readiness instead of fallback to alternate persistence or mixed runtime components.

## CI regression policy

`scripts/check-runtime-package-boundary.sh` and the Android/Rust CI fail when the active production chain reintroduces any of the following:

- active runtime classes under the compatibility `sandbox` package outside the allowlist;
- an App-writable or `/data/adb/minis/bin/minisd` production broker executable;
- `external_staged` runtime identity;
- `/data/local/tmp/minis-runtime` production staging;
- production filesystem broker socket/PID/policy paths;
- filesystem IPC without the explicit Rust development guard;
- schema version other than 2 or layout version other than 2;
- missing packaged rootfs asset or manifest identity fields;
- Debug and Release bypassing the common runtime asset/verifier chain.

The installed-layout instrumentation gate is compiled in CI and must additionally be executed on an authorized arm64 device before physical-device acceptance is complete.
