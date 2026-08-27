# Minis for Android

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#从源码构建)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#从源码构建)
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

> 当前主架构已经移除 Alpine + PRoot 和旧 Web Remote / Cloudflare Tunnel。仓库目前只维护源码，不在 Git 中提交 APK，也不保留旧版本 Release / tag 作为当前产品入口。

## 当前开发状态

当前 `master` 是持续开发分支。版本号只用于 Android 构建兼容，不代表存在对应的 GitHub Release。

- applicationId: `dev.openminispet.android`
- minSdk: 26
- 当前主要 ABI: `arm64-v8a`
- 发布状态: **源码开发阶段，无预编译 APK 发布**

详细状态见 [docs/DEVELOPMENT-STATUS.md](docs/DEVELOPMENT-STATUS.md)，已知问题见 [Issues](https://github.com/Slacker-LLC/minis-for-android/issues)。

## 主要能力

### Agent 与工具

- 多 Provider、模型组、OAuth/API Key、图片输入、会话历史和工具调用；
- Goal、Todo、Plan、Job、Subagent、提问/反馈、Token 计量、上下文压力和结果落盘；
- 文件读写、编辑、浏览器、记忆、Android 系统工具和 Linux shell；
- 工具超时、审批、执行检查点、危险命令策略和大输出治理。

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

这是一个高权限 Agent 项目，`minisd`、Root、MCP 和 Android 系统工具都属于关键安全边界。

当前仍有 P0/P1 安全、可靠性和发布工程问题在收口，因此仓库不提供“可直接安装的正式版”承诺。

- [安全设计](docs/SECURITY.md)
- [当前开发状态](docs/DEVELOPMENT-STATUS.md)
- [Open issues](https://github.com/Slacker-LLC/minis-for-android/issues)

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

APK 只作为本地构建产物生成：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

不要把 APK、AAB 或其他大体积构建产物提交到 Git。

安装本地构建：

```bash
adb install -r src/android/app/build/outputs/apk/debug/app-debug.apk
```

详细步骤：

- [中文构建说明](BUILD-CN.md)
- [English build guide](BUILDING.md)

## 仓库结构

| 路径 | 内容 |
|---|---|
| `src/android/` | Android App、Compose UI、Room、Provider、MCP、Tool Runtime |
| `src/native/minisd/` | Rust Root Broker |
| `src/shared/` | 当前构建仍使用的共享规则/资源 |
| `scripts/` | rootfs、构建和维护脚本 |
| `docs/` | 当前架构、安全、状态、规格和归档文档 |
| `assets/` | README/项目展示资源 |
| `.github/` | Issue / PR 模板与 CI 配置 |

文档索引见 [docs/README.md](docs/README.md)。

## 文档规则

当前事实来源优先级：

```text
运行源码与测试
  > 当前架构 / 安全文档
  > README / CHANGELOG
  > archive / upstream 历史资料
```

如果文档与当前 `master` 实现冲突，以源码和测试为准。

## 贡献者

- ChatGPT (OpenAI)
- Claude (Anthropic)

完整贡献说明见 [CONTRIBUTORS.md](CONTRIBUTORS.md)。

## 起源与许可证

本项目代码谱系包含 [OpenMinis](https://github.com/OpenMinis/OpenMinis)（GPL-3.0）及其他开源组件。第三方来源和许可证见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) 与 [README-upstream.md](README-upstream.md)。

本项目整体按 [GPL-3.0](LICENSE) 分发。分发自行构建或修改后的 APK 时应同时满足对应源码提供义务。
