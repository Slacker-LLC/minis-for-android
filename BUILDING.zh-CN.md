# Minis for Android 构建说明（中文翻译）

> [BUILDING.md](BUILDING.md) 是权威英文构建文档。本文件只作为中文翻译；如果版本号、命令或行为与英文文档冲突，以英文文档和实际构建脚本为准。

## 当前工具链

- Linux / WSL2 推荐；
- JDK 17；
- Gradle Wrapper 8.11.1；
- Android Gradle Plugin 8.10.1；
- Kotlin 2.1.0；
- compileSdk 36 / targetSdk 35 / minSdk 26；
- Android NDK 27.0.12077973（Android 构建脚本固定版本）；
- CMake 3.22.1；
- Rust stable + `aarch64-linux-android`。

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
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

不要提交真实 API Key、OAuth token、Provider 私有标识、签名密钥或其他凭据。

## 3. 构建 `minisd`

Android Gradle 构建会自动用 `aarch64-linux-android` 编译 broker，校验
Android PIE / 16 KB ELF，并把产物放入 `lib/arm64-v8a/libminisd.so`。可执行
文件不会从 `assets` 解包，也不会复制到 `/data/adb/minis/bin`。

```bash
rustup target add aarch64-linux-android
cd src/android
./gradlew :app:verifyMinisdElf --no-daemon
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

`assembleDebug` 和 `assembleRelease` 会自动校验 APK 内的
`lib/arm64-v8a/libminisd.so`、runtime manifest SHA-256，以及 assets 是否混入
原生可执行文件。

rootfs 恢复会把已校验归档登记到 `/data/adb/minis/runtime/rootfs/versions`，
并通过 `current` 指针作为 native 运行时入口；`/data/adb/minis/rootfs` 只保留
为旧安装兼容回退。升级失败会尝试恢复旧 rootfs，且不会删除
`workspace`、`memory`、`skills`、`shared` 和 `home` 等持久数据。

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
