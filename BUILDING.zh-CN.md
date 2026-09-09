# Minis for Android 构建说明

> 产品行为以 [AGENTS.md](AGENTS.md) 与 [docs/contracts/](docs/contracts/00-IDENTITY.md) 为准。构建命令与工具链以本文件、[BUILDING.md](BUILDING.md) 和实际脚本为准；三者冲突时以脚本为准。

## 当前工具链

- Linux / WSL2 推荐；
- JDK 17；
- Gradle Wrapper 8.11.1；
- Android Gradle Plugin 8.10.1；
- Kotlin 2.1.0；
- compileSdk 36 / targetSdk 35 / minSdk 26；
- Android NDK 28.2.13676358 或更高版本；
- CMake 3.22.1；
- Rust stable + `aarch64-linux-android`。

Gradle、Root 网络代理与 rclone 默认使用 NDK `28.2.13676358`。验证其它已安装版本时统一设置 `MINIS_NDK_VERSION`；若另外指定 `ANDROID_NDK_HOME`，必须指向同一版本。

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
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PATH="$HOME/.cargo/bin:$PATH"

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

不要提交真实 API Key、OAuth token、Provider 私有标识、签名密钥或其他凭据。

## 3. 构建 Root 网络代理

打包前还需构建并导入 rclone（包含 arm64-v8a 与 x86_64）：

```bash
bash deps/build_rclone_android.sh
mkdir -p src/android/app/libs
cp deps/build/rclone/rclone.aar src/android/app/libs/rclone.aar
```

Root 网络代理只负责 `127.0.0.1:18787` 的 HTTP/CONNECT 出站转发，不提供 RPC 或任意 Root 命令接口：

```bash
rustup target add aarch64-linux-android
bash scripts/build-root-network-proxy-android.sh
```

## 4. 构建 Ubuntu rootfs

```bash
./scripts/build-ubuntu-rootfs.sh
```

脚本会下载并校验固定 SHA-256 的 Ubuntu 24.04 arm64 base rootfs，并生成可复现的 rootfs 归档。

一次生成完整且经过验证的 APK 运行时载荷：

```bash
bash scripts/build-runtime-payload.sh
```

该脚本只输出 rootfs payload：`dist/ubuntu-arm64-rootfs.tar.gz` 和 `dist/runtime-manifest.json`。Root 网络代理由 `build-root-network-proxy-android.sh` 独立构建和校验，Gradle 以 `libminisnetproxy.so` 单独打包；CI 组装 APK 时两者都必须存在。

## 5. 构建 Debug APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

产物：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Debug APK 使用本机持久化的固定调试签名，不会把签名材料提交到仓库。首次构建时会自动生成一次：Windows 与 WSL 共用 `C:\Users\<用户名>\.minis\debug.keystore`；其他 Linux 环境使用 `~/.minis/debug.keystore`。也可以通过 `MINIS_DEBUG_KEYSTORE` 指定路径。该签名只用于 Debug，Release 仍必须使用显式的正式签名配置。

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

Root 网络代理 Rust：

```bash
cargo fmt --manifest-path src/native/root-network-proxy/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/root-network-proxy/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/root-network-proxy/Cargo.toml
```

Rootfs 校验：

```bash
bash scripts/test-build-ubuntu-rootfs-verification.sh
bash scripts/test-runtime-payload-verification.sh
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
Android App
  ↓
ExecutionCoordinator → RootPersistentShell → UbuntuKernel
  ↓
su → unshare -m → bind mount → chroot
  ↓
setpriv(App UID, capabilities=none) → Ubuntu 24.04 bash
```

App 直接持有会话 shell。Root 仅用于 mount/chroot/rootfs 维护和 loopback-only 出站代理；普通 guest 命令不会以 UID 0 执行。旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home}` 数据只做一次迁移，rootfs reset 不得删除用户数据。

更多内容：

- [README.md](README.md)
- [README.zh-CN.md](README.zh-CN.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/SECURITY.md](docs/SECURITY.md)
