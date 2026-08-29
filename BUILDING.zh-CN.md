# Minis for Android 构建说明（中文翻译）

> [BUILDING.md](BUILDING.md) 是权威英文构建文档。本文件只作为中文翻译；如有冲突，以英文文档和实际构建脚本为准。

## 当前工具链

Linux / WSL2、JDK 17、Gradle Wrapper 8.11.1、Android Gradle Plugin 8.10.1、Kotlin 2.1.0、compileSdk 36、targetSdk 35、minSdk 26、Android NDK r28+、CMake 3.22.1、Rust stable + `aarch64-unknown-linux-musl`。

## 构建 `minisd`

```bash
rustup target add aarch64-unknown-linux-musl
cargo build --locked --release \
  --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml
```

## 构建 Ubuntu rootfs

```bash
./scripts/build-ubuntu-rootfs.sh
```

脚本会下载并校验固定 SHA-256 的 Ubuntu 24.04 arm64 base rootfs。

## 构建 Debug APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

## 测试

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
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

## Release 签名

配置 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 后运行：

```bash
cd src/android
./gradlew :app:assembleRelease --no-daemon
```

## 当前 Linux 执行路径

```text
Android kernel
  ↓
minisd Root Broker
  ↓
mount namespace + bind mount + chroot
  ↓
Ubuntu 24.04 userspace
```

当前 `master` 基线中，workspace / memory / skills / shared 的 host 目录由 App 私有 files 目录解析，再 bind mount 到 chroot。

更多内容：

- [README.md](README.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
