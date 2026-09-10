# Third-Party Licenses

Minis for Android contains code derived from [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) and uses additional third-party open-source components.

This file is a project-level inventory, not a substitute for license files shipped by each dependency. Exact dependency versions remain authoritative in Gradle, Cargo, vendored source, Go modules, generated rootfs package metadata, and lock/sum files.

## Project and source-lineage license

OpenMinis is distributed under GPL-3.0. Minis for Android is a derivative work and continues to be distributed under **GPL-3.0**. Replacing earlier Android runtime implementations does not remove GPL obligations that apply to derived application code.

When distributing a modified APK, provide corresponding source and preserve applicable copyright/license notices.

See [PROVENANCE.md](PROVENANCE.md) and [LICENSE](LICENSE).

## Active native/runtime components

| Component | Source | License | Current use |
|---|---|---|---|
| `minis-root-network-proxy` | this repository, `src/native/root-network-proxy/` | GPL-3.0-only | fixed loopback HTTP/CONNECT network compatibility helper |
| rclone v1.75.0 mobile binding | `deps/rclone-mobile/`, upstream `rclone/rclone` | MIT for rclone core; transitive modules retain their own licenses | Android remote-transfer binding |
| Ubuntu 24.04 Base | Ubuntu project | aggregate package licenses | generated Direct Ubuntu rootfs userspace |
| cppjieba | vendored Android native source | MIT | word segmentation |

The current `minis-root-network-proxy` Cargo manifest has no third-party crate dependencies; it uses the Rust standard library. The helper may be launched with privileged identity on Android for UID/VPN/BPF egress compatibility, but HTTP/CONNECT proxying itself is not inherently a Root requirement.

The rclone mobile module currently pins `github.com/rclone/rclone v1.75.0`; `deps/rclone-mobile/go.mod` and `go.sum` are authoritative for its transitive module graph. Those transitive dependencies keep their own licenses and notices.

The Ubuntu rootfs is a generated build artifact. Packages inside it retain their individual licenses and notices.

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

## Removed or historical runtime components

The current Android execution architecture does **not** build or ship the former privileged `minisd` broker or the earlier Alpine/PRoot runtime. Older Git history, archived documents, Issue implementation records, and regression tests may still mention:

- `minisd` and its former Rust dependencies/protocol path;
- OpenMinis/Termux PRoot and ELF loaders (GPL-2.0);
- talloc (LGPL-3.0-or-later);
- Alpine Linux minirootfs;
- iSH and iOS-only dependencies removed from this Android-focused tree;
- historical Web Remote assets and associated web tooling.

These historical references do not mean those components are part of the current APK/runtime payload.

## Verification

Before publishing binaries, verify the final Gradle/Cargo/Go dependency graphs, generated Ubuntu rootfs package notices, and bundled assets against this inventory. If a dependency is added, removed, upgraded, or relicensed, update this file in the same change.
