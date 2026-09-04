# Minis for Android

面向 **已 Root 的 Android 设备** 的 AI Agent Runtime。原生 Android App + Rust `minisd` + Ubuntu 24.04 userspace，共用 Android 内核，不是虚拟机。

**中文合同定义应保持的行为边界；当前源码和测试定义 `master` 实际已经实现了什么。** Agent 先读 [`AGENTS.md`](AGENTS.md) 与 [`docs/contracts/`](docs/contracts/00-IDENTITY.md)，当前实现与合同的已确认差异统一记录在 [`06-CURRENT-GAPS.md`](docs/contracts/06-CURRENT-GAPS.md)。

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

## 当前运行时

```text
Android 原生 App
└─ Unix socket RPC
   ↓
minisd Root Broker
   ↓
独立 mount namespace + 显式 bind mount + chroot
   ↓
Ubuntu 24.04 userspace
```

产品运行时是 **Root-only**。PRoot、Alpine 兼容层和其它 userspace 模拟执行后端不属于当前产品运行时。

App 负责会话、Provider、工具权限与审批；`minisd` 负责特权 Linux 边界、持久化布局、namespace/mount/chroot 与 guest 执行。Guest 命令使用运行时取得的真实 App guest UID/GID，禁止写死 `10000`。

## Android 身份

当前 Gradle 已经是：

| 项 | 当前值 |
|---|---|
| `applicationId` | `llc.slacker.minis` |
| `namespace` / Kotlin 包根 | `com.openminis.app` |

`applicationId` 与代码 namespace 可以不同。当前没有为了“整洁”而全库迁移 Kotlin package 的要求。

## 持久化真源

根目录：`/data/adb/minis`。

| Host | Guest / 用途 |
|---|---|
| `/data/adb/minis/workspace` | `/workspace` |
| `/data/adb/minis/sessions` | 每 session 的 workspace/附件等 |
| `/data/adb/minis/memory` | `/memory` |
| `/data/adb/minis/skills` | `/skills` |
| `/data/adb/minis/shared` | `/shared` |
| `/data/adb/minis/home` | `/home/minis` |
| `/data/adb/minis/rootfs` | 可替换的 Ubuntu rootfs，不是用户数据 |

非合同路径、符号链接逃逸和 tmpfs-backed 用户数据必须 fail-closed。详见 [`03-STORAGE-CONTRACT.md`](docs/contracts/03-STORAGE-CONTRACT.md)。

## 当前状态

核心 Root/minisd/Ubuntu 与 `/data/adb/minis` 架构已经形成。当前确认需要修复的缺口以 [`06-CURRENT-GAPS.md`](docs/contracts/06-CURRENT-GAPS.md) 和 GitHub Issues 为准；不要从历史 PR、阶段计划或归档文档推断当前实现。

源码分发，不承诺生产 APK / GitHub Release。

- [身份](docs/contracts/00-IDENTITY.md)
- [架构](docs/contracts/01-ARCHITECTURE.md)
- [硬限制](docs/contracts/02-CONSTRAINTS.md)
- [存储](docs/contracts/03-STORAGE-CONTRACT.md)
- [安全](docs/contracts/04-SECURITY-CONTRACT.md)
- [工程规范](docs/contracts/05-ENGINEERING.md)
- [当前缺口](docs/contracts/06-CURRENT-GAPS.md)
- [属主校正](docs/contracts/07-OWNERSHIP-MIGRATION.md)
- [构建](BUILDING.zh-CN.md) / [BUILDING.md](BUILDING.md)

## 构建

```bash
cd src/android
./gradlew :app:assembleDebug --no-daemon
```

权威构建步骤见 [BUILDING.zh-CN.md](BUILDING.zh-CN.md)。

## 许可证

[GPL-3.0](LICENSE)。著作权与来源声明见 [PROVENANCE.md](PROVENANCE.md)，第三方见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
