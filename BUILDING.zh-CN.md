# Minis for Android 构建说明（中文翻译）

> [BUILDING.md](BUILDING.md) 是权威英文构建文档。本文件为中文同步版；若出现冲突，以构建脚本、Gradle 和 CI 为准。

## 当前工具链

- Linux / WSL2 推荐；
- JDK 17；
- Gradle Wrapper 8.11.1；
- Android Gradle Plugin 8.10.1；
- Kotlin 2.1.0；
- compileSdk 36 / targetSdk 35 / minSdk 26；
- Android NDK 27.0.12077973；
- CMake 3.22.1；
- Rust stable + `aarch64-linux-android`。

Android App 本身包含 `arm64-v8a` 和 `x86_64` ABI；Issue #51 当前分发的特权 Ubuntu 运行时只支持 arm64。

## 1. 克隆与配置

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.0.12077973"
export PATH="$HOME/.cargo/bin:$PATH"
```

可选 Provider 本地配置：

```bash
cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

不要提交真实 API Key、OAuth material、Provider 私有标识、签名密钥或其他凭据。

## 2. 运行时发行合同

每个生产 APK 管理一套匹配的运行时身份：

- `lib/arm64-v8a/libminisd.so`，由 Package Manager 安装到 `ApplicationInfo.nativeLibraryDir`；
- `assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz`；
- schema v2 的 `assets/minis-runtime/runtime-manifest.json`。

manifest 必须声明真实的 minisd SHA-256、最终 rootfs tar SHA-256、protocol version 1、layout version 2、`arm64-v8a`、Ubuntu release/profile/upstream SHA、正整数 provision revision，以及必需 guest 命令。

rootfs 版本格式固定为：

```text
ubuntu-24.04-rN-<最终 tar SHA-256 前 16 位>
```

Debug 与 Release 使用同一个 rootfs producer、manifest generator 和 APK 校验链。`managed` 等占位身份无效。

生产环境不会把第二份 broker 可执行文件部署到 `/data/adb/minis`。Android 直接启动 Package Manager 管理的 `libminisd.so`，使用按 App UID 隔离的 Linux abstract socket、内联 policy JSON，以及绑定 App PID + procfs start-time 的 lease。filesystem socket 与 `--policy PATH` 只能在显式 `--dev-filesystem-ipc` 的 standalone 开发模式中使用。

## 3. 构建固定 Ubuntu 24.04 rootfs

仓库固定 Ubuntu Base 24.04.3，并同时校验仓库内 pin 与上游 SHA256SUMS：

```bash
bash scripts/build-ubuntu-rootfs.sh
```

输出：

```text
dist/ubuntu-arm64-rootfs.tar.gz
dist/ubuntu-arm64-rootfs.tar.gz.sha256
dist/ubuntu-arm64-rootfs.manifest.json
```

最终归档采用排序、固定 tar metadata 和无时间戳 gzip，因此相同输入应得到相同字节与 SHA。`ROOTFS_REVISION`、`PROVISION_REVISION` 必须是正整数，默认都为 `1`。

直接构建 APK 时不要求人工先执行 rootfs 脚本。Gradle 的 `:app:packageRuntimeAssets` 显式依赖 `buildPinnedUbuntuRootfs`，因此缺少 `dist/ubuntu-arm64-rootfs.tar.gz` 时会先生成，再打包 runtime assets。CI 已经生成的相同输出可被 Gradle 作为 up-to-date 产物复用。

验证：

```bash
bash scripts/test-build-ubuntu-rootfs-verification.sh
```

该测试覆盖 checksum/revision fail-closed，以及相同输入连续构建两次的可重复性。

## 4. 构建和验证 `minisd`

```bash
rustup target add aarch64-linux-android
cd src/android
./gradlew :app:verifyMinisdElf --no-daemon
```

Gradle 会编译 `src/native/minisd`，检查 Android arm64 PIE / 16 KB ELF，并把精确产物放入 `lib/arm64-v8a/libminisd.so`。

Rust 质量检查：

```bash
cargo fmt --manifest-path src/native/minisd/Cargo.toml --all -- --check
cargo clippy --locked --manifest-path src/native/minisd/Cargo.toml --all-targets -- -D warnings
cargo test --locked --manifest-path src/native/minisd/Cargo.toml
```

