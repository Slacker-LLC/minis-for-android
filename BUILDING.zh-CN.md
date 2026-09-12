# Minis for Android 构建说明

产品行为以 [AGENTS.md](AGENTS.md) 与 [docs/contracts/](docs/contracts/00-IDENTITY.md) 为准；精确工具链以实际 Gradle、脚本和本文件为准。

## 当前工具链

- Linux / WSL2 推荐；
- JDK 17；
- Gradle Wrapper 8.11.1；
- Android Gradle Plugin 8.10.1；
- Kotlin 2.1.0；
- compileSdk 36 / targetSdk 35 / minSdk 26；
- Android NDK 28.2.13676358 或更高；
- CMake 3.22.1；
- Rust stable + `aarch64-linux-android`。

Gradle、Android 网络兼容代理与 rclone 默认使用 NDK `28.2.13676358`。验证其它版本时统一设置 `MINIS_NDK_VERSION`，`ANDROID_NDK_HOME` 也必须指向对应版本。

## 1. 克隆与切换分支

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android
git switch main
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

不要提交 API Key、OAuth token、Provider 私有标识、签名密钥或其它凭据。

## 3. 构建 native 依赖

rclone：

```bash
bash deps/build_rclone_android.sh
mkdir -p src/android/app/libs
cp deps/build/rclone/rclone.aar src/android/app/libs/rclone.aar
```

网络兼容 helper：

```bash
rustup target add aarch64-linux-android
bash scripts/build-root-network-proxy-android.sh
```

当前二进制/Android 启动类沿用 `root-network-proxy` 命名，是因为现阶段部署可用特权身份建立出站 socket，以兼容部分 Android/VPN/BPF 对 App-UID guest 的限制。**代理协议本身并不依赖 Root，也不是 chroot 的必要组成部分。** 它只提供 `127.0.0.1:18787` HTTP/CONNECT 转发，不提供 shell、文件或通用 Root RPC。

## 4. 构建 Ubuntu rootfs/runtime payload

```bash
./scripts/build-ubuntu-rootfs.sh
bash scripts/build-runtime-payload.sh
```

runtime payload 只包含：

```text
dist/ubuntu-arm64-rootfs.tar.gz
dist/runtime-manifest.json
```

网络 helper 单独构建/校验，Gradle 以 `libminisnetproxy.so` 独立打包。

## 5. 构建 Debug APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

产物：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

安装到明确授权设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug 使用本机持久调试签名：Windows/WSL 默认 `C:\Users\<用户名>\.minis\debug.keystore`，Linux 默认 `~/.minis/debug.keystore`，可用 `MINIS_DEBUG_KEYSTORE` 覆盖。Release 不得回退使用 Debug key。

## 6. 测试与校验

以下各组命令均从仓库根目录开始；上一节进入了 `src/android` 时，先执行 `cd ../..`。Android 命令单独在子 shell 中运行：

```bash
(cd src/android && ./gradlew test --no-daemon)
(cd src/android && ./gradlew :app:lintDebug :app:lintRelease --no-daemon)
(cd src/android && ./gradlew :app:assembleDebugAndroidTest --no-daemon)
```

Runtime/build guard：

```bash
python3 scripts/test_build_cleanup_guard.py
python3 scripts/check_build_cleanup.py
bash scripts/test-build-ubuntu-rootfs-verification.sh
bash scripts/test-runtime-payload-verification.sh
bash scripts/check-runtime-package-boundary.sh
```

网络 helper：

```bash
cargo fmt --manifest-path src/native/root-network-proxy/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/root-network-proxy/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/root-network-proxy/Cargo.toml
```

文档：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

16 KiB：

```bash
bash scripts/verify-android-16k.sh <apk>
BUNDLETOOL_JAR=/path/to/bundletool.jar bash scripts/verify-android-bundle.sh <aab>
```

## 7. Release

Release 签名必须显式提供：

```bash
export RELEASE_KEYSTORE=/absolute/path/to/release.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS='...'
export RELEASE_KEY_PASSWORD='...'

cd src/android
./gradlew :app:assembleRelease --no-daemon
```

缺少正式签名时必须失败关闭。

## 当前 Linux 执行路径

```text
Android App
  → ExecutionCoordinator → RootPersistentShell → UbuntuKernel
  → su → setsid → unshare -m → bind mount → chroot
  → setpriv(真实 App UID/GID, clear groups/caps)
  → Ubuntu 24.04 bash
```

Root 只负责建立 Direct Ubuntu 环境所需的最小基础设施；普通 guest 命令不是 Root 命令。网络代理是独立兼容组件，不应再和 Root/chroot 本身写成同一概念。

KernelSU/Magisk/APatch、SELinux、mount namespace、VPN/DNS/BPF/Fake-IP 与 OEM 生命周期仍需真机验收。

更多内容：[README.zh-CN.md](README.zh-CN.md)、[docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)、[docs/SECURITY.md](docs/SECURITY.md)。

## 增量编译与内存

保留 Gradle 用户缓存、项目 `.gradle/`、`build/` 和未跟踪的 `rclone.aar`，普通改动不必运行 `clean`。新 worktree 不会自动继承未跟踪的 native 产物，需按第 3 节准备；不要把缓存、APK 或私有配置提交到 Git。

2026-09-12 合并验证中，冷构建的 Kotlin daemon 在 1.5 GiB 堆上内存不足；使用 Gradle 4 GiB、Kotlin 3 GiB、单 worker 后测试和 Debug 构建通过。内存充足的机器可从仓库根目录复用：

```bash
(cd src/android && ./gradlew test assembleDebug --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' \
  -Pkotlin.daemon.jvmargs=-Xmx3g)
```

两套 JVM 的堆上限不等于总内存需求，还需为系统、编译器和 native 工具预留空间；内存较小的机器不要照搬。文档修改只需跑文档守卫，不必重新冷编译整个 Android 项目。
