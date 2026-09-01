# Building Minis for Android

This is the primary build guide for the current runtime. The build files and CI workflow are authoritative when this document and source disagree.

Chinese translation: [BUILDING.zh-CN.md](BUILDING.zh-CN.md)

## Toolchain

| Tool | Current source configuration |
|---|---|
| Host | Linux or WSL2 recommended |
| JDK | 17 |
| Gradle | 8.11.1 wrapper |
| Android Gradle Plugin | 8.10.1 |
| Kotlin | 2.1.0 |
| compileSdk | 36 |
| targetSdk | 35 |
| minSdk | 26 |
| Android NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| Rust | stable + `aarch64-linux-android` target |

The Android module includes `arm64-v8a` and `x86_64` application ABIs. The privileged Ubuntu runtime distributed by Issue #51 is currently arm64-only.

## 1. Clone and configure

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
export PATH="$HOME/.cargo/bin:$PATH"
```

Optional provider customization remains local and must not contain committed secrets:

```bash
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

## 2. Runtime distribution contract

A production APK owns one matching runtime identity:

- `lib/arm64-v8a/libminisd.so`, installed by Package Manager into `ApplicationInfo.nativeLibraryDir`;
- `assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz`;
- `assets/minis-runtime/runtime-manifest.json` using schema version 2.

The manifest records the exact minisd SHA-256, final rootfs tar SHA-256, protocol version 1, layout version 2, `arm64-v8a`, Ubuntu release/profile/upstream SHA, a positive provision revision, and the required guest commands.

The rootfs identity has the form:

```text
ubuntu-24.04-rN-<first 16 hex characters of the final tar SHA-256>
```

Debug and Release use the same rootfs producer, manifest generator, and APK verification path. Placeholder identities such as `managed` are invalid.

Production does not deploy a second broker executable into `/data/adb/minis`. Android starts the Package Manager-owned `libminisd.so` with app-UID-scoped Linux abstract sockets, inline policy JSON, and an app PID plus procfs start-time lease. Filesystem sockets and `--policy PATH` exist only for explicit standalone development through `--dev-filesystem-ipc`.

## 3. Build the pinned Ubuntu 24.04 rootfs

The repository pins Ubuntu Base 24.04.3 and verifies both the repository digest and upstream checksum metadata before extraction.

```bash
bash scripts/build-ubuntu-rootfs.sh
```

Outputs:

```text
dist/ubuntu-arm64-rootfs.tar.gz
dist/ubuntu-arm64-rootfs.tar.gz.sha256
dist/ubuntu-arm64-rootfs.manifest.json
```

The final archive is reproducible: the packaging step sorts entries and fixes tar metadata and gzip timestamps. `ROOTFS_REVISION` and `PROVISION_REVISION` are positive integer inputs and default to `1`.

Direct Gradle APK builds do not require a separate manual rootfs command. `:app:packageRuntimeAssets` depends on the Gradle `buildPinnedUbuntuRootfs` producer, so a missing `dist/ubuntu-arm64-rootfs.tar.gz` is built before runtime asset packaging. A rootfs already produced by CI can be reused as an up-to-date Gradle output.

Rootfs verification tests:

```bash
bash scripts/test-build-ubuntu-rootfs-verification.sh
```

They cover fail-closed checksum/revision cases and two-build reproducibility.

## 4. Build and verify `minisd`

```bash
rustup target add aarch64-linux-android
cd src/android
./gradlew :app:verifyMinisdElf --no-daemon
```

Gradle cross-compiles `src/native/minisd`, verifies the Android arm64 PIE/16 KB ELF contract, and stages the exact executable as `lib/arm64-v8a/libminisd.so`.

Rust quality checks from the repository root:

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

## 5. Build APKs

Debug:

```bash
cd src/android
./gradlew :app:assembleDebug :app:verifyDebugMinisdApk --no-daemon
```

Output:

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Release signing is fail-closed and requires all four variables:

```bash
export RELEASE_KEYSTORE=/absolute/path/to/release.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS='...'
export RELEASE_KEY_PASSWORD='...'

cd src/android
./gradlew :app:assembleRelease :app:verifyReleaseMinisdApk --no-daemon
```

The common APK verifier checks both packaged payload hashes against the schema-v2 manifest and rejects obsolete externally staged broker/runtime paths. The independent release verifier additionally checks signing/package identity and runtime-manifest invariants:

```bash
bash scripts/verify-android-release.sh \
  src/android/app/build/outputs/apk/release/app-release.apk
```

Do not `adb push` a separate broker executable.

## 6. Android tests and lint

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:lintRelease --no-daemon
./gradlew :app:assembleDebugAndroidTest --no-daemon
```

`assembleDebugAndroidTest` compiles the installed-layout device gate. On an authorized arm64 device, that instrumentation verifies that `ApplicationInfo.nativeLibraryDir/libminisd.so` physically exists, is executable, and matches the APK manifest, and that the packaged rootfs asset matches its declared SHA-256. CI compiles this gate; it does not substitute for rooted-device execution.

Runtime package-boundary checks:

```bash
bash scripts/check-runtime-package-boundary.sh
```

Documentation provenance checks:

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

## 7. Installed runtime lifecycle

The rooted execution path is:

```text
Android app
  ↓ abstract Unix socket RPC
Package Manager-owned libminisd.so
  ↓
private mount namespace + bind mounts + chroot
  ↓
Ubuntu 24.04 userspace
```

The versioned system runtime lives under:

```text
/data/adb/minis/runtime/rootfs/versions/
/data/adb/minis/runtime/rootfs/current
/data/adb/minis/runtime/rootfs/previous
/data/adb/minis/runtime/rootfs/pending
```

A candidate rootfs is extracted and validated before the externally visible `current` pointer switch. Failed post-switch health/provision checks attempt to restore `previous`; interrupted transactions are detected through `pending`.

User data is separate from replaceable rootfs state. The canonical persistent sources are:

```text
/data/adb/minis/workspace
/data/adb/minis/sessions
/data/adb/minis/memory
/data/adb/minis/skills
/data/adb/minis/shared
/data/adb/minis/home
```

These paths are fixed, prepared before keeper namespace creation, and rejected if their persistent backing is tmpfs. Existing App-private data is a one-time migration input; after migration these `/data/adb/minis/*` directories are the persistent sources of truth.

## 8. CI acceptance chain

The pull-request CI runs independent jobs for:

- Rust format, Clippy, tests, host release build, and arm64 compatibility build;
- rootfs fail-closed/reproducibility tests, runtime package boundary, and diff hygiene;
- documentation provenance;
- pinned rootfs production and identity verification;
- Android unit tests and Debug/Release lint;
- Debug APK assembly plus runtime verification;
- installed-layout instrumentation compilation;
- Release Kotlin compilation, APK assembly, runtime verification, signing/package verification, and final diff hygiene.

A green CI run proves the build/package/static test chain. Root/KernelSU, SELinux-enforcing persistence, install/upgrade/rollback, force-stop lifecycle, and physical-device installed-layout behavior remain device gates.

## Related documents

- [README.md](README.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/runtime-package-boundary.md](docs/runtime-package-boundary.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
