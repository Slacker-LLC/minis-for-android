# Minis for Android（中文说明）

> [README.md](README.md) 是英文主文档；本文件是中文说明，不单独定义不同的工程行为。

**Minis for Android** 是面向 Root Android 设备的 AI Agent Runtime。它由原生 Android App、Rust Root Broker `minisd`、Ubuntu 24.04 userspace、Android 原生工具、MCP、持久化 Agent 数据、任务/子 Agent、语音与设备级集成组成。

## 当前架构

```text
Android 原生 App
├─ Agent / Session / Room / Repository
├─ Provider / Model Runtime
├─ Tool Runtime / Approval / Checkpoint / Job
├─ Android 原生工具
├─ MCP Client + 本地 MCP Server
├─ Voice / Assistant / Overlay
└─ Unix socket RPC
   ↓
minisd Root Broker
   ↓
独立 mount namespace + bind mount + chroot
   ↓
Ubuntu 24.04 userspace
```

Android App 负责应用/数据库状态、Provider/Model 配置、工具权限、审批与运行时编排。`minisd` 负责特权 Linux 运行时边界，并在 keeper 的 mount namespace 建立前准备固定的持久化数据源。Agent 命令以 App guest UID 运行，不保留无限制 root 身份。

## Linux 持久化数据

固定 host 布局位于 `/data/adb/minis/`：

| Host 路径 | Guest / 运行时用途 |
|---|---|
| `/data/adb/minis/workspace` | `/workspace` |
| `/data/adb/minis/sessions` | 每 Session 的 workspace 数据源 |
| `/data/adb/minis/memory` | `/memory` |
| `/data/adb/minis/skills` | `/skills` |
| `/data/adb/minis/shared` | `/shared` |
| `/data/adb/minis/home` | `/home/minis` |

`minisd` 会在 keeper 启动前创建并验证这些数据源。持久化数据目录使用 guest UID/GID 与 `0700` 权限；非固定持久化来源以及位于 tmpfs 上的持久化来源都会被拒绝。

## 当前状态

仓库处于持续开发阶段，目前以源码分发为主，不承诺 `versionName` 必然对应 GitHub Release。

主要文档：

- [开发状态](docs/DEVELOPMENT-STATUS.md)
- [执行环境](docs/EXECUTION-ENVIRONMENT.md)
- [安全模型](docs/SECURITY.md)
- [构建说明](BUILDING.md)
- [文档索引](docs/README.md)
- [源码来源与法律归属](PROVENANCE.md)

## 主要能力

- 多 Provider / Model、OAuth/API Key、图片输入、流式输出与 Tool Call；
- 持久 Session、Memory、Skills、Goal、Todo、Job、Subagent；
- Android 原生工具、Accessibility、截图、日志、包管理和设备能力；
- 本地 MCP Server 与外部 MCP Provider；
- 语音识别、TTS、默认助手与 Overlay；
- `minisd` + Ubuntu 24.04 Linux 执行环境。

## Linux Runtime

```text
Android kernel
  ↓
minisd
  ↓
mount namespace + bind mount + chroot
  ↓
Ubuntu 24.04 userspace
```

rootfs 由 `scripts/build-ubuntu-rootfs.sh` 构建，并使用固定 SHA-256 校验来源。guest 与 Android 共用内核，不是虚拟机。

## 构建

[BUILDING.md](BUILDING.md) 是本仓库唯一权威的构建与发布说明，定义当前支持的工具链、Android/Rust/rootfs 入口、验证命令和 Release 签名规则。[BUILDING.zh-CN.md](BUILDING.zh-CN.md) 仅作为中文翻译。

不要从归档材料或历史上游文档直接复制旧构建命令到当前自动化；应先与 `BUILDING.md` 和实际构建文件核对。

## 文档规则

当前文档只描述 Minis for Android 的当前行为。历史架构统一放在 `docs/archive/`；源码来源、许可证和法律归属统一放在 [PROVENANCE.md](PROVENANCE.md) 与 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。

真实性优先级：

```text
源码与测试
  > 当前架构 / 安全文档
  > README / CHANGELOG
  > archive 历史资料
```

## 许可证与来源

Minis for Android 按 [GPL-3.0](LICENSE) 分发。源码来源、归属与历史关系见 [PROVENANCE.md](PROVENANCE.md)，第三方许可证见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
