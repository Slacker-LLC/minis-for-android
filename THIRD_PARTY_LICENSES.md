# Third-Party Licenses

Minis for Android contains code derived from [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) and uses additional third-party open-source components.

This file is a project-level inventory, not a substitute for the license files shipped by each dependency. Exact dependency versions remain authoritative in Gradle, Cargo, vendored source, and lock files.

## Project and upstream license

OpenMinis is distributed under GPL-3.0. Minis for Android is a derivative work and continues to be distributed under **GPL-3.0**. Removing upstream iOS code or replacing the upstream Android PRoot runtime does not remove the GPL obligations that apply to the derived application code.

When distributing a modified APK, provide the corresponding source and preserve applicable copyright/license notices.

See also [PROVENANCE.md](PROVENANCE.md) and [LICENSE](LICENSE).

## Active native/runtime components

| Component | Source | License | Use |
|---|---|---|---|
| `minisd` | this repository, `src/native/minisd/` | GPL-3.0 with project | Root broker / chroot setup |
| serde / serde_json | Cargo.lock | MIT OR Apache-2.0 | JSON protocol |
| libc (Rust crate) | Cargo.lock | MIT OR Apache-2.0 | low-level Linux/Unix calls |
| Ubuntu 24.04 Base | Ubuntu project | aggregate package licenses | generated rootfs userspace |
| cppjieba | vendored Android native source | MIT | word segmentation |

The Ubuntu rootfs is a generated build artifact. Its packages retain their own licenses and notices.

## Android dependencies

| Library / family | License |
|---|---|
| AndroidX / Jetpack / Compose | Apache-2.0 |
| OkHttp / MockWebServer | Apache-2.0 |
| Kotlin coroutines / serialization | Apache-2.0 |
| Coil | Apache-2.0 |
| multiplatform-markdown-renderer | Apache-2.0 |
| Reorderable | Apache-2.0 |
| ACRA | Apache-2.0 |
| Shizuku API/provider | MIT |
| RealTimeCutVADLibraryForAndroid | MIT |
| JUnit 4 | EPL-1.0 |
| org.json test dependency | Public Domain / JSON License |

Use `src/android/app/build.gradle.kts` and Gradle dependency reports for the current version set.

## Bundled web/UI assets

| Asset | Location | License |
|---|---|---|
| KaTeX | Android app assets | MIT |
| cppjieba dictionaries | Android app assets | MIT / upstream distribution terms |

## Removed or historical components

The current Android execution architecture does not build or ship the historical Alpine + PRoot runtime. Older Git history and archived documents may still mention:

- OpenMinis/Termux PRoot and ELF loaders (GPL-2.0);
- talloc (LGPL-3.0-or-later);
- Alpine Linux minirootfs;
- iSH and iOS-only dependencies removed from this Android-focused tree;
- historical Web Remote assets and associated web tooling.

Those historical references must not be read as a statement that the components are part of the current Android artifact.

## Verification

Before publishing binaries, verify the final dependency graph and bundled assets against this inventory. If a dependency is added, removed, or relicensed, update this file in the same change.
