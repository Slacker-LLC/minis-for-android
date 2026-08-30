# Execution Environment

This document defines the current rooted-device execution and runtime-distribution model.

## Runtime authority chain

```text
Android app
  ↓ app-UID-scoped abstract Unix socket RPC
Package Manager-owned libminisd.so
  ↓
private mount namespace
  ↓ explicit bind mounts
chroot
  ↓
Ubuntu 24.04 userspace
```

The Ubuntu guest reuses the Android kernel. It is not a separate kernel or VM.

## Android app responsibilities

The app owns application/database state, provider/model configuration, tool permissions, user approvals, execution checkpoints, and runtime readiness/recovery orchestration.

For the privileged runtime, Android consumes one authoritative schema-v2 manifest packaged in the APK. It refuses mixed minisd/rootfs/layout/provision identities instead of treating file existence as readiness.

## `minisd` responsibilities

`minisd` is the privileged root broker and part of the trusted computing base. Production Android executes the broker directly from `ApplicationInfo.nativeLibraryDir/libminisd.so`; there is no production broker executable copied into `/data/adb/minis` or an App-writable directory.

Production IPC uses Linux abstract sockets scoped by the current App UID:

```text
@minis.minisd.root.<uid>.v1
@minis.minisd.app.<uid>.v1
```

The policy is passed as inline JSON. The Android supervisor binds the broker lifetime to App UID, PID, and `/proc/<pid>/stat` start time, so PID reuse alone cannot keep an old broker alive. Production does not require broker `.sock`, `.pid`, or policy files on disk.

The Rust standalone compatibility CLI may use filesystem sockets or `--policy PATH` only when `--dev-filesystem-ipc` is explicitly supplied. That path is development-only and is not used by the Android production chain.

`minisd` also owns keeper lifecycle, mount-namespace setup, chroot entry, fixed persistent bind-source validation, and root-side execution boundaries.

## APK runtime identity

The APK contains:

```text
lib/arm64-v8a/libminisd.so
assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz
assets/minis-runtime/runtime-manifest.json
```

`runtime-manifest.json` uses schema version 2 and binds the package to:

- protocol version `1`;
- layout version `2`;
- ABI `arm64-v8a`;
- exact minisd SHA-256;
- exact final rootfs tar SHA-256;
- rootfs identity `ubuntu-24.04-rN-<final SHA first 16>`;
- Ubuntu release, profile, and pinned upstream SHA-256;
- a positive provision revision;
- required guest commands, currently `python3`, `git`, and `curl`.

Debug and Release use the same runtime-asset producer and verifier.

## Rootfs build and install

`scripts/build-ubuntu-rootfs.sh` downloads the pinned Ubuntu Base 24.04.3 arm64 archive, verifies repository and upstream SHA-256 metadata, overlays the Minis guest layout, and produces a deterministic final tar. Sorted entries, fixed tar metadata, and timestamp-free gzip make the final SHA reproducible for identical inputs.

The Android Gradle `packageRuntimeAssets` task depends directly on the rootfs producer, so runtime asset packaging cannot legally run before the rootfs archive and build manifest exist.

Installed system revisions live under:

```text
/data/adb/minis/runtime/rootfs/versions/
/data/adb/minis/runtime/rootfs/staging/
/data/adb/minis/runtime/rootfs/current
/data/adb/minis/runtime/rootfs/previous
/data/adb/minis/runtime/rootfs/pending
```

A new revision is extracted into staging, its required layout and manifest identity are checked, and only then is it registered and switched into `current`. `pending` records an in-flight switch. A failed post-switch health/provision check attempts to restore `previous`.

The rootfs is replaceable system state. Persistent Agent data is not stored inside a replaceable rootfs revision.

## Canonical persistent host layout

The persistent sources of truth are fixed:

| Host path | Guest purpose |
|---|---|
| `/data/adb/minis/workspace` | global `/workspace` backing |
| `/data/adb/minis/sessions` | per-session backing |
| `/data/adb/minis/memory` | `/memory` backing |
| `/data/adb/minis/skills` | `/skills` backing |
| `/data/adb/minis/shared` | `/shared` backing |
| `/data/adb/minis/home` | `/home/minis` backing |

These directories are prepared before keeper namespace creation, with explicit owner/mode handling. Alternate persistent paths are rejected. Persistent backing must not resolve to tmpfs.

Existing App-private workspace/session/global/home data is a one-time migration input. Migration is fail-closed and idempotent: copy, ownership/mode correction, SELinux relabel, and compatibility aliases must complete before the final migration marker is committed. After that, `/data/adb/minis/*` is authoritative.

## Guest paths and session isolation

| Guest path | Backing |
|---|---|
| `/workspace` | global workspace or selected session workspace |
| `/memory` | `/data/adb/minis/memory` |
| `/skills` | `/data/adb/minis/skills` |
| `/shared` | `/data/adb/minis/shared` |
| `/home/minis` | `/data/adb/minis/home` |

A valid session is contained below `/data/adb/minis/sessions/<session_id>/`. Session handling rejects traversal, NUL input, symlink escape, and paths outside the fixed sessions root.

Each command receives the keeper namespace state required by the active runtime. Persistent bind sources are established before chroot and Agent commands drop to the configured guest identity rather than retaining unrestricted root identity.

## Startup and upgrade order

The production order is fail-closed:

1. parse and validate the APK schema-v2 runtime manifest;
2. verify the Package Manager-owned minisd SHA and supported ABI;
3. obtain/verify root capability;
4. detect missing, mismatched, corrupt, or interrupted rootfs state;
5. materialize the APK-packaged rootfs and verify its exact SHA;
6. prepare/register a concrete rootfs revision without modifying persistent user data;
7. atomically switch `current`, retaining rollback metadata;
8. start the APK-owned broker and keeper;
9. prepare the fixed persistent sources and private mount namespace;
10. enter Ubuntu and run provision/required-command health checks;
11. commit the provision revision only after those checks pass;
12. report READY only when minisd, rootfs, layout, and provision identity agree.

No-root, unsupported ABI, corrupt package payload, mixed version, invalid persistence backing, or failed provision remains a structured unavailable/failure state rather than a false installed state.

## Shell behavior

Agent shell commands target Ubuntu through the structured minisd RPC surface. Public tool contracts expose guest paths such as `/workspace`, not host implementation paths.

Execution remains subject to timeout/cancellation, dangerous-command policy, output limits/spill, approvals, and persisted checkpoints where applicable.

## SELinux and capability model

The project does not disable SELinux globally. Persistent App access and privileged runtime setup must work under enforcing mode or report failure.

Root-provider identity alone does not prove every operation is available. Runtime capability depends on actual `su` behavior, Linux capabilities, SELinux policy, mount namespace support, Android version, and device policy.

## Validation boundary

CI verifies deterministic rootfs construction, schema-v2 packaging, minisd/rootfs hashes, Rust quality, Android unit/lint/build paths, Debug/Release runtime packaging, release package/signing checks, and compilation of the installed-layout instrumentation gate.

Physical-device acceptance remains separate. A rooted arm64 device must still prove Package Manager-installed broker execution, SELinux-enforcing access to persistent paths, non-tmpfs persistence, fresh install/upgrade/rollback behavior, force-stop lease cleanup, App/guest bidirectional visibility, and installed-layout instrumentation execution.
