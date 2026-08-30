# Source Provenance

This document records source lineage, build-input provenance, and verification boundaries for Minis for Android. It is a provenance/legal record, not an active runtime specification or an ongoing synchronization policy.

## Project lineage

Minis for Android contains substantial code derived from the open-source **OpenMinis** project:

- source repository: https://github.com/OpenMinis/OpenMinis
- repository identifier: `OpenMinis/OpenMinis`
- upstream license at the time of derivation: GPL-3.0
- project website: https://openminis.app

The current repository is independently maintained by Slacker-LLC and is not an official OpenMinis distribution. Its present product architecture, runtime contracts, release policy, and development decisions are defined by this repository's current source code, tests, and active documentation.

There is no standing policy that changes from OpenMinis are continuously synchronized into this repository. If code is later imported or adapted from any external project, the importing change must identify its source, preserve applicable notices, satisfy the relevant license, and be adapted to the current Minis for Android architecture.

### Derivation revision boundary

This repository does not currently contain independently verified evidence for one canonical OpenMinis commit that represents the complete derivation point of the present codebase. Therefore this document does **not** invent or infer an upstream commit hash from dates, repository creation time, filenames, or later synchronization history.

The machine-readable record at [`provenance/runtime-assets.json`](provenance/runtime-assets.json) records `derivation_revision` as `null` / `not-recorded` for the same reason. A future change may replace that value only when it can cite verifiable repository history or another authoritative record.

## Copyright and license obligations

Minis for Android remains distributed under GPL-3.0. Derived source does not lose its original copyright or license obligations when architecture, packaging, platform scope, or runtime implementation changes.

When redistributing modified binaries, provide the corresponding source as required by GPL-3.0 and preserve applicable copyright and license notices.

See [LICENSE](LICENSE), [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md), and [CONTRIBUTORS.md](CONTRIBUTORS.md).

## Build and runtime asset provenance

Machine-readable provenance for security-relevant build inputs and generated runtime artifacts lives in [`provenance/runtime-assets.json`](provenance/runtime-assets.json). The manifest is intentionally conservative: a hash is recorded only where the repository can prove the exact value.

### Ubuntu Base input

The default Ubuntu rootfs build consumes Canonical's Ubuntu Base 24.04.3 arm64 archive:

- source: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz`
- checksum index: `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS`
- repository-pinned SHA-256: `7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048`

`scripts/build-ubuntu-rootfs.sh` fails closed unless the repository pin, the current Ubuntu `SHA256SUMS` entry, and the locally downloaded archive all agree. `scripts/test-build-ubuntu-rootfs-verification.sh` covers the fail-closed checksum behavior.

### Generated Ubuntu rootfs

`dist/ubuntu-arm64-rootfs.tar.gz` is a generated build artifact, not a checked-in binary with one repository-wide fixed hash. `scripts/build-ubuntu-rootfs.sh` writes these records for each build:

- `dist/ubuntu-arm64-rootfs.tar.gz.sha256`
- `dist/ubuntu-arm64-rootfs.manifest.json`

Those build-time values must not be copied into provenance as though they were a universal artifact hash. The machine-readable provenance therefore keeps the generated rootfs `sha256` field `null` and records the build-time sidecar policy instead.

### minisd

`minisd` is built from the source under `src/native/minisd/` and is installed at `/data/adb/minis/bin/minisd`. This repository does not claim a universal SHA-256 for all `minisd` builds and does not treat an arbitrary locally built binary hash as a project provenance constant. Cargo source/lock state and CI build checks are the repository evidence for this source-built artifact.

## Verification boundaries

Repository CI can verify source-level and build-contract facts such as documentation guards, Rust checks, Android build/test/lint checks, the Ubuntu Base checksum pin, and generated rootfs metadata behavior.

Repository CI is **not** a Root/KernelSU device E2E attestation. Unless a specific change includes independently captured device evidence, documentation must not state that Root installation, KernelSU integration, mount/chroot startup, or end-to-end privileged runtime behavior was physically verified. The asset manifest records this boundary as `root_kernelsu_device_e2e: not-claimed`.

Likewise, generated artifact hashes are evidence only for the build that produced them. They must not be promoted to fixed project-wide provenance without a reproducible-release contract that proves that claim.

## Historical architecture

Earlier development inherited or experimented with runtime ideas and components associated with OpenMinis, including Alpine Linux and PRoot-based execution. Those details are historical context, not current architecture.

The retained history note is [docs/archive/RUNTIME-HISTORY.md](docs/archive/RUNTIME-HISTORY.md). Current runtime behavior is documented in [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md).
