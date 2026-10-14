# 开发状态

> 核对更新：2026-10-14，`main` 当前未提交工作树（HEAD `3527ad8165f11c0f98d8a30f96098fc9280faa94`）。当前主线是 `Slacker-LLC/minis-for-android` 的 `main`；Eta 能力已经并入这个独立产品。本文的运行时结论仍是 Direct Ubuntu 24.04 chroot、App-owned 数据与结构化 `root.shell`。M1–M5 开发及本轮受控集成验收已完成：17 组工作流、迁移与 force-stop 恢复通过，Debug/Release Kotlin/R8/Lint 通过。历史基线：Direct Ubuntu 审计提交 `53dada42` 经合并提交 `422cc29f` 进入旧主线（2026-09-12）；真机记录保留各自测试范围，不用宿主构建或模拟器替代设备验收。逐片进度见 [`development/PROGRESS.md`](development/PROGRESS.md)，Agent 重整见 [总体路线](development/AGENT-ROADMAP.md)，项目全貌见 `PROJECT.md`。

## 项目状态

- 仓库：`Slacker-LLC/minis-for-android`
- 当前主线：`main`
- 基线快照：Direct Ubuntu 运行时结论沿用 `main` 上的 2026-09-12 审计；移植与收敛进度见 `development/PROGRESS.md`
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

## Agent 重整（当前工作树）

- M1：AgentRunner、AgentSessionStore 与 AgentSession 持有唯一执行核心，聊天页面只保留输入/显示投影。模型循环、工具批次、持久化及 offload 位于 `agent/AgentLoop.kt`、`agent/ToolRound.kt`、`agent/AgentPersistence.kt`、`agent/ContextOffloader.kt`，不再由 `ui.chat` 持有执行实现。
- M2：持久 Goal/预算/接续/验收复用 Task；完整文件、后台 Job/子代理终态及真实用户现场核验独立结算动作，原生入口覆盖已停止任务和 legacy；UNKNOWN 不重放、不隐式完成/恢复。
- M3：ToolExecutor 与既有领域后端共用受信执行事实，浏览器 CLI 和认证桥接保留 typed UNKNOWN；后台接受、观察与确定终态分开。
- M4：SAF URI 证据、核验导出、明确全员共享、成员背景/共享引用及 Task/检查点备份已接入。恢复暂停、复核后显式继续，原预算/历史事实不重置。
- M5：Room v26 在既有消息表持久接单；稳定去重、CAS 领取、恢复待审、历史编辑保留待投递输入。子代理回执绑定原 Task/Run/停止代次，过期结果不唤醒；例程先接受后报告启动，Ralph 保存父子关联和交接。默认接待、最近语音对象、任务选择/阶段/等待原因/成果入口与声音控制已接入。

**M1–M5 开发与本轮受控集成验收已完成**。按用户要求先整体开发，再集中验收；17 组工作流与实际进程恢复通过，已通过的检查不机械重复。最新行为和验收范围见 [整体接线](development/M-SERIES-INTEGRATION.md)；旧切片记录保持各自历史范围，真实模型、Root/OEM 与生产签名另需证据。

## Guest CLI 状态

上游的 `android-*` / `minis-*` 命令在 PRoot 路径中由 `/usr/local/bin` stub 触发 `native_offload`；当前 Direct Root 不恢复这套机制。现役实现由 `GuestCommandBridge` 在 Ubuntu 启动/恢复时按 `NativeOffloadServer` 的注册表生成带 token 鉴权的 loopback wrapper，所以命令入口和 Android handler 都存在，但 Root 权限边界没有扩大。

小米 `24129PN74C` 真机已经验证命令入口和代表性实际调用：全部已注册 Android/Minis handler 命令可由 `command -v` 找到，`android-device info` 返回设备 JSON，多个 `--help`/`--version` 正常返回。完整清单与限制见 [`REAL-DEVICE-TEST-REPORT.md`](REAL-DEVICE-TEST-REPORT.md)。**Guest 的 `minis-mcp-cli` 仍缺失**，Android 原生 MCP client/server 不能替代这个 shell 命令；但 `MCPRepository.mcpPromptFragment()` 已改为引导模型直接调用已注册的 `mcp_<server>_<tool>`，不再推荐该 CLI。提示词不一致已修正，Guest CLI 入口及在 Ubuntu Guest 中启动 STDIO 服务的能力仍未补齐；详见[当前缺口](contracts/06-CURRENT-GAPS.md)。

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

历史分支基线（2026-10-08，Debug；不代表当前工作树重新通过）：`:app:testDebugUnitTest` **3379 例 0 失败**；`:app:lintDebug` **0 error**（394 warning / 7 hint 为既有，记录在 `app/lint-baseline.xml`）；模拟器 instrumentation 套件通过；`:app:assembleDebug` 产出含 runtime payload 的 APK。2026-10-08 在小米手机上抽查过：选中文字工具栏与引用卡片、统一朗读条、发送按钮的强调色、特殊权限的一键授予、聊天文件浏览器；模型错误文案只有单元测试，没有在手机上触发过。其余设备缺口仍按下节处理。

2026-10-12/13 的 Agent 切片另有受控 Provider 配合真实 App、Room、文件、WebView/ContentProvider 和进程恢复的模拟器 instrumentation 工件；各切片 Debug/Release Kotlin、R8、Lint 等结果以相应冻结报告为准，不表示当前工作树重新完成全构建或生产签名 Release 验收。本次仅核对源码并同步文档，不重跑 Android 构建或设备测试。

合并提交 `422cc29f` 的宿主 JVM 测试与 `assembleDebug` 已通过。合并后的 APK 没有额外完成一轮全功能真机验收；下列设备缺口不因合并或后续模拟器切片通过而自动关闭。

## 仍需补齐的验证边界

CI **不能**证明真机运行。以下项目仍需要明确的 Root 真机证据：

- 目标 Root 方案的授权拒绝/允许分支；
- 设备 SELinux 下完整的 `su → unshare → mount/bind → chroot → setpriv`；
- 真实 App UID/GID 的 owner、读写和每个 session workspace 一致性；
- 手机重启恢复、APK 升级保留数据、多个 Terminal session；
- VPN/DNS/BPF/Fake-IP，包括代表性配置下 Guest 的 `curl` / `apt`；
- OEM 后台策略下的进程/服务生命周期。

GitHub Issue 是独立工作队列。Issue 正文可能保留历史 runtime 术语，必须重新对照最终 Direct Ubuntu 源码后，才能当作当前实现事实。
