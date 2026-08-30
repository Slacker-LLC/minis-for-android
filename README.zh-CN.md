# Minis for Android

面向 **已 Root 的 Android 设备** 的 AI Agent Runtime。原生 App + Rust `minisd` + Ubuntu 24.04 userspace（共用 Android 内核，不是虚拟机）。

**中文规范是行为定义。** 合同在 [`docs/contracts/`](docs/contracts/00-IDENTITY.md)，Agent 宪法在 [`AGENTS.md`](AGENTS.md)。英文 [README.md](README.md) 只是摘要。

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

## 目标架构

```text
Android 原生 App
└─ Unix socket RPC
   ↓
minisd Root Broker
   ↓
独立 mount namespace + bind mount + chroot
   ↓
Ubuntu 24.04 userspace
```

App 负责会话、Provider、工具权限与审批。`minisd` 负责特权 Linux 边界，并在 keeper 启动前准备 `/data/adb/minis` 下的固定持久化目录。Guest 命令以 App UID 运行。

完整职责与启动顺序：[01-ARCHITECTURE.md](docs/contracts/01-ARCHITECTURE.md)。

## 持久化真源（合同）

| Host | Guest |
|---|---|
| `/data/adb/minis/workspace` | `/workspace` |
| `/data/adb/minis/sessions` | 每 session |
| `/data/adb/minis/memory` | `/memory` |
| `/data/adb/minis/skills` | `/skills` |
| `/data/adb/minis/shared` | `/shared` |
| `/data/adb/minis/home` | `/home/minis` |

非上述路径以及 tmpfs backing 必须 fail-closed。详见 [03-STORAGE-CONTRACT.md](docs/contracts/03-STORAGE-CONTRACT.md)。

**现状：** master 上 App 与 minisd 对真源尚未对齐，见 [06-CURRENT-GAPS.md](docs/contracts/06-CURRENT-GAPS.md)。不要把合同当成「已经在每台设备上如此运行」。

## 当前状态

源码分发，不承诺生产 APK / GitHub Release。目标包名 `llc.slacker.minis` 已冻结，Gradle 尚未切换。

- [身份](docs/contracts/00-IDENTITY.md)
- [硬限制](docs/contracts/02-CONSTRAINTS.md)
- [安全合同](docs/contracts/04-SECURITY-CONTRACT.md)
- [工程规范](docs/contracts/05-ENGINEERING.md)
- [构建](BUILDING.zh-CN.md) / [BUILDING.md](BUILDING.md)
- [法律来源](PROVENANCE.md)

## 能力（产品范围）

多 Provider、流式与工具调用；Session / Memory / Skills / Job / Subagent；Android 原生工具与设备能力；MCP；语音与助手；`minisd` + Ubuntu 24.04。

需要 Root。chroot 不是强沙箱。

## 构建

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

权威步骤见 [BUILDING.md](BUILDING.md)。

## 许可证

[GPL-3.0](LICENSE)。著作权与来源声明见 [PROVENANCE.md](PROVENANCE.md)，第三方见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
