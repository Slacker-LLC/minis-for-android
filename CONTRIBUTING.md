# Contributing

Contributions to Minis for Android are welcome through GitHub Issues and Pull Requests.

This repository is an independent Android project built on [OpenMinis](https://github.com/OpenMinis/OpenMinis). Before contributing, read [UPSTREAM.md](UPSTREAM.md) so upstream ports do not accidentally reintroduce architecture that this project intentionally replaced.

## Reporting bugs

Include, when relevant:

- Android version and app `versionName` / `versionCode`;
- device model and ROM;
- root status and root provider (Magisk / KernelSU / APatch) for diagnostics;
- exact reproduction steps;
- expected and actual behavior;
- provider/model involved;
- sanitized logs or crash metadata.

Never post API keys, OAuth tokens, MCP tokens, DebugServer tokens, signing material, private files, or unrelated device data.

## Pull requests

Base new work on the current `master` branch unless the maintainers request otherwise.

A change should preserve these project invariants:

1. The Android app remains the single source of truth for agent state, sessions, providers, tools, and persistence.
2. New tools go through the existing Tool Registry, permission manager, runtime gates, and result model.
3. Sensitive operations define caller scope and use confirmation or local-only policy where appropriate.
4. Root operations use `minisd` or an existing controlled privileged backend; do not add raw `su -c <model output>` paths.
5. Side-effecting operations reuse approvals, checkpoints, jobs, and recovery semantics.
6. Runtime capability is determined by real probes rather than by provider names or `uid=0` alone.
7. Security-sensitive paths fail closed.
8. Tests and English primary documentation are updated with behavior changes.
9. GPL and third-party license obligations are preserved.
10. Build/tooling changes use the canonical entry points in [BUILDING.md](BUILDING.md) and must not reintroduce an upstream clone/patch build pipeline.

## Runtime architecture

### Android runtime

Do not create a second remote agent/database/runtime. MCP and system integrations must project into the existing Android runtime.

Do not duplicate Accessibility, jobs, approval, checkpoint, token accounting, provider state, or permission systems when a canonical implementation already exists.

### Linux runtime

The active rooted-device execution path is:

```text
minisd root broker
  ↓
unshare + mount + chroot
  ↓
Ubuntu 24.04 userspace
```

Do not reintroduce Alpine + PRoot as a parallel default runtime.

`minisd` is part of the trusted computing base. New privileged RPC methods must be narrow, structured, auditable, and bounded by a compile-time capability ceiling. Runtime policy may restrict capabilities but must never expand that ceiling.

Do not disable SELinux globally for compatibility.

### MCP

The remote tool surface is MCP. The removed Web Remote / Cloudflare Tunnel implementation must not return as a second control plane.

External MCP servers still enter the canonical tool registry and permission system.

### Android privileged backends

Ordinary Android APIs, Accessibility, Shizuku-compatible bridges, and root are separate capabilities rather than a single privilege ladder.

Vendor-specific enhancements must:

- probe support before execution;
- return structured unsupported/unavailable results on non-matching devices;
- version-gate hidden APIs/reflection;
- remain optional enhancements;
- fall back to generic Android settings/actions when possible.

## Security-sensitive areas

Treat changes in these areas as security work:

- `src/native/minisd/`;
- root / privileged backends / Accessibility;
- MCP server and MCP provider;
- provider credentials and OAuth;
- file, path, mount, and workspace boundaries;
- package installation and sensitive Android data/tools;
- DebugServer and release packaging;
- cleartext/network transport policy.

Security tests should cover negative cases, not only successful paths.

## Validation

Use the narrowest relevant checks locally, and rely on repository CI for the full gate.

Build/tooling changes:

```bash
python3 scripts/test_build_cleanup_guard.py
python3 scripts/check_build_cleanup.py
bash -n scripts/update_models_dev.sh
```

Android:

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

When release behavior changes:

```bash
./gradlew :app:lintRelease --no-daemon
./gradlew :app:compileReleaseKotlin --no-daemon
```

`minisd`:

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

Rootfs changes:

```bash
bash scripts/test-build-ubuntu-rootfs-verification.sh
```

Connected Android tests must run only on an explicitly authorized emulator/device.

## Documentation

English is the primary documentation language. Translations may be added as secondary documents, but they must point back to the English source of truth.

Authority order:

```text
source code and tests
  > current architecture/security documents
  > README and changelog
  > archived material
```

See also:

- [README.md](README.md)
- [BUILDING.md](BUILDING.md)
- [docs/README.md](docs/README.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
