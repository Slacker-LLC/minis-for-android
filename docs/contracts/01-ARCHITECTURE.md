# 01 — 目标架构

本文定义**目标**执行模型。实现是否对齐见 `06-CURRENT-GAPS.md`。

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

## 职责

### Android App

唯一权威：应用数据库、Provider/Model、工具注册与权限、用户审批、运行时编排、进程死后的恢复策略。

不要再造第二套 Agent / 数据库 / 权限 / 审批系统。MCP 与系统集成必须投影进现有 Android runtime。

### minisd

唯一权威：特权 Linux 边界。在 keeper 的 mount namespace 建立**之前**准备并校验固定持久化数据源。暴露有界、结构化的 RPC。编译期能力天花板不可被运行时策略扩大。

Agent 命令以 App guest UID 运行，不保留无限制 root 身份。`uid=0` 只是诊断事实，不证明 SELinux / mount / capability 一定可用。

### Ubuntu guest

Ubuntu 24.04 userspace。通过 chroot 进入 minisd 准备好的 namespace。不是完整容器安全边界。

公开工具合同使用 guest 路径（`/workspace`、`/memory`、`/skills`、`/shared`、`/home/minis`），不要把 host 路径写进对模型可见的工具说明。

## 启动顺序（目标）

1. 校验固定持久化参数（拒绝替代路径）
2. 必要时清理陈旧 keeper
3. 创建/修复 `/data/adb/minis` 布局与 owner/mode
4. 拒绝 tmpfs 真源
5. 校验 rootfs
6. 拉起 keeper（unshare + bind + chroot）
7. guest 内探针通过后才报 READY

`minisd` 必须能在 rootfs 损坏时独立启动，以便修复 rootfs。不要把 broker 启动绑死在 guest 健康上。

## 非目标

- 不把 guest 当成 VM 或强隔离沙箱
- 不把 Root、无障碍、Shizuku、普通 Android API 混成同一条权限阶梯
- 不靠一个伪装成媒体播放的 FGS 让 Agent 无限活着；长任务靠 checkpoint / 持久 Job
