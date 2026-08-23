# Minis for Android 构建说明

本文对应当前 `master`。项目只构建 Android `arm64-v8a`。

## 已验证环境

| 工具 | 版本/要求 |
|---|---|
| 操作系统 | Linux 或 WSL2 |
| JDK | 17 |
| Gradle | Wrapper 8.11.1 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Android SDK | compileSdk 36,targetSdk 35,minSdk 26 |
| NDK | r28+(当前构建使用 `28.0.13004108`) |
| CMake | 3.22.1 |
| Node.js | 22+(仅 Minis Web Client Plugin 构建需要) |
| ABI | `arm64-v8a` |

Windows 原生的非 ASCII/NTFS 路径可能触发 Gradle、CMake、符号链接和性能问题,推荐 WSL 的
ASCII 路径。

## 1. 克隆源码

```bash
git clone https://github.com/limuzi013/minis-for-android.git
cd OpenMinis-Pet
```

原生依赖 `src/native/minisd`(Rust Root Broker)与 App 同仓,无 submodule。

## 2. 配置 SDK 与定制

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"  # 按本机版本调整
export PATH="$HOME/.cargo/bin:$PATH"                     # Rust 工具链(交叉编译用)

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties
```

空值对 API Key 类 Provider 合法;需要省略 OAuth 定制值的功能会在运行时显式失败。

## 3. 构建沙箱资源

```bash
# minisd Root Broker(需 rustup target add aarch64-unknown-linux-musl)
cargo build --release --target aarch64-unknown-linux-musl \
    --manifest-path src/native/minisd/Cargo.toml

# Ubuntu 24.04 arm64 rootfs 打包(下载 + SHA-256 校验 + tar 打包)
./scripts/build-ubuntu-rootfs.sh
```

- 第一条交叉编译 minisd(静态 musl,无 Android 运行时依赖),产出到
  `src/native/minisd/target/aarch64-unknown-linux-musl/release/minisd`;
- 第二条下载固定 Ubuntu 24.04 arm64 base、校验 SHA-256、预装 python3/git
  并打包为 rootfs tar;
- 生成的 minisd 与 Ubuntu rootfs 产物不提交 Git,干净 checkout 后必须重新生成。
- PRoot/Alpine 依赖(P2 已拆除)不再需要:`deps/build_proot.sh` 与
  `scripts/prepare_android_sandbox.sh` 已随 P2 移除。

## 4. 构建 Minis Web Client Plugin

「Minis 控制台」是正式 DeepSeek Harness Client Plugin;源码在 `web/minis-client-plugin/`,
生成的 `client.js` 随 APK assets 分发。

```bash
cd web/minis-client-plugin
npm install
npm run check    # tsc --noEmit
npm test         # vitest
npm run build    # 生成 plugins/@openminis/minis-client-settings/client.js
                 # 并更新 assets/minis/index.html 的 boot graph
```

`npm run build` 是更新浏览器 bundle 与 boot graph 的唯一受支持方式;禁止手工修改生成的
`client.js` 或 `__MINIS_BOOT__` JSON。

## 5. 构建 APK

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

输出:`src/android/app/build/outputs/apk/debug/app-debug.apk`

安装到已连接的 arm64 设备:

```bash
./gradlew :app:installDebug
# 或
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. 测试

```bash
cd src/android
./gradlew :app:testDebugUnitTest \
  --tests com.openminis.app.remote.DshApiAdapterTest \
  --tests com.openminis.app.data.UpdateCheckerVersionTest

./gradlew :app:assembleDebugAndroidTest
```

只有明确授权的设备才运行 `./gradlew :app:connectedDebugAndroidTest`。38 项 Provider 测试需要
公开仓库不提供的 OAuth 定制值或网络 fixture;不要为了绿跑删除它们。

## 常见问题

### cargo 交叉编译失败

先 `rustup target add aarch64-unknown-linux-musl`,并确认 `$HOME/.cargo/bin` 在 PATH。
minisd 静态链接 musl,不需要 Android NDK。

### 找不到 Android NDK

将 `ANDROID_NDK_HOME` 指向包含 `toolchains/llvm/prebuilt` 的 r28+ 目录。

### App 能启动但沙箱命令失败

检查 minisd 是否已安装到 `/data/adb/minis/bin/minisd`(root 设备)与 Ubuntu rootfs
是否在 `/data/adb/minis/rootfs`。App 在 `UbuntuRuntime.ensureReady()` 时会自动经 su
拉起 minisd watchdog,无需手动常驻。执行一条 shell 命令并断言退出码为 0:

```bash
adb shell su -c "/data/adb/minis/bin/minisd --call --socket /data/adb/minis/run/minisd.sock"
# 输入: {"v":1,"method":"ubuntu.status","client":{"id":"adb","capabilities":["ubuntu.status"]}}
```

### 更改 versionName/versionCode

编辑 `src/android/app/build.gradle.kts` 中的 `versionCode`/`versionName`,重新构建,并把 APK
与哈希同步到 `releases/README.md` 与 `RELEASE-NOTES.md`。

## 发布要求

当前 `release` 构建类型仍使用 debug 签名,debug APK 会启动 DebugServer,只适合开发自测。
生产发布需要长期保管的 release keystore、不启动 DebugServer 的 release 变体、完整安全验收、
不可变源码 tag 与已发布的产物哈希。

许可证与来源见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
