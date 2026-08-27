# 安全设计

本文描述 Minis for Android 当前的安全模型、目标边界和已知缺口。

> 仓库当前不提供预编译发行 APK。源码仍处于安全收口阶段，不能把本地 Debug/Release 构建视为 production-ready；高优先级问题以仓库 Issues 为准。

## 1. 凭据存储

- Provider API Key、OAuth token、DebugServer token 等敏感值应使用 Android 加密存储；
- 加密存储初始化失败时应 fail-closed，不回退到明文；
- `allowBackup=false`；
- Provider/MCP/环境变量读取接口不应返回 secret 原文。

## 2. 本地服务边界

### DebugServer

- 仅用于开发/自测；
- 监听回环地址；
- 未来生产构建必须确保 DebugServer 与 debug-only 资源不可进入发布 APK。

### MCP Server

- 默认监听 `127.0.0.1:18789`；
- Bearer token 认证；
- 工具权限由 caller/token scope 和 ToolPermissionManager 控制；
- 敏感工具可要求手机端确认；
- 未经用户额外代理/隧道配置时，不应直接暴露到 LAN/公网。

### minisd Root Broker

`minisd` 是整个 Root 路径的 TCB（可信计算基）。它的安全要求高于普通 App 内部模块。

目标模型：

```text
caller
  ↓ identity / peer check
minisd RPC
  ↓ compile-time capability ceiling
runtime policy (只能收紧)
  ↓ structured privileged operation
Android / mount / chroot
```

当前实现仍在安全收口中，已知关键问题包括：

- [#2](https://github.com/Slacker-LLC/minis-for-android/issues/2)：运行时 policy 目前存在自我扩权风险；
- [#3](https://github.com/Slacker-LLC/minis-for-android/issues/3)：历史 `restartCloudflared` supervisor RPC 可形成过宽的 Root 进程启动面；
- [#4](https://github.com/Slacker-LLC/minis-for-android/issues/4)：Unix stream framing、并发和全局锁需要重构；
- [#6](https://github.com/Slacker-LLC/minis-for-android/issues/6)：`root.exec` 输出需要硬上限。

因此当前文档不把 `SO_PEERCRED + policy` 描述成已经完全闭环的生产安全边界。

## 3. 工具调用授权

- 所有 Agent/MCP 工具应进入统一 Tool Registry / ToolPermissionManager / runtime gate；
- 未登记工具默认拒绝；
- caller 至少区分本地 Agent 与不同 MCP token/scope；
- 敏感 Android 能力应使用 `MCP_CONFIRM` 或 `LOCAL_ONLY`；
- Root 能力不得提供裸 raw shell 给远程 caller；
- 权限检查不能只存在 UI 层，真正执行入口也必须再次验证。

## 4. Root 权限原则

- Root 工具优先使用结构化 RPC，而不是 `su -c <任意字符串>`；
- 编译时必须存在不可被 runtime policy 扩大的最大权限集合；
- runtime policy 只能缩小权限，不得新增超出编译时 ceiling 的 binary/method；
- policy 管理能力应与普通 App 调用面隔离；
- 不允许为了可用性执行 `setenforce 0` 或关闭全局 SELinux；
- Root provider 名称（KernelSU/Magisk/APatch 等）只作诊断，不代替真实 capability 探测。

## 5. Ubuntu chroot 边界

- chroot 不是虚拟机或完整容器；
- guest 复用 Android kernel；
- mount / namespace / UID / SELinux 边界必须由 minisd 明确建立；
- workspace、memory、skills、shared 等挂载必须使用固定 host/guest layout；
- guest 不应获得不必要的 host 路径写权限；
- rootfs 来源和 hash 必须可验证，见 [#5](https://github.com/Slacker-LLC/minis-for-android/issues/5)。

## 6. 文件与路径边界

- 文件工具在执行前做 canonical path containment；
- workspace 写入和外部 SAF 授权必须分开处理；
- `..`、symlink escape 和越界挂载必须 fail-closed；
- 大文件和大工具输出应限制大小或 spill 到受控目录；
- 外部 URL 导入需要限制 scheme、重定向、大小和 SSRF 目标。

## 7. Android 权限

Root、Shizuku/AXManager/Sui、Accessibility、普通 Android API 是不同能力，不应混成简单的“权限等级链”。

原则：

- 普通 Android API 能完成时优先普通 API；
- privileged backend 只在对应操作确实需要时使用；
- capability 必须真实探测；
- `uid=0` 不等于拥有所有 SELinux/capability 能力；
- 系统角色、Accessibility、悬浮窗、SAF、电池豁免等仍由用户在 Android 系统界面授权。

## 8. Agent 侧安全治理

高副作用操作应组合使用：

- Approval / user confirmation；
- ToolCheckpoint；
- Tool timeout；
- DangerousCommandPolicy；
- Job / persisted state；
- SpillPolicy / ToolResultPruner；
- secret redaction。

进程死亡恢复时，不能因为“没收到上一次工具结果”就盲目重试有副作用的操作。

## 9. 网络安全

- 云 Provider、OAuth、更新/下载信任元数据默认必须使用 HTTPS；
- 用户配置的 LAN HTTP Provider 是特殊情况，不应把整个 App 的明文 HTTP 当成默认安全策略；
- 当前全局 cleartext 配置的收口跟踪见 [#10](https://github.com/Slacker-LLC/minis-for-android/issues/10)；
- 受保护流程不得被重定向降级为 HTTP。

## 10. 未来发布安全

仓库当前是 source-only，没有正式发行版。若以后恢复 production release，至少先关闭这些发布链问题：

- [#7](https://github.com/Slacker-LLC/minis-for-android/issues/7)：CI 门禁；
- [#8](https://github.com/Slacker-LLC/minis-for-android/issues/8)：Release 禁止使用 debug keystore；
- [#9](https://github.com/Slacker-LLC/minis-for-android/issues/9)：恢复 release lint；
- [#12](https://github.com/Slacker-LLC/minis-for-android/issues/12)：私有 Provider 定制值缺失时显式禁用/失败。

二进制产物应通过受控的 Release/CI artifact 分发，不能再提交 APK/AAB 到 Git。

## 11. 已知平台边界

- HyperOS 等 OEM 可能冻结后台 Agent 网络/CPU；
- Foreground Service 行为和 Android 版本策略会变化，见 [#11](https://github.com/Slacker-LLC/minis-for-android/issues/11)；
- Root/SELinux/capability 行为必须真机验证；
- Android 系统授权不能由 App 静默绕过。

## 12. 安全改动的测试要求

安全改动不能只测试 happy path。

至少需要覆盖：

```text
允许的调用 → 成功
越权调用 → 拒绝
非法参数 → 拒绝
边界尺寸 → 限制
进程/IPC 异常 → fail-closed
重启/恢复 → 不重复副作用
```

尤其是 `src/native/minisd/` 的改动，应把负向测试视为合并条件。
