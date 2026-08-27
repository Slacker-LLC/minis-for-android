# Building Minis for Android

This document tracks the current `master` branch. Published APKs currently target Android `arm64-v8a`. Chinese instructions are in [BUILD-CN.md](BUILD-CN.md).

## Requirements

| Tool | Version / notes |
|---|---|
| Host | Linux or WSL2 recommended |
| JDK | 17 |
| Gradle | Wrapper 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Android SDK | compileSdk 36 / targetSdk 35 / minSdk 26 |
| Android NDK | r28+ (`28.0.13004108` is the current example) |
| CMake | 3.22.1 |
| Rust | stable + `aarch64-unknown-linux-musl` target |
| Shell tools | bash, curl, tar, awk, sed, sha256sum |

Use the repository Gradle wrapper. A separate Gradle installation is not required.

## 1. Clone

```sh
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android
```

The current repository has no Git submodules that need initialization. The Rust Root Broker lives in-tree at `src/native/minisd/`.

## 2. Configure Android and provider customization

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # adjust locally
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Public-source builds may use empty customization values. Integrations that require private OAuth customization are not production-ready when those values are absent.

## 3. Build minisd

```sh
rustup target add aarch64-unknown-linux-musl

cargo build --release \
  --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml
```

Output:

```text
src/native/minisd/target/aarch64-unknown-linux-musl/release/minisd
```

`minisd` is linked against musl for this target. The Android NDK is not used for this Rust cross-build step.

## 4. Build the Ubuntu rootfs

```sh
./scripts/build-ubuntu-rootfs.sh
```

The script downloads Ubuntu 24.04 arm64 Ubuntu Base, overlays the Minis directory layout, and packages the rootfs.

The current rootfs is **base-only**. Tools such as `python3`, `git`, and `curl` are installed later by the on-device provisioning path; they are not preinstalled into the tarball by this script.

The old Alpine + PRoot runtime has been removed. No PRoot submodule or PRoot sandbox preparation step is required.

## 5. Build the APK

```sh
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

Output:

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device:

```sh
./gradlew :app:installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. Tests

```sh
cd src/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
```

Run connected tests only on an explicitly authorized device:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Some Provider tests depend on OAuth customization or network fixtures that are intentionally absent from the public repository. Do not delete those tests merely to make an unconfigured environment green.

## Troubleshooting

### Rust cross-compilation fails

Run:

```sh
rustup target add aarch64-unknown-linux-musl
```

and ensure `$HOME/.cargo/bin` is on `PATH`.

### Android NDK is not found

Point `ANDROID_NDK_HOME` to an installed r28+ directory containing:

```text
toolchains/llvm/prebuilt
```

### The app starts but Ubuntu shell commands fail

On a rooted device, verify:

```text
/data/adb/minis/bin/minisd
/data/adb/minis/rootfs
```

The app starts the minisd watchdog from the Ubuntu runtime readiness path.

A direct status probe can be sent with:

```sh
adb shell su -c "/data/adb/minis/bin/minisd --call --socket /data/adb/minis/run/minisd.sock"
```

then:

```json
{"v":1,"method":"ubuntu.status","client":{"id":"adb","capabilities":["ubuntu.status"]}}
```

## Production releases

The current public APK is a debug-signed development build, and release hardening is still tracked in the repository Issues.

A production release should require at least:

- a protected long-term release keystore;
- no DebugServer or debug-only surfaces in the production artifact;
- release lint and CI gates;
- a complete test/security pass;
- an immutable source tag and published artifact hashes.

See also:

- [README.md](README.md)
- [BUILD-CN.md](BUILD-CN.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)
