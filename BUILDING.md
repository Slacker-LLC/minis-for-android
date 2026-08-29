# Building Minis for Android

This is the primary build guide for the current `master` branch. The public repository currently distributes source code rather than production APK releases.

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
| Android NDK | r28+ |
| CMake | 3.22.1 |
| Rust | stable + `aarch64-unknown-linux-musl` target |

## Build `minisd`

```bash
rustup target add aarch64-unknown-linux-musl
cargo build --locked --release \
  --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml
```

## Build the Ubuntu rootfs

```bash
./scripts/build-ubuntu-rootfs.sh
```

The script downloads the pinned Ubuntu 24.04 arm64 base archive and verifies its SHA-256 digest.

## Build a debug APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

Output: `src/android/app/build/outputs/apk/debug/app-debug.apk`.

## Tests and lint

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:lintRelease --no-daemon
```

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
bash scripts/test-build-ubuntu-rootfs-verification.sh
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

## Release builds

Release signing is fail-closed. Provide `RELEASE_KEYSTORE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD`, then run:

```bash
cd src/android
./gradlew :app:assembleRelease --no-daemon
```

## Runtime notes

```text
Android kernel
  ↓
minisd
  ↓
mount namespace + bind mounts + chroot
  ↓
Ubuntu 24.04 userspace
```

The guest reuses the Android kernel and runs with the app guest UID. On the current `master` baseline, workspace/memory/skills/shared host directories are resolved from the app private files directory and bind-mounted into the chroot.

## Related documents

- [README.md](README.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [CONTRIBUTING.md](CONTRIBUTING.md)
