# Third-Party Licenses

Minis for Android contains code derived from [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) and uses additional third-party open-source components.

This file is a project-level inventory, not a substitute for the license files shipped by each dependency. Exact dependency versions remain authoritative in Gradle, Cargo, vendored source, and lock files.

## Project and source-lineage license

OpenMinis is distributed under GPL-3.0. Minis for Android is a derivative work and continues to be distributed under **GPL-3.0**. Architecture or platform changes do not remove the GPL obligations that apply to derived application code.

When distributing a modified APK, provide the corresponding source and preserve applicable copyright/license notices.

See [PROVENANCE.md](PROVENANCE.md) and [LICENSE](LICENSE).

## Active native/runtime components

| Component | Source | License | Use |
|---|---|---|---|
| `minisd` | this repository, `src/native/minisd/` | GPL-3.0 with project | Root broker / chroot setup |
| serde / serde_json | Cargo.lock | MIT OR Apache-2.0 | JSON protocol |
| libc (Rust crate) | Cargo.lock | MIT OR Apache-2.0 | low-level Linux/Unix calls |
| Ubuntu 24.04 Base | Ubuntu project | aggregate package licenses | generated rootfs userspace |
| cppjieba | vendored Android native source | MIT | word segmentation |

## Android dependencies

AndroidX/Jetpack/Compose, OkHttp/MockWebServer, Kotlin coroutines/serialization, Coil, multiplatform-markdown-renderer, Reorderable, and ACRA are Apache-2.0; Shizuku API/provider and RealTimeCutVADLibraryForAndroid are MIT; JUnit 4 is EPL-1.0; the `org.json` test dependency follows its upstream terms.

## Removed or historical components

Historical source and repository history may mention OpenMinis/Termux PRoot and ELF loaders (GPL-2.0), talloc (LGPL-3.0-or-later), Alpine Linux minirootfs, removed iOS-only dependencies, and historical web tooling.

Those references are historical/legal records, not a statement that the components are part of the current Android artifact.

## Verification

Before publishing binaries, verify the final dependency graph and bundled assets against this inventory. Update this file when a dependency is added, removed, or relicensed.
