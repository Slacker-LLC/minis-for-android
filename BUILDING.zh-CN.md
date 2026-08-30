# Minis for Android 构建说明

> 产品行为以 [AGENTS.md](AGENTS.md) 与 [docs/contracts/](docs/contracts/00-IDENTITY.md) 为准。构建命令与工具链以本文件、[BUILDING.md](BUILDING.md) 和实际脚本为准；三者冲突时以脚本为准。

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

可选 APK 运行时载荷：若存在 `dist/ubuntu-arm64-rootfs.tar.gz`，Gradle 会拷进 assets；若存在 `src/native/minisd/target/aarch64-linux-android/release/minisd`，会拷成 `libminisd.so`。纯源码构建不含这两项，设备上会 fail-closed。

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

Rootfs 校验：

```bash
bash scripts/test-build-ubuntu-rootfs-verification.sh
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

guest 复用 Android 内核，并以 App guest UID 运行。`minisd` 会在 keeper 启动前准备固定的 host 持久化数据源：

```text
/data/adb/minis/workspace
/data/adb/minis/sessions
/data/adb/minis/memory
/data/adb/minis/skills
/data/adb/minis/shared
/data/adb/minis/home
```

这些路径是固定运行时输入。启动时会拒绝其他持久化路径，并拒绝位于 tmpfs 上的持久化数据源。

更多内容：

- [README.md](README.md)
- [README.zh-CN.md](README.zh-CN.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
