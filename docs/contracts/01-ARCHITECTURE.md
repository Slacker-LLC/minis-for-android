# 01 — 运行时架构合同

本文定义当前产品应保持的 Direct Ubuntu 执行边界。最终目标分支源码与测试用于判断实现现状；已确认偏差写入 `06-CURRENT-GAPS.md`。

## 总图

```text
Android 原生 App
├─ Agent / Session / Room / Repository
├─ Provider / Model
├─ Tool Registry / 权限 / 审批 / Checkpoint / Job
├─ Android 原生工具
├─ MCP Client + 本地 MCP Server
├─ Voice / Assistant / Overlay
└─ ExecutionCoordinator / App-owned persistent shell
   ↓
UbuntuKernel / DirectRootRunner
   ↓
su → setsid → unshare -m
   ↓ 显式 bind mount
chroot /data/adb/minis/rootfs
   ↓
setpriv(App UID/GID, clear groups, drop all capabilities)
   ↓
Ubuntu 24.04 bash / userspace
```

PRoot、Alpine、旧特权 broker 或其它 userspace 模拟/双栈后端不属于当前生产架构。

## Android App

App 是应用数据库、Provider/Model、工具注册与权限、用户审批、session 选择、运行时编排、shell 生命周期和恢复策略的权威。不要为 MCP、Terminal、Voice、Bot 或其它入口再造第二套 Agent、数据库、权限或 session 真源。

## Root 边界

Root 仅用于确实需要特权的 App-owned Direct Ubuntu 基础设施：

- 探测 Root 与 direct chroot 前置能力；
- 校验、安装、修复 `/data/adb/minis/rootfs`；
- 为 shell 建立独立 mount namespace 和显式 bind mounts；
- 进入 chroot；
- 执行受控的一次性 legacy 数据迁移或同类必要维护。

进入 guest 后必须通过 `setpriv` 降到真实 App UID/GID并清空 supplementary groups/capabilities。

`DirectRootRunner` 是 internal launcher，不是通用 Root RPC，也不接受原始模型命令字符串。需要 Android Root 能力的本地 Agent 可使用结构化 `root.shell`：只提交 executable basename + argv，经过可信 Android system 目录解析、参数/输出/超时边界和进程清理后进入该 launcher。它是 local-only，不对 MCP 暴露，也不提供 host 文件系统或 socket RPC 面；`su -c` 仍只承载 App 构造的受控脚本。

## Ubuntu guest

Ubuntu 24.04 userspace 运行在 Android 内核上。chroot 不是 VM，也不是强隔离容器。

对模型和普通工具公开 guest 路径：`/workspace`、`/memory`、`/skills`、`/shared`、`/home/minis` 和 `/var/minis/...`。这些路径由 direct runtime 绑定到 App-owned backing；Host 绝对路径不是模型提示词里的默认接口。

## Session

当调用链带有效 `session_id` 时，workspace、attachments、offloads、browser 必须来自该 session backing。Terminal、shell、文件链接、附件与 Agent 执行应对同一 session 得到一致视图。

不得用固定 UID/GID 或全局 `/workspace` 旁路 session 语义。

## 启动顺序

1. 初始化 App-owned 路径与 session/global backing；
2. 验证需要的 Root 能力；
3. 校验或修复 rootfs；
4. 验证 `unshare` / `mount` / `chroot` / `setsid` / guest `setpriv`；
5. 完成 Ubuntu provision 与 guest command bridge 准备；
6. 如需要，在 guest 启动前完成旧 Root-owned 用户数据迁移；
7. 建立 per-shell namespace/binds/chroot；
8. 在 guest 内降权后启动 bash；
9. 只有必需前置条件通过才报告 READY。

网络兼容 helper 的启动/可用性属于网络子系统，不应被描述成 Root/chroot 本身的固有步骤；如果当前产品配置要求 guest 通过该 helper 出站，则对应网络准备失败应明确报告，而不是静默改变权限模型。

## 网络

网络代理和 Root/chroot 是两个不同问题。

当前实现提供 `127.0.0.1:18787` 的 `minis-root-network-proxy`，仅做 HTTP absolute-form 和 CONNECT 转发。代理协议本身不依赖 Root；当前 Android 部署可用特权身份建立代理的出站 socket，用于兼容部分 VPN/BPF/UID 策略对 App-UID guest 的限制。

代理固定监听 loopback，不提供命令、文件、配置插件或通用 RPC。普通 private/loopback 目标拒绝；`198.18.0.0/15` Fake-IP 仅保留给明确 VPN/TUN 兼容。DNS 应跟随 Android 当前有效网络，真实 VPN/DNS/BPF 行为仍需真机验收。

## 非目标

- 不恢复旧 broker 或 PRoot/Alpine 双运行时；
- 不把 guest 宣传成 VM/强沙箱；
- 不把网络代理写成 Root/chroot 的天然组成部分；
- 不把 Root、Accessibility、Shizuku、普通 Android API 合并成一条权限阶梯；
- 不通过伪装 FGS 类型维持无限后台寿命；
- 不为了 namespace 整洁顺手重命名全库 Kotlin/Java package。
