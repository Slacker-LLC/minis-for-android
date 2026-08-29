# Issue #53 Build Cleanup Audit

This audit records the build-path decisions for Issue #53. It is intentionally narrower than runtime cleanup and package-identity migration.

## Classification

### Removed dead build/tooling paths

- `scripts/verify_models_dev_resolution.py`: iOS-only verifier with no current Android/Rust build role.
- The iOS copy/output branch in `scripts/update_models_dev.sh`: the active Android catalog output remains `src/android/app/src/main/assets/models-dev-api.json`.

### Migrated active legacy entry point

- The old pet-named PowerShell APK wrapper was still functionally valid because it directly built the checked-in Android Gradle project. Its behavior was retained under `scripts/build-android-debug.ps1`; the legacy filename was removed.

### Preserved migration-only identity

The current Android build still uses legacy package identity in active source, including:

- `namespace = "com.openminis.app"`
- `applicationId = "dev.openminispet.android"`

These values are part of the current supported Android build and are therefore not removed by Issue #53. Package/namespace migration is tracked separately by Issue #52.

### Preserved legal/provenance material

The following are explicitly outside the regression guard's cleanup target and must retain accurate historical attribution:

- `LICENSE`
- `UPSTREAM.md`
- `THIRD_PARTY_LICENSES.md`
- historical Git history and archived provenance material

References to OpenMinis in those locations are legal/provenance information, not active build dependencies.

### Deferred to other issues

PRoot/Alpine runtime-history cleanup is not broadened into this build-path change. Issue #44 owns obsolete runtime/code-path cleanup; Issue #53 only prevents obsolete build pipelines from becoming active tooling again.

## Canonical build contract

`BUILDING.md` is the single canonical build/release guide. Current build entry points operate directly on this repository:

- Android: `src/android/gradlew`
- Rust: `src/native/minisd/Cargo.toml`
- rootfs: `scripts/build-ubuntu-rootfs.sh`
- Windows convenience wrapper: `scripts/build-android-debug.ps1`
- CI: `.github/workflows/ci.yml`

README files point to `BUILDING.md` instead of carrying a second copy of the full build recipe.

## Regression guard

`scripts/check_build_cleanup.py` scans active root build docs, scripts, and GitHub workflows. It rejects:

- the removed legacy PowerShell build wrapper;
- references from active tooling to the removed iOS source tree;
- active tooling that clones the upstream OpenMinis repository as a build stage;
- active tooling that applies an OpenMinis/OpenMinisPet patch pipeline.

`scripts/test_build_cleanup_guard.py` covers positive and negative fixtures, including explicit acceptance of migration-only Android package identity and provenance-only upstream references.

The guard deliberately does not scan legal/provenance documents or all application source for legacy naming. Doing so would conflate Issue #53 with the separate package-identity migration and could break the currently supported Android build.
