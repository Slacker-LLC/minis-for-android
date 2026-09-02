# Issue #53 Build Cleanup Audit

This audit records the build-path decisions for Issue #53. It is intentionally narrower than runtime cleanup, package-identity migration, and source-provenance history.

## Classification

### Removed dead build/tooling paths

- `scripts/verify_models_dev_resolution.py`: legacy verifier with no current Android/Rust build role.
- The removed copy/output branch in `scripts/update_models_dev.sh`: the active Android catalog output remains `src/android/app/src/main/assets/models-dev-api.json`.

### Migrated active legacy entry point

- The old pet-named PowerShell APK wrapper was still functionally valid because it directly built the checked-in Android Gradle project. Its behavior was retained under `scripts/build-android-debug.ps1`; the legacy filename was removed.

### Canonical build contract

`BUILDING.md` is the single canonical build/release guide. Current build entry points operate directly on this repository:

- Android: `src/android/gradlew`
- Rust: `src/native/minisd/Cargo.toml`
- rootfs: `scripts/build-ubuntu-rootfs.sh`
- Windows convenience wrapper: `scripts/build-android-debug.ps1`
- CI: `.github/workflows/ci.yml`

## Regression guard

`scripts/check_build_cleanup.py` scans active root build docs, scripts, and GitHub workflows. It rejects:

- the removed legacy PowerShell build wrapper;
- references from active tooling to removed external source trees;
- active tooling that clones a historical upstream repository as a build stage;
- active tooling that applies a historical upstream patch pipeline.

`scripts/test_build_cleanup_guard.py` covers positive and negative fixtures.
