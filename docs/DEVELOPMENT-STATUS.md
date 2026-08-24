# 开发状态

本文记录当前 `master` 的工程状态。发布使用者请先阅读根目录 [README](../README.md)。

## 仓库与发布

- Repository: `https://github.com/Slacker-LLC/minis-for-android`
- Branch: `master`
- Release tag: `v1.01-beta.2`
- Package: `dev.openminispet.android`
- Version: `1.01-beta.2`(versionCode 39)
- APK: `releases/OpenMinis-Pet-1.01-beta.2-arm64-debug.apk`
- Size: `54478782` bytes
- SHA-256: `4158bdd821d5a9b6b48c950dc9568842ec7c8f630d9c35467a54bacdef4e9490`

当前 APK 是 arm64 Debug 签名开发包,不是生产 release。

## 已交付

### MCP Server 与 Tool Runtime

- MCP Server(`127.0.0.1:18789`):Bearer token 认证、`tools/list` 过滤(LOCAL_ONLY/
  MCP_DENIED 不出现)、MCP_CONFIRM 走手机通知「批准/拒绝」确认队列,120s 自动拒绝;
- `MCPKeepAliveService` 保持锁屏可用;MCPProvider(客户端)可连接外部 HTTP/stdio
  MCP Server,工具命名为 `mcp.<server>.<tool>`;
- Tool Registry / Provider Router / Permission Manager 三分层,canonical 工具
  (`linux.*`/`android.*`)与 MiClaw 兼容 alias 共存,alias 先 canonicalize 再
  执行权限/scope/确认/runtime gate;
- `root.shell` 结构化 minisd `root.exec`,LOCAL_ONLY,无 raw command 字段。

### 安全

- Provider、环境和 MCP secret 不通过读取响应返回;
- ToolPermissionManager「工具 × caller」显式映射,未登记工具默认拒绝;
- UbuntuPaths canonical path containment 和 workspace 写权限策略;
- 有副作用 Android 操作(安装/卸载/清日志/Root 授权等)走一次性审批;
- EncryptedPreferences 失败时 fail-closed,不回退明文;
- 2026-08:Web Remote(旧 HTTP server、Tunnel 配置、capability catalog、静态前端)已整体删除;
  远程面收敛为 MCP Server + DebugServer(本地)。

### Android Debug 工具链

- `android_capabilities`/`android_app`/`android_ui`/`android_logs`/`android_diagnose`/
  `android_deploy` 六个高内聚工具,复用现有 Accessibility、VisionGroupResolver、Root su、
  ApprovalSeam、JobRegistry、ToolCheckpointStore、SpillPolicy(P2 后 PRoot/Shizuku 已停用);
- 只读 capability resolver(root/privileged shell/UI/debug/execution/package visibility
  逐项探测,`uid=0` 不等同于全能力);
- `PrivilegedCommandRunner`:Root `su` backend(被动检测 + 主动授权探测,CAP_SYS_CHROOT/ADMIN
  与 SELinux 状态返回)或 Shizuku,按操作选择;
- Accessible UI observation 支持 generation/ref、指纹校验、STALE_UI_REF;Unicode 输入优先
  ACTION_SET_TEXT,失败才走会保存/恢复剪贴板的 PASTE;
- logcat 支持 mark_cursor → 操作 → read since cursor,boot change 检测,watch 走 JobRegistry,
  大输出走 SpillPolicy;
- APK 部署按真实 Gradle output 发现 + archive 元数据,不猜固定路径;明确拒绝自覆盖安装
  (self-update continuous execution UNSUPPORTED);
- native chroot 已由 P2 升级为默认执行环境(见下文「Root-only 重构」)。

### Android

- 桌面宠物、默认数字助手、原生 Agent 状态条、提问/反馈/计划等同步;
- **Ubuntu 24.04 chroot 沙箱(经 minisd)**,共享目录和 SAF 外部挂载(P2 起替代 PRoot Alpine);
- Shizuku/AXManager/Sui 为可选 Android privileged bridge;普通 Agent 不依赖它(P2 后 Shizuku 停用);
- 工具超时、执行意图检查点、后台作业、Token 计量、上下文压力、结果修剪/落盘。

## 验证记录

- JDK 17 / Android SDK 36:`:app:compileDebugKotlin`、`:app:assembleDebug` 通过;
- OpenMinis Web Client Plugin:`tsc --noEmit` 与 vitest 6/6 通过;
- Android 工具单元测试 47/47 通过(capability/SELinux/CapEff 解析、logcat/崩溃解析、
  APK 发现、UI generation/ref、风险分类);全部 904 测试仅 14 个 OpenAI MockWebServer
  环境基线失败;
- Android 16 arm64 真机(Xiaomi 15, API 36):`adb install -r` 成功,MainActivity 启动、
  前台窗口与进程确认。

## 尚未交付

- **Root-only 重构已交付(2026-08)**——以下为剩余项:
- 远程 MCP 公网验收(需用户 Cloudflare 账号);
- rootfs 升级策略(保护用户 pip/npm/venv 环境);
- production release keystore、关闭 DebugServer 的正式 release APK;
- QEMU/KVM 独立 Ubuntu kernel backend(已否,不计划);
- 完整 DSH Subagent 目录、通用持久 Job runtime 和队列编辑;
- Provider 全量离线 fixture。

> 2026-08:Web Remote 已删除(26 项能力对照、Tunnel、前端 bundle 与 capability 层),不再留存。

## 设备相关限制

- HyperOS 未授予后台无限制时,即使前台服务进程存在,也可能在退到后台约 20 秒后冻结
  网络处理;
- 电池豁免、自启动、系统角色、悬浮窗和 SAF 授权必须由用户在 Android 系统界面完成;
- Shizuku 不是 Root;Root 设备直接走 `su` 后端(主动探测),与 Shizuku 相互独立。

## 关键入口

| 目的 | 路径 |
|---|---|
| Android Debug 工具 | `src/android/app/src/main/java/com/openminis/app/tools/android/` |
| Root runtime | `src/native/minisd/`(Rust,根 broker) |
| Ubuntu runtime (App 侧) | `src/android/app/src/main/java/com/openminis/app/sandbox/ubuntu/` |
| MCP Server | `src/android/app/src/main/java/com/openminis/app/mcp/server/` |
| Tool Runtime | `src/android/app/src/main/java/com/openminis/app/tools/runtime/` |
| Rootfs manager(壳) | `src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt` |
| Build | [`../BUILD-CN.md`](../BUILD-CN.md) |
| Docs index | [`README.md`](README.md) |
