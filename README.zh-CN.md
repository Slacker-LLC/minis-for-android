# Minis for Android（中文说明）

> English is the primary project documentation: [README.md](README.md). This Chinese document is a secondary translation and should not override the English specification.

**Minis for Android** 是一个基于上游开源项目 [OpenMinis](https://github.com/OpenMinis/OpenMinis) 开发的独立 Android AI Agent 项目。

项目保留 OpenMinis 的原生 Android Agent 基础，同时针对 Root 设备发展自己的执行架构：Rust `minisd` Root Broker、Ubuntu 24.04 chroot、Android 原生工具、MCP client/server、持久任务、子 Agent 和设备级集成。

本仓库不是 OpenMinis 官方 Android 发行仓库。上游关系、许可证与同步策略见 [UPSTREAM.md](UPSTREAM.md)。

## 当前架构

```text
Android 原生 App
├─ Agent Loop / Session / Room / Repository
├─ Provider / Model Runtime
├─ Tool Runtime / Approval / Checkpoint / Job
├─ Android 原生工具
├─ MCP Client + 本地 MCP Server
├─ Voice / Assistant / Overlay
└─ minisd Root Broker
   └─ unshare + mount + chroot
      └─ Ubuntu 24.04 userspace
```

Android App 是 Agent 状态、会话、工具权限、Provider 配置和持久化数据的唯一权威来源。MCP 是集成接口，不是第二套 Agent Runtime。

## 与上游 OpenMinis 的主要差异

- Root 设备默认走 Ubuntu 24.04 chroot，而不是 Alpine + PRoot；
- 使用 `minisd` 作为结构化 Root Broker；
- 增加本地 MCP Server 与外部 MCP Provider；
- 扩展 Android 原生系统工具；
- 增加 Goal / Todo / Job / Subagent / Approval / Checkpoint 等运行时能力；
- CI 覆盖 Android 单测、Debug/Release Lint、Debug/Release 构建、Release 签名校验、Rust 质量检查和 rootfs 校验；
- 不再维护旧 Web Remote / Cloudflare Tunnel 运行时。

## 当前状态

仓库处于持续开发阶段，目前以源码分发为主，不把 Android `versionName` 视为对应 GitHub Release 的承诺。

主要文档：

- [英文主 README](README.md)
- [构建说明](BUILDING.md)
- [中文构建说明](BUILDING.zh-CN.md)
- [开发状态](docs/DEVELOPMENT-STATUS.md)
- [执行环境](docs/EXECUTION-ENVIRONMENT.md)
- [安全模型](docs/SECURITY.md)
- [上游关系](UPSTREAM.md)

## 构建

推荐 Linux / WSL2。完整、权威的构建参数以 [BUILDING.md](BUILDING.md) 和 Gradle/Rust 源码为准。

```bash
git clone https://github.com/Slacker-LLC/minis-for-android.git
cd minis-for-android

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties

rustup target add aarch64-unknown-linux-musl
cargo build --release --target aarch64-unknown-linux-musl \
  --manifest-path src/native/minisd/Cargo.toml

./scripts/build-ubuntu-rootfs.sh

cd src/android
./gradlew :app:assembleDebug --no-daemon
```

## 文档规则

当前项目文档以英文为主。中文文档只作为翻译，不单独定义工程行为。

真实性优先级：

```text
源码与测试
  > 当前架构 / 安全文档
  > README / CHANGELOG
  > archive 历史资料
```

## 许可证

本项目基于 OpenMinis 开发，并继续按 [GPL-3.0](LICENSE) 分发。第三方许可证见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
