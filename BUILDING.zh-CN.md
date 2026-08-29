# Minis for Android 构建说明（中文翻译）

> [BUILDING.md](BUILDING.md) 是权威英文构建文档。本文件只作为中文翻译；如果版本号、命令或行为与英文文档冲突，以英文文档和实际构建脚本为准。

## 当前工具链

- Linux / WSL2 推荐；
- JDK 17；
- Gradle Wrapper 8.11.1；
- Android Gradle Plugin 8.10.1；
- Kotlin 2.1.0；
- compileSdk 36 / targetSdk 35 / minSdk 26；
- Android NDK r28+；
- CMake 3.22.1；
- Rust stable + `aarch64-unknown-linux-musl`。

## 1. 克隆

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android
```

当前运行时不需要初始化 Git submodule。

## 2. 配置 Android 与 Provider 自定义值

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

不要提交真实 API Key、OAuth token、Provider 私有标识、签名密钥或其他凭据。

## 3. 构建 `minisd`

```bash
rustup target add aarch64-unknown-linux-musl
cargo build --locked --release \
  --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml
```

## 4. 构建 Ubuntu rootfs

```bash
./scripts/build-ubuntu-rootfs.sh
```

脚本会下载并校验固定 SHA-256 的 Ubuntu 24.04 arm64 base rootfs。

## 5. 构建 Debug APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

产物：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK/AAB 只作为本地或 CI 构建产物，不应提交到 Git。

## 6. 测试

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:lintRelease --no-daemon
./gradlew :app:assembleDebugAndroidTest --no-daemon
```

Rust：

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

文档来源隔离检查：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

## 7. Release 签名

Release 构建必须显式提供正式签名配置：

```bash
export RELEASE_KEYSTORE=/absolute/path/to/release.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS='...'
export RELEASE_KEY_PASSWORD='...'
```

然后：

```bash
cd src/android
./gradlew :app:assembleRelease --no-daemon
```

缺少签名配置时 Release gate 必须失败，不能回退使用 Android debug key。

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

guest 复用 Android 内核，并以 App guest UID 运行。workspace / memory / skills / shared 的 host 目录位于 App 私有 files 目录，再由 `minisd` bind mount 到 chroot。

更多内容：

- [README.md](README.md)
- [README.zh-CN.md](README.zh-CN.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
