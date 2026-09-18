# Minis for Android

面向 **已 Root Android 设备** 的 AI Agent Runtime。原生 Android App + Ubuntu 24.04 userspace，共用 Android 内核，不是虚拟机。

**中文合同定义应保持的行为边界；最终目标分支源码与测试定义当前实现事实。** Agent 先读 [`AGENTS.md`](AGENTS.md) 与 [`docs/contracts/`](docs/contracts/00-IDENTITY.md)。

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](BUILDING.md)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-orange)](BUILDING.md)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

## 当前运行时

```text
Android 原生 App
  ↓
ExecutionCoordinator → RootPersistentShell → UbuntuKernel
  ↓
su → setsid → unshare -m → bind mount → chroot
  ↓
setpriv(真实 App UID/GID, clear groups, capabilities=none) → bash
  ↓
Ubuntu 24.04 userspace
```

当前产品运行时为 **Direct Ubuntu 24.04 chroot**。PRoot、Alpine 兼容层和旧特权 broker 不属于现役生产路径。

`DirectRootRunner` 只用于 rootfs、mount namespace、bind mount、chroot、受控 legacy 数据迁移等必要基础设施。普通 Guest 命令最终必须降到设备实际 App UID/GID，并清空 supplementary groups 与 Linux capabilities。本地 Agent 按上游权限模型可另外使用结构化 `root.shell`（`tool` basename + `args`）；它是 local-only、有限参数/时间/输出并负责进程清理的能力，不是 raw shell、主机文件 API 或通用 RPC，MCP 仍不可见。

## Android 身份

| 项 | 当前值 |
|---|---|
| `applicationId` | `llc.slacker.minis` |
| `namespace` / Kotlin 包根 | `com.openminis.app` |

`applicationId` 与代码 namespace 可以不同；当前没有为了整洁而全库迁移 Kotlin package 的要求。

## 持久化真源

现役 guest 用户数据从当前 `Context.filesDir` 派生：

| App-owned backing | Guest / 用途 |
|---|---|
| `minis/workspace` | 非 session `/workspace` |
| `minis-sessions/<session_id>/workspace` | session `/workspace` |
| `minis-sessions/<session_id>/{attachments,offloads,browser}` | session 附件/异步产物/浏览器数据 |
| `minis-global/memory` | `/memory` |
| `minis-global/skills` | `/skills` |
| `minis-global/shared` | `/shared` |
| `minis-global/mcp-servers` | `/var/minis/mcp-servers` |
| `minis/home` | `/home/minis` |

`/data/adb/minis/rootfs` 是 Root-owned、可替换 Ubuntu rootfs，不是用户数据。旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为一次性迁移源。

## 网络代理与 Root 的关系

`127.0.0.1:18787` 的 HTTP/CONNECT helper 是**独立的网络兼容组件**，不是 Root/chroot 的天然组成部分；HTTP/CONNECT 代理协议本身并不需要 Root。

当前 Android 实现让该 helper 可在特权身份下建立出站 socket，是为了兼容部分设备/VPN/BPF 对 App-UID guest 网络的限制。它不提供 shell、文件、插件或通用 Root RPC。即使将来某些设备可以让 guest 直接联网，Direct Ubuntu 的 Root/chroot 架构也不因此改变。

真实 Root、SELinux、VPN/DNS、BPF/Fake-IP、mount namespace 和 OEM 生命周期行为仍需真机验收，CI 不能替代。

## 构建与文档

Direct Ubuntu 审计改动已于 2026-09-12 合入 `main`（源码合并基线 `422cc29f`）。入口：[文档索引](docs/README.md)、[开发状态](docs/DEVELOPMENT-STATUS.md)、[真机实测报告](docs/REAL-DEVICE-TEST-REPORT.md)、[上游对账](docs/UPSTREAM-COMPARISON.md)。

基础包和 Android Guest 命令见[执行环境](docs/EXECUTION-ENVIRONMENT.md)。`minis-mcp-cli` 仍缺失，完整真机功能矩阵也未完成；[当前缺口](docs/contracts/06-CURRENT-GAPS.md)明确区分这些项目与已经通过的检查。

源码分发，不承诺生产 APK / GitHub Release。构建见 [BUILDING.zh-CN.md](BUILDING.zh-CN.md) / [BUILDING.md](BUILDING.md)。运行时见 [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)，安全边界见 [docs/SECURITY.md](docs/SECURITY.md)。

## 许可证

[GPL-3.0](LICENSE)。著作权与来源声明见 [PROVENANCE.md](PROVENANCE.md)，第三方声明见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
