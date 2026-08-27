# Minis for Android 构建说明

本文对应当前 `master`。发布 APK 目前面向 Android `arm64-v8a`。

## 环境要求

| 工具 | 版本/要求 |
|---|---|
| 操作系统 | Linux 或 WSL2 推荐 |
| JDK | 17 |
| Gradle | Wrapper 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Android SDK | compileSdk 36 / targetSdk 35 / minSdk 26 |
| Android NDK | r28+（当前配置示例 `28.0.13004108`） |
| CMake | 3.22.1 |
| Rust | stable + `aarch64-unknown-linux-musl` target |
| Shell 工具 | bash、curl、tar、awk、sed、sha256sum |

Windows 原生的非 ASCII/NTFS 路径可能触发 Gradle、CMake、符号链接或性能问题，推荐在 WSL2 的 ASCII 路径中构建。

## 1. 克隆源码

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android
```

当前仓库没有需要初始化的 Git submodule。`minisd` 源码位于 `src/native/minisd/`。

## 2. 配置 Android 与 Provider 定制文件

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # 按本机版本调整
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

公开仓库中的定制值可以为空。部分依赖私有 OAuth 定制值的功能在未配置时不可用；不要把空值当成生产配置。

## 3. 构建 minisd

```bash
rustup target add aarch64-unknown-linux-musl

cargo build --release \
  --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml
```

输出：

```text
src/native/minisd/target/aarch64-unknown-linux-musl/release/minisd
```

`minisd` 使用 musl 静态链接，不依赖 Android NDK 来完成这一步交叉编译。

## 4. 构建 Ubuntu rootfs

```bash
./scripts/build-ubuntu-rootfs.sh
```

脚本会下载 Ubuntu 24.04 arm64 Ubuntu Base，叠加 Minis 所需目录结构并打包 rootfs。

注意：当前构建脚本生成的是 **base-only rootfs**。`python3`、`git`、`curl` 等额外工具不是在构建机里预装进 tar，而是在设备端 provisioning 阶段安装。

PRoot/Alpine 架构已经删除，不再需要 `deps/build_proot.sh` 或 `scripts/prepare_android_sandbox.sh`。

## 5. 构建 APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

输出：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

安装：

```bash
./gradlew :app:installDebug
# 或
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. 测试

```bash
cd src/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebugAndroidTest
```

只有在明确授权的设备上才运行：

```bash
./gradlew :app:connectedDebugAndroidTest
```

部分 Provider 测试依赖公开仓库不包含的 OAuth 定制值或网络 fixture。不要为了让未配置环境全绿而删除测试。

## 常见问题

### cargo 交叉编译失败

```bash
rustup target add aarch64-unknown-linux-musl
```

并确认 `$HOME/.cargo/bin` 已加入 `PATH`。

### Android NDK 找不到

确认 `ANDROID_NDK_HOME` 指向实际安装的 r28+ 目录，并包含：

```text
toolchains/llvm/prebuilt
```

### App 能启动但 Ubuntu shell 失败

Root 设备上检查：

```text
/data/adb/minis/bin/minisd
/data/adb/minis/rootfs
```

App 会在 `UbuntuRuntime.ensureReady()` 流程中拉起 minisd watchdog。

可使用 minisd RPC 检查状态：

```bash
adb shell su -c "/data/adb/minis/bin/minisd --call --socket /data/adb/minis/run/minisd.sock"
```

然后输入：

```json
{"v":1,"method":"ubuntu.status","client":{"id":"adb","capabilities":["ubuntu.status"]}}
```

## 发布说明

当前 `release` 构建类型仍存在发布安全待办，公开发布 APK 也是 Debug 签名开发包。生产发布至少需要：

- 独立且长期保管的 release keystore；
- 禁止 DebugServer/Debug-only 能力进入生产 APK；
- release lint、CI 和测试门禁；
- 固定源码 tag 与产物 SHA-256；
- 独立安全验收。

相关问题见仓库 Issues。

更多资料：

- [README.md](README.md)
- [BUILDING.md](BUILDING.md)
- [docs/SECURITY.md](docs/SECURITY.md)
- [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)
