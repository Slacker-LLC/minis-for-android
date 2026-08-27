# Building Minis for Android

This is the primary build guide for the current `master` branch. The public repository currently distributes source code rather than production APK releases.

Chinese translation: [BUILDING.zh-CN.md](BUILDING.zh-CN.md)

## Toolchain

The build files are authoritative. At the time of writing the repository uses:

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
| Android NDK | r28+ |
| CMake | 3.22.1 |
| Rust | stable + `aarch64-unknown-linux-musl` target |

The Android module currently includes `arm64-v8a` and `x86_64` in its ABI filters. Rooted-device runtime work primarily targets arm64 Android devices.

## 1. Clone

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android
```

No Git submodule initialization is required for the current runtime.

## 2. Configure Android and optional provider customization

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # adjust to your installation
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Do not commit real provider identifiers, OAuth material, API keys, signing keys, or tokens.

Some integrations require build-time provider customization that is intentionally absent from the public repository. Public builds must treat unavailable integrations explicitly rather than relying on hidden/private values.

## 3. Build `minisd`

```bash
rustup target add aarch64-unknown-linux-musl

cargo build --locked --release \
  --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml
```

`minisd` is the Rust root broker used by the rooted-device execution path.

## 4. Build the Ubuntu rootfs

```bash
./scripts/build-ubuntu-rootfs.sh
```

The script downloads the pinned Ubuntu 24.04 arm64 base archive and verifies its SHA-256 digest before packaging the project rootfs layout.

This project does not use the upstream Alpine + PRoot runtime as its active Android execution backend.

## 5. Build a debug APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

Output:

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Install on an authorized device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or:

```bash
./gradlew :app:installDebug
```

APKs and AABs are build artifacts and must not be committed to Git.

## 6. Run tests and lint

Android unit tests:

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
```

Lint:

```bash
./gradlew :app:lintDebug --no-daemon
./gradlew :app:lintRelease --no-daemon
```

Instrumentation package:

```bash
./gradlew :app:assembleDebugAndroidTest --no-daemon
```

Connected tests should only run on an explicitly authorized emulator or device.

Rust quality checks:

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

Rootfs verification:

```bash
bash scripts/test-build-ubuntu-rootfs-verification.sh
```

## 7. Release builds

Release signing is fail-closed. A production release package requires all four environment variables:

```bash
export RELEASE_KEYSTORE=/absolute/path/to/release.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS='...'
export RELEASE_KEY_PASSWORD='...'
```

Then:

```bash
cd src/android
./gradlew :app:assembleRelease --no-daemon
```

Without explicit release credentials, the release-signing gate must fail. Debug signing is not accepted as a release fallback.

The repository CI validates:

- Rust format, Clippy, tests, and release build;
- rootfs checksum failure paths;
- Android unit tests;
- Debug and Release lint;
- Debug packaging;
- Release Kotlin compilation and packaging;
- fail-closed release signing;
- final release APK verification.

## Runtime notes

The rooted-device Linux path is:

```text
Android kernel
  ↓
minisd
  ↓
unshare + mount + chroot
  ↓
Ubuntu 24.04 userspace
```

The guest reuses the Android kernel and runs with the app guest UID. Host workspace/memory/skills/shared directories are created under the app's private files directory and bind-mounted into the chroot.

## Troubleshooting

### Rust target missing

```bash
rustup target add aarch64-unknown-linux-musl
```

### Android NDK missing

Set `ANDROID_NDK_HOME` to an installed r28+ directory containing `toolchains/llvm/prebuilt`.

### Ubuntu runtime is unavailable on device

Verify root access and the installed runtime paths under `/data/adb/minis/`, then inspect `minisd` status and application logs. Do not disable SELinux globally as a troubleshooting step.

### Provider flow is unavailable

Check whether the integration requires a build-time customization value that is intentionally absent from the public source configuration. The application should expose unavailable integrations explicitly instead of failing deep inside a request path.

## Related documents

- [README.md](README.md)
- [UPSTREAM.md](UPSTREAM.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
