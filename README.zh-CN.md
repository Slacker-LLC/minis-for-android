# Minis for Android

面向 **已 Root 的 Android 设备** 的 AI Agent Runtime。原生 Android App + Ubuntu 24.04 userspace，共用 Android 内核，不是虚拟机。App 直接持有运行时与会话生命周期。

**中文合同定义应保持的行为边界；最终目标分支源码和测试定义当前实现事实。** Agent 先读 [`AGENTS.md`](AGENTS.md) 与 [`docs/contracts/`](docs/contracts/00-IDENTITY.md)。带日期的历史审计基线和已确认缺口保留在 [`06-CURRENT-GAPS.md`](docs/contracts/06-CURRENT-GAPS.md)。

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

产品运行时是 **Root-only**。PRoot、Alpine 兼容层、旧特权 broker 和其它 userspace 模拟执行后端不属于当前产品运行时。

App 负责会话、Provider、工具权限与审批，并直接持有 Ubuntu shell 生命周期。Root 仅用于 rootfs、mount namespace、bind mount、chroot、受控 legacy 数据迁移，以及单一职责的 loopback 出站代理；普通 Guest 命令最终必须降到真实 App UID/GID 且清空 supplementary groups/capabilities。

## Android 身份

| 项 | 当前值 |
|---|---|
| `applicationId` | `llc.slacker.minis` |
| `namespace` / Kotlin 包根 | `com.openminis.app` |

`applicationId` 与代码 namespace 可以不同。当前没有为了“整洁”而全库迁移 Kotlin package 的要求。

## 持久化真源

现役 guest 用户数据由 App 私有目录持有，并从 `Context.filesDir` 派生：

| App-owned backing | Guest / 用途 |
|---|---|
| `minis/workspace` | 非 session 的 `/workspace` |
| `minis-sessions/<session_id>/workspace` | session `/workspace` |
| `minis-sessions/<session_id>/{attachments,offloads,browser}` | session 附件/异步产物/浏览器数据 |
| `minis-global/memory` | `/memory` |
| `minis-global/skills` | `/skills` |
| `minis-global/shared` | `/shared` |
| `minis-global/mcp-servers` | `/var/minis/mcp-servers` |
| `minis/home` | `/home/minis` |

Root-owned 现役 runtime state 只保留明确基础设施，其中 `/data/adb/minis/rootfs` 是可替换 Ubuntu rootfs，不是用户数据。

旧 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为一次性迁移源；成功写入 `.root-data-migrated-v1` 后不能继续作为 guest path/bind 的现役真源。详见 [`03-STORAGE-CONTRACT.md`](docs/contracts/03-STORAGE-CONTRACT.md) 与 [`07-OWNERSHIP-MIGRATION.md`](docs/contracts/07-OWNERSHIP-MIGRATION.md)。

## 网络

Ubuntu guest 的 HTTP/HTTPS 通过固定 `127.0.0.1:18787` 的独立 Root helper 出站，用于兼容部分 Android/VPN/BPF 对非 Root guest UID 的限制。该 helper 只提供有界 HTTP/CONNECT 转发，不提供命令、文件或通用 Root RPC。

真实 Root、SELinux、VPN/DNS 切换、mount namespace 和 OEM 生命周期行为仍需设备验收，CI 不能替代。

## 构建

源码分发，不承诺生产 APK / GitHub Release。权威步骤见 [BUILDING.zh-CN.md](BUILDING.zh-CN.md) / [BUILDING.md](BUILDING.md)。当前 runtime 细节见 [docs/EXECUTION-ENVIRONMENT.md](docs/EXECUTION-ENVIRONMENT.md)。

## 许可证

[GPL-3.0](LICENSE)。著作权与来源声明见 [PROVENANCE.md](PROVENANCE.md)，第三方见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
