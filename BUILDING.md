# Building Minis for Android

Behavioral contracts are defined by [AGENTS.md](AGENTS.md) and [docs/contracts/](docs/contracts/00-IDENTITY.md). This file is the build/toolchain guide. Source and build scripts remain authoritative for exact versions.

Chinese translation: [BUILDING.zh-CN.md](BUILDING.zh-CN.md)

## Toolchain

| Tool | Current configuration |
|---|---|
| Host | Linux or WSL2 recommended |
| JDK | 17 |
| Gradle | 8.11.1 wrapper |
| Android Gradle Plugin | 8.10.1 |
| Kotlin | 2.1.0 |
| compileSdk | 36 |
| targetSdk | 35 |
| minSdk | 26 |
| Android NDK | 28.2.13676358 or newer |
| CMake | 3.22.1 |
| Rust | stable + `aarch64-linux-android` target |

Gradle, the Android network-compatibility proxy, and rclone default to NDK `28.2.13676358`. Set `MINIS_NDK_VERSION` consistently when validating another version. Rebuild `deps/build/rclone/rclone.aar` and copy it to `src/android/app/libs/` after changing Go/native inputs.

The Android module includes `arm64-v8a` and `x86_64`. The Direct Ubuntu rooted-device runtime and network helper primarily target arm64 Android devices.

## 1. Clone

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android
```

To reproduce this development branch exactly:

```bash
git switch refactor/direct-ubuntu-runtime
```

No Git submodule initialization is required.

## 2. Configure Android and optional provider customization

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Never commit provider secrets, OAuth material, API keys, signing keys, or tokens.

## 3. Build native dependencies

Build rclone:

```bash
bash deps/build_rclone_android.sh
mkdir -p src/android/app/libs
cp deps/build/rclone/rclone.aar src/android/app/libs/rclone.aar
```

Build the loopback network compatibility helper:

```bash
rustup target add aarch64-linux-android
bash scripts/build-root-network-proxy-android.sh
```

The binary name and Android launcher retain `root-network-proxy` terminology because the current deployment can start it with privileged identity. The proxy protocol itself is not a Root/chroot primitive: it is a separate HTTP/CONNECT egress compatibility path for devices where App-UID guest sockets are restricted. It exposes no generic Root RPC or command API.

## 4. Build the Ubuntu rootfs/runtime payload

```bash
./scripts/build-ubuntu-rootfs.sh
bash scripts/build-runtime-payload.sh
```

The payload is rootfs-only:

```text
dist/ubuntu-arm64-rootfs.tar.gz
dist/runtime-manifest.json
```

The network helper is built/verified separately and staged by Gradle as `libminisnetproxy.so`.

## 5. Build Android

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

Debug APK:

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Install on an explicitly authorized device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug signing uses the persistent local Minis debug keystore (`C:\Users\<username>\.minis\debug.keystore` on Windows/WSL or `~/.minis/debug.keystore` on Linux unless `MINIS_DEBUG_KEYSTORE` overrides it). Release signing never falls back to this key.

## 6. Test and verify

Android:

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:lintRelease --no-daemon
./gradlew :app:assembleDebugAndroidTest --no-daemon
```

Runtime/build guards:

```bash
python3 scripts/test_build_cleanup_guard.py
python3 scripts/check_build_cleanup.py
bash scripts/test-build-ubuntu-rootfs-verification.sh
bash scripts/test-runtime-payload-verification.sh
bash scripts/check-runtime-package-boundary.sh
```

Network helper:

```bash
cargo fmt --manifest-path src/native/root-network-proxy/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/root-network-proxy/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/root-network-proxy/Cargo.toml
```

Documentation:

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

APK/AAB native alignment:

```bash
bash scripts/verify-android-16k.sh <apk>
BUNDLETOOL_JAR=/path/to/bundletool.jar bash scripts/verify-android-bundle.sh <aab>
```

## 7. Release

Release signing is fail-closed and requires all four variables:

```bash
export RELEASE_KEYSTORE=/absolute/path/to/release.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS='...'
export RELEASE_KEY_PASSWORD='...'

cd src/android
./gradlew :app:assembleRelease --no-daemon
```

Canonical CI covers rootfs/payload guards, network-helper Rust quality/build, rclone, Android unit tests, Debug/Release lint/build/package verification, 16 KiB checks, fail-closed signing, and documentation provenance.

## Runtime model

```text
Android App
  → ExecutionCoordinator → RootPersistentShell → UbuntuKernel
  → su → setsid → unshare -m → bind mounts → chroot
  → setpriv(real App UID/GID, clear groups/caps)
  → Ubuntu 24.04 bash
```

Root is for the minimum infrastructure required to establish the Direct Ubuntu environment. Guest commands are not Root commands. The network proxy is a separate compatibility component and should not be described as a prerequisite inherent to Root/chroot itself.

Physical-device claims about KernelSU/Magisk/APatch authorization, SELinux, mount semantics, VPN/DNS/BPF/Fake-IP behavior, and OEM process lifecycle require real-device validation.

Related: [README.md](README.md), [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md), [docs/SECURITY.md](docs/SECURITY.md), [CONTRIBUTING.md](CONTRIBUTING.md).
