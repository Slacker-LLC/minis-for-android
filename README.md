# Minis for Android

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://github.com/Slacker-LLC/minis-for-android/releases)
[![Release](https://img.shields.io/badge/release-v1.00--beta-blue)](https://github.com/Slacker-LLC/minis-for-android/releases/tag/v1.01-beta)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)](#安装)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)

Minis 是一个 **Android 原生 Agent 运行时**:手机上运行完整的 LLM Agent(工具调用、文件编辑、
持续 shell、网页浏览、目标管理),内置 **Ubuntu 24.04 chroot**(由 minisd Root Broker 以
root 管理、按 App UID 降权运行)作为默认执行环境,并提供 MCP Server 作为本地/远程工具面。

```text
Android 原生 App(唯一运行时与数据源)
├─ Agent Loop / Room / Repository / 会话状态
├─ Ubuntu 24.04 rootfs + 真 chroot(minisd:unshare+mount+chroot,复用 Android 手机内核)
├─ minisd Root Broker(unix socket + SO_PEERCRED 鉴权 + policy 门控)
├─ MCP Server(127.0.0.1:18789,Bearer 认证,确认队列 + 手机通知)
├─ MCPProvider(外部 MCP Server 客户端,mcp.<server>.<tool>)
├─ 桌面宠物与默认数字助手
└─ 可选 Shizuku / Sui 权限桥
```

MCP 不是第二套 Agent:MCP Server 暴露的每个工具都映射到 Android 现有 ViewModel、
数据库、Repository 或 Tool Runtime,并由 ToolPermissionManager 按工具 × caller 授权。

## 当前版本

| 项目 | 值 |
|---|---|
| Release | [`v1.01-beta.2`](https://github.com/Slacker-LLC/minis-for-android/releases/tag/v1.01-beta.2) |
| Android 版本 | `1.01-beta.2`(versionCode 39) |
| applicationId | `dev.openminispet.android` |
| ABI | `arm64-v8a` |
| APK | `OpenMinis-Pet-1.01-beta.2-arm64-debug.apk` |
| 大小 | `54478782` bytes |
| SHA-256 | `4158bdd821d5a9b6b48c950dc9568842ec7c8f630d9c35467a54bacdef4e9490` |

**当前 APK 使用 Android Debug 签名,仅供开发、自测与源码对应验证,不是生产发布包。**
生产分发必须关闭 DebugServer、改用长期保管的 release keystore,并完成独立安全验收。

- [下载 APK](https://github.com/Slacker-LLC/minis-for-android/releases/download/v1.01-beta.2/OpenMinis-Pet-1.01-beta.2-arm64-debug.apk)
- [查看对应源码](https://github.com/Slacker-LLC/minis-for-android/tree/v1.01-beta.2)
- [发布说明](RELEASE-NOTES.md)

## 主要功能

### Agent 与沙箱

- 多 Provider、模型组、OAuth/API Key、图片输入、会话历史和工具调用;
- 每个会话复用持久 Ubuntu chroot Shell(经 minisd);工作区、附件、产出和共享目录分层挂载;
- 文件编辑支持并发串行化、revision 校验、重叠编辑拒绝及大输出落盘;
- Goal、Todo、Plan、产出文件、反馈、提问卡片和子代理工具使用原生状态源;
- Skills、MCP、记忆/SOUL、环境变量、外部 SAF 挂载和定时任务管理;
- 工具超时、执行意图检查点、后台作业、Token 计量、上下文压力、结果修剪/落盘、
  一次性审批和危险命令策略。

### MCP Server

- MCP Server(`127.0.0.1:18789`)Bearer token 认证,`tools/list` 只列出该 token
  可见的工具;写敏感工具(日历、联系人、位置、剪贴板、Intent、文件写等)要求手机通知
  确认,120s 自动拒绝;
- MCPProvider 可连接外部 HTTP/stdio MCP Server,工具统一注册为 `mcp.<server>.<tool>`
  且 LOCAL_ONLY,支持 hot-reload;
- Skills/MCP 配置可从公开 HTTPS URL 导入;导入器限制 HTTPS/443、重定向、大小,
  并拒绝 localhost、私网、链路本地和 CGNAT;
- 远程面只经 MCP Server;旧 Web Remote(HTTP 服务、Cloudflare Tunnel、capability
  catalog 与静态前端)已删除。

### Android Debug 工具链

- `android_capabilities` / `android_app` / `android_ui` / `android_logs` /
  `android_diagnose` / `android_deploy` 六个高内聚 Agent 工具;
- 只读能力矩阵:root、Shizuku、Accessibility、截图、调试器、执行环境逐项真实探测,
  `uid=0` 不会被当作全能力;
- Accessibility UI 观察带 generation/ref 与 `STALE_UI_REF`;API 30+ 系统截图;
- logcat 游标(mark → 操作 → read since),watch 复用作业系统,大输出自动落盘;
- APK 按真实 Gradle output 元数据发现/部署,不猜固定路径;
- 支持 Android 原生 SDK API、Shizuku 与主动授权后的 Root `su`。

### Android 集成

- 通用 ZIP 桌面宠物包、悬浮窗、状态动画、模型直聊与语音配置复用;
- Android `ROLE_ASSISTANT`、VoiceInteraction Session/Recognition 服务及系统助手入口;
- Shizuku、AXManager 或 Sui 是**可选**的 Android shell/Binder 能力桥,普通聊天和 Ubuntu
  chroot 沙箱不依赖它们。

## 安全边界

远程面只有 MCP Server 与本地 DebugServer,默认坚持最小权限:

- MCP Server 只监听回环 `127.0.0.1:18789`,Bearer token 按需生成,未知 token 默认拒绝;
- `ToolPermissionManager` 使用「工具 × caller」显式映射表,未登记工具默认拒绝;
  敏感能力对 MCP 默认 MCP_CONFIRM(手机通知批准),`root.shell` 为 LOCAL_ONLY;
- Provider Key、环境变量值以及 MCP Header/环境值不通过读取接口返回;
- 文件工具统一经 `UbuntuPaths` canonical 边界,写操作限定 workspace 并尊重只读挂载;
- 安装/卸载/清空日志/Root 授权等有副作用操作经过一次性审批(ApprovalSeam);
- 公网暴露(如 Cloudflare Tunnel)只能由用户自行配置在 MCP Server 之前,本项目不再内置
  Web 服务端。

## 执行环境:Ubuntu chroot、Root 与 Shizuku

当前默认执行环境已从 Alpine+PRoot 重构为 **Ubuntu 24.04 + 真 chroot**(P2):

```text
Android 手机内核 → OpenMinis App UID → minisd(root broker) → unshare+mount+chroot → Ubuntu 24.04
```

| 方案 | 当前状态 | 是否需要 Root | 是否有独立内核 |
|---|---|---:|---:|
| **Ubuntu 24.04 + minisd chroot** | **已实现(默认)** | 是(KernelSU/Magisk) | 否 |
| Alpine + PRoot | 已移除(P2 拆除) | 否 | 否 |
| Root `su` 直接执行 | 已实现(主动探测后端) | 是 | 否 |
| QEMU/KVM + Ubuntu kernel/rootfs | 未实现,需单独评估 | KVM 通常需要 | 是 |

- Root 设备通过 `su` 工作,不依赖 Shizuku,兼容 Magisk / KernelSU / APatch;
  Root provider 名称只作为诊断信息,能力判断以真实探测为准;
- 被动能力查询(如 `android_capabilities get`)不会触发 Root 授权弹窗;只有显式
  `active_root_probe` 才请求授权并返回 uid/gid/groups/CapEff/SELinux;
- guest 进程以 App UID 降权(uid=policy.caller.appUid)运行,SELinux 全程 Enforcing;
  不修改全局 SELinux 策略,chroot 不是容器;guest 出网经 minisd 根代理
  `127.0.0.1:18787`(拒内网/回环目标)。

完整边界见 [执行环境](docs/EXECUTION-ENVIRONMENT.md)。

## 安装

要求 Android 8.0(API 26)或更高版本的 arm64 设备。

```bash
adb install -r OpenMinis-Pet-1.01-beta-arm64-debug.apk
```

首次使用建议:

1. 在 Provider 设置中添加 API Key 或完成对应 OAuth;
2. 如需桌面宠物,在 Android 系统界面授予悬浮窗权限;
3. 如需 Root 能力,安装并授权 KernelSU/Magisk,minisd 会以结构化 allowlist 方式使用;
4. HyperOS 用户在系统设置中允许后台运行并开启自启动,否则退到后台后可能冻结;
5. 系统角色、电池豁免、自启动和 SAF 目录都必须由用户在手机系统界面授权。

## 从源码构建

构建环境:Linux/WSL、JDK 17、Android SDK 36、NDK r28+、CMake 3.22.1。
仓库只构建 `arm64-v8a`。完整步骤见 [BUILD-CN.md](BUILD-CN.md) 或 [BUILDING.md](BUILDING.md)。

```bash
git clone --recurse-submodules https://github.com/Slacker-LLC/minis-for-android.git
cd OpenMinis-Pet

cp src/android/app/provider-customization.properties.example \
   src/android/app/provider-customization.properties

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"

# minisd Root Broker（Rust → aarch64-linux-musl）
# 源码：src/native/minisd（与 App 同仓，无需 submodule）
cargo build --release --target aarch64-unknown-linux-musl --manifest-path src/native/minisd/Cargo.toml
# Ubuntu 24.04 arm64 rootfs 打包（产出 assets 内 rootfs tar + SHA-256）
./scripts/build-ubuntu-rootfs.sh

cd src/android
./gradlew :app:assembleDebug --no-daemon
```

APK 输出:`src/android/app/build/outputs/apk/debug/app-debug.apk`。

> 构建产物约定：`minisd` 二进制与 Ubuntu rootfs tar 不提交 Git，干净 checkout 后必须
> 用上述命令重新生成（详见 `BUILD-CN.md`）。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `src/android/` | Android App、Compose UI、Room、Provider、MCP 与 Tool Runtime |
| `src/android/app/src/main/java/com/openminis/app/tools/android/` | Android Debug 工具链 |
| `src/android/app/src/main/java/com/openminis/app/mcp/` | MCP Server 与 MCPProvider |
| `src/shared/` | Android 构建复用的共享规则/资源 |
| `deps/` | 已移除的 PRoot/Alpine 依赖构建脚本(历史归档);当前原生依赖为 `src/native/minisd`(Rust,同仓) |
| `releases/` | 明确发布且与源码对应的 APK |
| [`docs/README.md`](docs/README.md) | 文档索引 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本变更记录(自 1.01-beta 起) |
| [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) | 第三方许可证与来源 |

## 起源与许可证

本项目作为独立项目发布,首版为 `v1.01-beta`。其代码谱系包含:

- [OpenMinis](https://github.com/OpenMinis/OpenMinis)(GPL-3.0,派生基础);
- PRoot、Alpine、Termux 与各 Android 依赖(见
  [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) 与
  [README-upstream.md](README-upstream.md))。

本项目整体按 [GPL-3.0](LICENSE) 分发。分发修改后的 APK 时必须同时提供对应源码。

> 本项目与 DeepSeek 没有产品关联或官方合作关系。问题请提交到
> [本仓库 Issues](https://github.com/Slacker-LLC/minis-for-android/issues)。
