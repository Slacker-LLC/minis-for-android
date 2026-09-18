# 开发状态

> 更新：2026-09-12。Direct Ubuntu 审计提交 `53dada42` 已通过合并提交 `422cc29f` 进入 `main` 并推送 origin。本文以该源码基线为准；真机记录保留各自测试范围，不用合并后的宿主构建替代设备验收。

## 项目状态

- 仓库：`Slacker-LLC/minis-for-android`
- 参考分支：`main`（Direct Ubuntu 已合入）
- PR #235：2026-09-10 已合入 `main`
- 平台：已 Root 的 Android 设备
- Linux runtime：Android App 自有协调 + Ubuntu 24.04 Direct chroot
- `applicationId`：`llc.slacker.minis`
- Android/Kotlin namespace：`com.openminis.app`
- 发布方式：源码优先

## 当前架构

```text
Android App
├─ Agent / session / Room / Provider / 工具 / MCP / 语音
└─ ExecutionCoordinator / App-owned shell 生命周期
   ↓
UbuntuKernel / DirectRootRunner
   ↓
su → setsid → unshare -m → 显式 bind mount → chroot
   ↓
setpriv（真实 App UID/GID、清空 supplementary groups、丢弃 capabilities）
   ↓
Ubuntu 24.04 userspace
```

旧特权 broker 不再是现役源码、构建或运行时组件；PRoot/Alpine 兼容栈也不再是现役后端。

`root.shell` 是结构化、local-only 的 Agent 能力：只接受 Android 可执行文件 basename 和 argv，只解析可信系统目录，并由 Direct Root launcher 负责超时和进程清理。它对 MCP 隐藏，不是 raw command、宿主文件系统入口或通用 RPC。Direct Root 基础设施脚本仍由 App 构造。

## Guest CLI 状态

上游的 `android-*` / `minis-*` 命令在 PRoot 路径中由 `/usr/local/bin` stub 触发 `native_offload`；当前 Direct Root 不恢复这套机制。现役实现由 `GuestCommandBridge` 在 Ubuntu 启动/恢复时按 `NativeOffloadServer` 的注册表生成带 token 鉴权的 loopback wrapper，所以命令入口和 Android handler 都存在，但 Root 权限边界没有扩大。

小米 `24129PN74C` 真机已经验证命令入口和代表性实际调用：全部已注册 Android/Minis handler 命令可由 `command -v` 找到，`android-device info` 返回设备 JSON，多个 `--help`/`--version` 正常返回。完整清单与限制见 [`REAL-DEVICE-TEST-REPORT.md`](REAL-DEVICE-TEST-REPORT.md)。**Guest 的 `minis-mcp-cli` 仍缺失**，Android 原生 MCP client/server 不能替代这个 shell 命令。`MCPRepository.mcpPromptFragment()` 仍提示调用它，属于尚未闭合的命令/提示词不一致；详见[当前缺口](contracts/06-CURRENT-GAPS.md)。

## 存储

`/data/adb/minis/rootfs` 是 Root-owned、可替换的 runtime state。现役 Guest 用户数据由 App 持有，并从 `Context.filesDir` 派生，包括全局 workspace/home、memory/skills/shared/MCP 数据，以及每个 session 的 workspace/attachments/offloads/browser backing。

历史 `/data/adb/minis/{workspace,sessions,memory,skills,shared,home,mcp-servers}` 只作为一次性迁移源。迁移标记是 `<filesDir>/minis/.root-data-migrated-v1`，只有全部复制成功后才写入。

## 网络兼容与 Root/chroot 分离

当前构建包含固定监听 `127.0.0.1:18787` 的 HTTP/CONNECT helper。它是网络兼容组件，不是 chroot 的属性，也不是 Root 存在的理由。

HTTP/CONNECT 代理协议本身不要求 Root。某些 VPN/BPF 配置限制 App UID Guest 出站时，Android 部署可以让 helper 以特权身份建立出站 socket；helper 没有 shell、文件、插件或通用 RPC 接口。

真机网络仍是验收项：VPN/TUN 切换、DNS 选择、`198.18.0.0/15` Fake-IP、Android BPF/UID policy 和不同 OEM 行为不能由宿主 CI 证明。

## Runtime payload / native 产物

- Rootfs payload：`ubuntu-arm64-rootfs.tar.gz` + 仅包含 rootfs 信息的 `runtime-manifest.json`；
- 网络 helper：独立构建的 arm64 native 产物，打包为 `libminisnetproxy.so`；
- rclone AAR：独立的 Android 依赖/产物；
- 旧 broker 的二进制、socket 和 runtime package 身份由构建/打包守卫拒绝。

## 验证状态

PR #235 合入前，Direct Ubuntu 迁移已经通过既有 CI，包括 rootfs/payload、网络 helper Rust 质量与测试、rclone、Android 单测、Debug/Release lint/build/package、16 KiB 检查、签名失败关闭和 bundle APK 校验。

2026-09-12 的审计又补充了当前工作树和小米真机证据：设备 `24129PN74C`/HyperOS 已安装 Debug APK，进入 Direct Ubuntu Terminal，报告动态 App UID/GID，完成 workspace 文件读写，并在关闭时回收 Terminal shell；还执行了强停后的冷启动、MiMo v2.5 文本和 TTS 请求。详细记录见 [`REAL-DEVICE-TEST-REPORT.md`](REAL-DEVICE-TEST-REPORT.md)，上游/共享功能对账见 [`UPSTREAM-COMPARISON.md`](UPSTREAM-COMPARISON.md)。

该设备的 HyperOS 拒绝安装 instrumentation APK，因此真机手测不能替代 instrumentation 执行证据。

合并提交 `422cc29f` 的宿主 JVM 测试与 `assembleDebug` 已通过。合并后的 APK 没有额外完成一轮全功能真机验收；下列设备缺口不因合并而自动关闭。

## 仍需补齐的验证边界

CI **不能**证明真机运行。以下项目仍需要明确的 Root 真机证据：

- 目标 Root 方案的授权拒绝/允许分支；
- 设备 SELinux 下完整的 `su → unshare → mount/bind → chroot → setpriv`；
- 真实 App UID/GID 的 owner、读写和每个 session workspace 一致性；
- 手机重启恢复、APK 升级保留数据、多个 Terminal session；
- VPN/DNS/BPF/Fake-IP，包括代表性配置下 Guest 的 `curl` / `apt`；
- OEM 后台策略下的进程/服务生命周期。

GitHub Issue 是独立工作队列。Issue 正文可能保留历史 runtime 术语，必须重新对照最终 Direct Ubuntu 源码后，才能当作当前实现事实。
