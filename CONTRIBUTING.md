# Contributing

Contributions to Minis for Android are welcome through GitHub Issues and Pull Requests.

Base new work on the current `master` branch unless maintainers request otherwise. Read [PROVENANCE.md](PROVENANCE.md) when a change imports or adapts externally sourced code so attribution and license obligations remain explicit.

## Reporting bugs

Include Android version, app version metadata, device/ROM, root status when relevant, reproduction steps, expected/actual behavior, provider/model context, and sanitized logs. Never post secrets, tokens, signing material, or private device data.

## Pull requests

A change should preserve these project invariants:

1. The Android app remains the single source of truth for agent state, sessions, providers, tools, and persistence.
2. New tools go through the existing Tool Registry, permission manager, runtime gates, and result model.
3. Sensitive operations define caller scope and use confirmation or local-only policy where appropriate.
4. Root operations use `minisd` or an existing controlled privileged backend; do not add raw model-controlled root shell paths.
5. Side-effecting operations reuse approvals, checkpoints, jobs, and recovery semantics.
6. Runtime capability is determined by real probes rather than provider names or `uid=0` alone.
7. Security-sensitive paths fail closed.
8. Tests and English primary documentation are updated with behavior changes.
9. GPL and third-party license obligations are preserved.

## Runtime architecture

The active rooted-device Linux path is:

```text
minisd root broker
  ↓
private mount namespace + bind mounts
  ↓
chroot
  ↓
Ubuntu 24.04 userspace
```

Runtime changes must preserve this contract unless an explicitly reviewed architecture change replaces it. `minisd` privileged RPC methods must remain narrow, structured, auditable, and bounded by a compile-time capability ceiling. Do not disable SELinux globally for compatibility.

MCP and Android system integrations must project into the canonical Android runtime rather than creating a second source of truth.

## Validation

Android:

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

`minisd`:

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

Documentation provenance guard:

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

## Documentation

English is the primary documentation language. Current docs describe current behavior directly; historical architecture belongs under `docs/archive/`, and source/legal lineage belongs in `PROVENANCE.md`.

See [README.md](README.md), [BUILDING.md](BUILDING.md), [docs/README.md](docs/README.md), [docs/SECURITY.md](docs/SECURITY.md), and [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md).
