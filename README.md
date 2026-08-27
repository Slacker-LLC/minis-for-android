# Minis for Android

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/Slacker-LLC/minis-for-android/releases)
[![Release](https://img.shields.io/badge/release-v1.01--beta.2-blue)](https://github.com/Slacker-LLC/minis-for-android/releases/tag/v1.01-beta.2)
[![ABI](https://img.shields.io/badge/release%20ABI-arm64--v8a-orange)](#安装)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Minis for Android 是一个 Android 原生 AI Agent 运行时。Agent、会话、工具、数据库和 Android 能力都由 App 侧管理；Root 设备可使用 `minisd` 启动 Ubuntu 24.04 chroot 作为默认 Linux 执行环境，并通过 MCP 对外暴露受控工具能力。

```text
Android App
├─ Agent Loop / Room / Repository / Session state
├─ Tool Runtime / Android tools / Jobs / Goals / Subagents
├─ MCP Server + MCPProvider
├─ Voice / Assistant / Pet integrations
└─ minisd Root Broker
   └─ Ubuntu 24.04 chroot
```

> 当前主架构已经移除 Alpine + PRoot 和旧 Web Remote / Cloudflare Tunnel。历史资料只作为归档参考，不应作为当前实现说明。

## 当前版本

| 项目 | 值 |
|---|---|
| Release | [`v1.01-beta.2`](https://github.com/Slacker-LLC/minis-for-android/releases/tag/v1.01-beta.2) |
| Android version | `1.01-beta.2` (`versionCode 39`) |
| applicationId | `dev.openminispet.android` |
| Published ABI | `arm64-v8a` |
| APK | `OpenMinis-Pet-1.01-beta.2-arm64-debug.apk` |
| SHA-256 | `4158bdd821d5a9b6b48c950dc9568842ec7c8f630d9c35467a54bacdef4e9490` |

当前发布 APK 使用 Android Debug 签名，只适合开发、自测和源码对应验证，不是生产发布包。生产发布仍需要独立 release keystore、release 构建门禁和安全验收。

- [Release](https://github.com/Slacker-LLC/minis-for-android/releases/tag/v1.01-beta.2)
- [对应源码](https://github.com/Slacker-LLC/minis-for-android/tree/v1.01-beta.2)
- [发布说明](RELEASE-NOTES.md)
- [变更记录](CHANGELOG.md)

## 主要能力

### Agent 与工具

- 多 Provider、模型组、OAuth/API Key、图片输入、会话历史和工具调用；
- Goal、Todo、Plan、Job、Subagent、提问/反馈、Token 计量、上下文压力和结果落盘；
- 文件读写、编辑、浏览器、记忆、Android 系统工具和 Linux shell；
- 工具超时、审批、执行检查点、危险命令策略和大输出处理。

### Ubuntu chroot 与 Root Broker

当前默认 Linux 执行环境为：

```text
Android kernel
  ↓
minisd (root broker)
  ↓
unshare + mount + chroot
  ↓
Ubuntu 24.04 userspace
```

- `minisd` 位于 `src/native/minisd/`，与 App 同仓；
- Ubuntu rootfs 由 `scripts/build-ubuntu-rootfs.sh` 构建；
- rootfs 基础包是 Ubuntu Base，额外工具在设备端 provisioning；
- guest 复用 Android 内核，不是虚拟机，也没有独立 kernel；
- Root 后端面向 KernelSU / Magisk / APatch 等 `su` 环境；
- Shizuku / AXManager / Sui 属于独立的 Android privileged bridge，不等同于 Root。

完整说明见 [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)。

### MCP

- MCP Server 默认监听 `127.0.0.1:18789`；
- 使用 Bearer token 和工具级权限控制；
- 敏感远程工具可进入手机端确认流程；
- MCPProvider 可连接外部 MCP Server，并将工具注册到现有 Tool Runtime；
- 项目不再内置旧 Web Remote 或 Cloudflare Tunnel 服务。

### Android 集成

- 桌面宠物与悬浮窗；
- `ROLE_ASSISTANT` / VoiceInteraction 系统助手入口；
- 语音识别、TTS、Voice Call 相关模块；
- Accessibility、截图、logcat、APK 部署和设备诊断工具；
- Root / Shizuku / 系统 API 多后端能力探测。

## 安全状态

这是一个高权限 Agent 项目，尤其是 `minisd`、Root、MCP 和 Android 系统工具属于关键安全边界。

当前仓库已建立对应安全文档和问题跟踪，但仍存在待修复的安全/发布问题。不要把当前 Debug APK 当作生产安全版本。

- [安全设计](docs/SECURITY.md)
- [当前开发状态](docs/DEVELOPMENT-STATUS.md)
- [Open issues](https://github.com/Slacker-LLC/minis-for-android/issues)

## 安装

要求 Android 8.0（API 26）或更高版本。当前发布 APK 面向 arm64 设备。

```bash
adb install -r OpenMinis-Pet-1.01-beta.2-arm64-debug.apk
```

按需授予系统权限：悬浮窗、通知、电池优化豁免、系统助手、Accessibility、Shizuku 或 Root 等。HyperOS 等系统还可能需要单独允许后台运行和自启动。

## 从源码构建

推荐 Linux / WSL2，使用 JDK 17、Android SDK 36、NDK r28+、CMake 3.22.1 和 Rust 工具链。

仓库当前没有需要初始化的 Git submodule。

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"

rustup target add aarch64-unknown-linux-musl
cargo build --release --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml

./scripts/build-ubuntu-rootfs.sh

cd src/android
./gradlew :app:assembleDebug --no-daemon
```

APK 输出：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

详细步骤：

- [中文构建说明](BUILD-CN.md)
- [English build guide](BUILDING.md)

## 仓库结构

| 路径 | 内容 |
|---|---|
| `src/android/` | Android App、Compose UI、Room、Provider、MCP、Tool Runtime |
| `src/native/minisd/` | Rust Root Broker |
| `src/shared/` | Android/iOS 历史共享规则中仍被当前构建使用的资源 |
| `scripts/` | rootfs、构建和维护脚本 |
| `docs/` | 当前架构、安全、状态、规格和归档文档 |
| `assets/` | README/项目展示资源 |
| `releases/` | 与源码版本对应的发布产物记录 |
| `.github/` | Issue / PR 模板及后续 CI 配置 |

文档索引见 [docs/README.md](docs/README.md)。

## 文档规则

当前事实来源优先级：

```text
运行源码与测试
  > 当前架构/安全文档
  > README / Release Notes
  > archive / upstream 历史资料
```

如果文档与当前 `master` 实现冲突，以源码和测试为准，并请提交 Issue。

## 起源与许可证

本项目代码谱系包含 [OpenMinis](https://github.com/OpenMinis/OpenMinis)（GPL-3.0）及其他开源组件。第三方来源和许可证见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) 与 [README-upstream.md](README-upstream.md)。

本项目整体按 [GPL-3.0](LICENSE) 分发。分发修改后的 APK 时应同时满足对应源码提供义务。
