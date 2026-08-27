# 开发状态

本文记录当前 `master` 的工程状态。先阅读根目录 [README](../README.md)。

## 仓库状态

- Repository: `https://github.com/Slacker-LLC/minis-for-android`
- Default branch: `master`
- applicationId: `dev.openminispet.android`
- Android build metadata: `1.01-beta.2` (`versionCode 39`)
- Distribution: **source-only；当前不维护 GitHub Release、Git tag 版本入口或仓库内 APK**

Android 的 `versionName/versionCode` 暂时仍作为构建/升级元数据存在，不表示仓库有同名公开发行版。

## 当前主架构

```text
Android App
├─ Agent Runtime / Room / Repository
├─ Tool Runtime / Android tools / Jobs / Goals / Subagents
├─ MCP Server / MCPProvider
├─ Voice / Assistant / Pet integrations
└─ minisd Root Broker
   └─ Ubuntu 24.04 chroot
```

已经退出当前主架构的历史路径：

- Alpine + PRoot；
- 旧 Web Remote HTTP Server；
- 内置 Cloudflare Tunnel 前端/控制面。

历史资料只能作为 archive/upstream 参考，不应作为当前实现说明。

## 已实现

### Agent Runtime

- 多 Provider、会话、图片输入、工具调用；
- Goal / Todo / Plan / Job / Subagent；
- 工具超时、审批、执行检查点、Token 计量、上下文压力和大结果落盘；
- 文件、浏览器、记忆、Linux 和 Android 工具统一进入 Tool Runtime。

### MCP

- MCP Server 默认回环监听；
- Bearer token 与工具级 caller 权限；
- 敏感工具可进入手机端确认；
- MCPProvider 连接外部 HTTP/stdio MCP Server，并将工具注册到现有 Runtime。

### Android

- Compose UI、Room、Provider 设置与会话状态；
- Accessibility、截图、logcat、APK 部署和诊断工具；
- 桌面宠物、系统助手/VoiceInteraction、语音相关模块；
- Root、Shizuku/AXManager/Sui 等多后端能力探测与调用。

### Linux / Root

- `src/native/minisd/` Rust Root Broker；
- Ubuntu 24.04 chroot；
- App 侧 Ubuntu runtime 位于 `src/android/app/src/main/java/com/openminis/app/sandbox/ubuntu/`；
- rootfs 由 `scripts/build-ubuntu-rootfs.sh` 构建；
- 当前 rootfs 是 Ubuntu Base skeleton，额外工具在设备端 provisioning。

## 当前高优先级问题

当前能力已经超过普通 App 的权限规模，工程重点应从继续扩功能转向安全和可靠性收口。

### P0

- [#2 Prevent minisd runtime policy self-escalation](https://github.com/Slacker-LLC/minis-for-android/issues/2)
- [#3 Remove arbitrary root process spawning via restartCloudflared](https://github.com/Slacker-LLC/minis-for-android/issues/3)
- [#7 Add CI gates for Android, minisd and release checks](https://github.com/Slacker-LLC/minis-for-android/issues/7)
- [#8 Stop signing release builds with the Android debug key](https://github.com/Slacker-LLC/minis-for-android/issues/8)

### P1

- [#4 Framed/concurrent minisd IPC](https://github.com/Slacker-LLC/minis-for-android/issues/4)
- [#5 Fail-closed Ubuntu rootfs verification](https://github.com/Slacker-LLC/minis-for-android/issues/5)
- [#6 Bound root.exec output](https://github.com/Slacker-LLC/minis-for-android/issues/6)
- [#9 Restore release lint](https://github.com/Slacker-LLC/minis-for-android/issues/9)
- [#10 Contain cleartext HTTP](https://github.com/Slacker-LLC/minis-for-android/issues/10)
- [#11 Correct foreground-service lifecycle/type](https://github.com/Slacker-LLC/minis-for-android/issues/11)
- [#12 Explicit provider-customization capability state](https://github.com/Slacker-LLC/minis-for-android/issues/12)

这些 Issue 是当前工程状态的一部分；在它们关闭前，不应建立 production-ready 分发入口。

## 已知设备/平台限制

- HyperOS 等 OEM 在未授予后台运行/自启动时可能冻结后台网络和 Agent 任务；
- 系统角色、Accessibility、悬浮窗、电池豁免、SAF、Shizuku 和 Root 均需要用户在系统侧明确授权；
- Root 能力受 provider、SELinux、capability 和 ROM 行为影响，需要真机验证；
- 长任务不能只依赖 Android 进程永不被杀，应继续使用持久状态和恢复机制。

## 当前验证原则

在 CI 门禁建立前，本地验证结果不等同于持续门禁。提交改动时至少执行与改动相关的最窄测试，并在 PR 中写清实际运行过的命令。

推荐基线：

```bash
cd src/android
./gradlew :app:compileDebugKotlin --no-daemon
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

minisd：

```bash
cd src/native/minisd
cargo fmt --check
cargo test
cargo clippy -- -D warnings
```

## 关键入口

| 目的 | 路径 |
|---|---|
| Android App | `src/android/` |
| Android tools | `src/android/app/src/main/java/com/openminis/app/tools/android/` |
| Tool Runtime | `src/android/app/src/main/java/com/openminis/app/tools/runtime/` |
| MCP | `src/android/app/src/main/java/com/openminis/app/mcp/` |
| Ubuntu runtime | `src/android/app/src/main/java/com/openminis/app/sandbox/ubuntu/` |
| Root Broker | `src/native/minisd/` |
| Rootfs build | `scripts/build-ubuntu-rootfs.sh` |
| Build guide | [`../BUILD-CN.md`](../BUILD-CN.md) |
| Security | [`SECURITY.md`](SECURITY.md) |
| Docs index | [`README.md`](README.md) |

## 文档真实性规则

```text
源码与测试
  > 当前架构 / 安全文档
  > README / CHANGELOG
  > archive / upstream 历史资料
```

发现冲突时，优先修当前文档，不要让旧发行资料或历史架构表述重新进入当前文档。
