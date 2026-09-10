# Building Minis for Android

Behavioral contracts are Chinese: [AGENTS.md](AGENTS.md) and [docs/contracts/](docs/contracts/00-IDENTITY.md). This file is the build and toolchain guide only.

This is the primary build guide for the current `main` branch. The public repository currently distributes source code rather than production APK releases.

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
| Android NDK | 28.2.13676358 or newer |
| CMake | 3.22.1 |
| Rust | stable + `aarch64-linux-android` target |

Gradle, the Root network proxy and rclone default to NDK `28.2.13676358`. Set
`MINIS_NDK_VERSION` consistently when validating another installed version;
explicit `ANDROID_NDK_HOME` overrides for shell builds must point to that version.
Rebuild `deps/build/rclone/rclone.aar` and copy it to `src/android/app/libs/`
after changing Go dependencies or native build flags. The binding includes both
arm64-v8a and x86_64; the Root network proxy/Ubuntu runtime remains arm64-only.

After packaging, run `bash scripts/verify-android-16k.sh <apk>` to check ZIP/ELF
alignment and required JNI ABI coverage. For an AAB, set `BUNDLETOOL_JAR` and run
`bash scripts/verify-android-bundle.sh <aab>` to validate its generated APK.

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
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

Do not commit real provider identifiers, OAuth material, API keys, signing keys, or tokens.

Some integrations require build-time provider customization that is intentionally absent from the public repository. Public builds must treat unavailable integrations explicitly rather than relying on hidden/private values.

## 3. Build the Root network proxy

```bash
rustup target add aarch64-linux-android
bash scripts/build-root-network-proxy-android.sh
```

This binary has one responsibility: expose `127.0.0.1:18787` so App-UID Ubuntu guests can use Root egress on Android/VPN configurations that restrict non-Root outbound sockets. It has no RPC or command-execution API.

## 4. Build the Ubuntu rootfs

```bash
./scripts/build-ubuntu-rootfs.sh
```

The script downloads the pinned Ubuntu 24.04 arm64 base archive, verifies its SHA-256 digest, and creates a reproducible rootfs archive.

To produce the complete verified APK payload in one command, run:

```bash
bash scripts/build-runtime-payload.sh
```

This writes the rootfs-only payload: `dist/ubuntu-arm64-rootfs.tar.gz` and `dist/runtime-manifest.json`. Build `scripts/build-root-network-proxy-android.sh` separately; Gradle stages its verified ELF independently as `libminisnetproxy.so`. CI requires both the rootfs payload and proxy for assembled APKs.

## 5. Build a debug APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

Output:

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Debug APKs use a persistent local debug keystore so repeated builds and Windows/WSL builds remain upgradeable. The keystore is generated once at `C:\Users\<username>\.minis\debug.keystore` on Windows/WSL or `~/.minis/debug.keystore` on other Linux hosts, and is never committed. Set `MINIS_DEBUG_KEYSTORE` to override the path. This key is Debug-only; Release still requires explicit production signing credentials.

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

Root network proxy Rust quality checks:

```bash
cargo fmt --manifest-path src/native/root-network-proxy/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/root-network-proxy/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/root-network-proxy/Cargo.toml
```

Rootfs verification:

```bash
bash scripts/test-build-ubuntu-rootfs-verification.sh
bash scripts/test-runtime-payload-verification.sh
```

Documentation provenance checks:

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
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
- final release APK verification;
- documentation provenance separation.

## Runtime notes

The rooted-device Linux path is:

```text
Android App
  ↓
ExecutionCoordinator → RootPersistentShell → UbuntuKernel
  ↓
su → unshare -m → bind mounts → chroot
  ↓
setpriv(App UID, no capabilities) → Ubuntu 24.04 bash
```

The App owns persistent user data and per-session shells. Root is used only for mount/chroot/rootfs maintenance and the loopback-only outbound network proxy. Existing data under `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}` is migrated into App-owned storage; resetting the rootfs must not delete user data.

## Troubleshooting

### Rust target missing

```bash
rustup target add aarch64-linux-android
```

### Android NDK missing

Set `ANDROID_NDK_HOME` to NDK `28.2.13676358` or newer, with the directory containing `toolchains/llvm/prebuilt`.

### Ubuntu runtime is unavailable on device

Verify root access and the installed runtime paths under `/data/adb/minis/`, then inspect direct Ubuntu/runtime and Root network proxy application logs. Do not disable SELinux globally as a troubleshooting step.

### Provider flow is unavailable

Check whether the integration requires a build-time customization value that is intentionally absent from the public source configuration. The application should expose unavailable integrations explicitly instead of failing deep inside a request path.

## Related documents

- [README.md](README.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
