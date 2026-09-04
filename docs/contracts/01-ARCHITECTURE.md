# 01 — 运行时架构合同

本文定义当前产品应保持的 Root/minisd/Ubuntu 执行边界。最终 `master` 源码与测试用于判断实现现状；已确认偏差写入 `06-CURRENT-GAPS.md`。

## 总图

```text
Android 原生 App
├─ Agent / Session / Room / Repository
├─ Provider / Model
├─ Tool Registry / 权限 / 审批 / Checkpoint / Job
├─ Android 原生工具
├─ MCP Client + 本地 MCP Server
├─ Voice / Assistant / Overlay
└─ Unix socket RPC
   ↓
minisd（Root Broker，可信计算基）
   ↓
独立 mount namespace + 显式 bind mount + chroot
   ↓
Ubuntu 24.04 userspace（与 Android 共用内核，不是虚拟机）
```

产品运行时是 Root-only。PRoot、Alpine 或其它 userspace 模拟执行后端不属于当前架构，也不是兼容性要求。

## Android App

App 是应用数据库、Provider/Model、工具注册与权限、用户审批、session 选择、运行时编排和恢复策略的权威。

不要为 MCP、Terminal、Voice 或其它入口再造第二套 Agent、数据库、权限或 session 真源。

## minisd

`minisd` 是唯一特权 Linux 边界。它负责：

- 私有、结构化、有界的 Root RPC；
- `/data/adb/minis` 布局与实际 App UID/GID；
- keeper、mount namespace、显式 bind mount、chroot；
- session 路径的包含与隔离；
- rootfs 启动、健康、恢复所需的特权操作。

运行时策略只能收紧编译期能力天花板，不能扩大。Agent 命令不得因为进入 guest 就保留无限制 root 身份。

## Ubuntu guest

Ubuntu 24.04 userspace运行在 Android 内核上。chroot 不是 VM，也不是强隔离容器。

对模型和普通工具公开 guest 路径：`/workspace`、`/memory`、`/skills`、`/shared`、`/home/minis`。Host `/data/adb/minis/...` 是 runtime/storage 实现细节，不应成为模型提示词里的默认路径。

## Session

当调用链带有效 `session_id` 时，workspace 与相关附件/浏览器/offload 数据必须来自该 session backing。Terminal、shell、文件链接、附件与 Agent 执行应对同一 session 得到一致视图。

不得用固定 UID/GID 或全局 `/workspace` 旁路 session 语义。当前 Terminal 偏差见 #186 / `06-CURRENT-GAPS.md`。

## 启动顺序

1. 校验固定持久化参数；
2. 必要时退休陈旧 keeper；
3. 创建/校正 `/data/adb/minis` 布局、owner、mode；
4. 校验路径包含、符号链接与 backing，拒绝无效/tmpfs 用户数据源；
5. 校验或恢复 rootfs；
6. 启动 keeper，建立 namespace/bind/chroot；
7. guest/runtime 探针通过后才报告 READY。

`minisd` 必须能在 rootfs 损坏时独立启动，以便恢复 rootfs。

## 网络

Guest 使用 Android 网络栈。当前有效网络发生变化时，包括 VPN 开启/切换/关闭，guest resolver 应跟随实际 system/VPN DNS 刷新。公共 DNS fallback 属于策略层，不能代替正确继承当前网络 DNS。当前缺口见 #190。

## 非目标

- 不恢复 PRoot/Alpine 双运行时；
- 不把 guest 宣传成 VM/强沙箱；
- 不把 Root、无障碍、Shizuku、普通 Android API 合并成一条权限阶梯；
- 不通过伪装 FGS 类型维持无限后台寿命；
- 不为了 namespace 整洁顺手重命名全库 Kotlin/Java package。