## 5. 构建 APK

Debug：

```bash
cd src/android
./gradlew :app:assembleDebug :app:verifyDebugMinisdApk --no-daemon
```

输出：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

Release 必须显式提供正式签名配置：

```bash
export RELEASE_KEYSTORE=/absolute/path/to/release.jks
export RELEASE_STORE_PASSWORD='...'
export RELEASE_KEY_ALIAS='...'
export RELEASE_KEY_PASSWORD='...'

cd src/android
./gradlew :app:assembleRelease :app:verifyReleaseMinisdApk --no-daemon
```

缺少签名配置时 Release gate 必须失败，不能回退到 Android debug key。

Debug/Release 共用的 APK runtime verifier 会核对 minisd 与 rootfs 两个实际 payload SHA，并拒绝旧的外置 broker/runtime 路径。Release 还有独立签名、package 和 schema-v2 校验：

```bash
bash scripts/verify-android-release.sh \
  src/android/app/build/outputs/apk/release/app-release.apk
```

不要再通过 `adb push` 单独部署 broker。

## 6. Android 测试与 lint

```bash
cd src/android
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:lintDebug --no-daemon
./gradlew :app:lintRelease --no-daemon
./gradlew :app:assembleDebugAndroidTest --no-daemon
```

`assembleDebugAndroidTest` 会编译 installed-layout 真机门禁。该 instrumentation 在 arm64 设备上会检查 `ApplicationInfo.nativeLibraryDir/libminisd.so` 是否真实存在、可执行、SHA 与 APK manifest 一致，同时核对 APK 内 rootfs asset SHA。CI 只负责编译该测试，不能替代 Root/KernelSU 真机执行。

Runtime package boundary：

```bash
bash scripts/check-runtime-package-boundary.sh
```

文档来源隔离：

```bash
python3 scripts/test_docs_provenance.py
python3 scripts/check_docs_provenance.py
```

## 7. 安装后的运行时生命周期

```text
Android App
  ↓ abstract Unix socket RPC
Package Manager-owned libminisd.so
  ↓
private mount namespace + bind mounts + chroot
  ↓
Ubuntu 24.04 userspace
```

可替换的版本化系统运行时位于：

```text
/data/adb/minis/runtime/rootfs/versions/
/data/adb/minis/runtime/rootfs/current
/data/adb/minis/runtime/rootfs/previous
/data/adb/minis/runtime/rootfs/pending
```

新 rootfs 必须先完整解压和校验，之后才执行对外可见的 `current` 切换。切换后的 health/provision 失败会尝试恢复 `previous`；`pending` 用于发现并恢复中断事务。

用户持久数据与 rootfs 系统状态分离，唯一持久化真源为：

```text
/data/adb/minis/workspace
/data/adb/minis/sessions
/data/adb/minis/memory
/data/adb/minis/skills
/data/adb/minis/shared
/data/adb/minis/home
```

这些目录在 keeper namespace 创建之前准备，路径固定，并拒绝 tmpfs 持久化 backing。旧 App 私有目录只作为一次迁移输入；迁移完成后以上 `/data/adb/minis/*` 目录就是持久化数据源。

## 8. CI 验收链

PR CI 独立检查：

- Rust fmt / Clippy / tests / host release / arm64 compatibility build；
- rootfs fail-closed、reproducibility、runtime boundary、diff hygiene；
- documentation provenance；
- pinned rootfs 实际构建与身份校验；
- Android unit tests、Debug/Release lint；
- Debug APK 构建和 runtime 校验；
- installed-layout instrumentation 编译；
- Release Kotlin、APK 构建、runtime 校验、签名/package 校验和最终 diff hygiene。

CI 全绿只证明源码、构建、打包和静态/单测链。Root/KernelSU、SELinux enforcing 下持久化、真实首装/升级/回滚、force-stop 生命周期以及 physical-device installed-layout 仍必须通过真机门禁。

更多内容：

- [README.md](README.md)
- [PROVENANCE.md](PROVENANCE.md)
- [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)
- [docs/runtime-package-boundary.md](docs/runtime-package-boundary.md)
- [docs/SECURITY.md](docs/SECURITY.md)
