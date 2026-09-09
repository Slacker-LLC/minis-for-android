# 01 — 运行时架构合同

本文定义当前产品应保持的 Direct Root / Ubuntu 执行边界。最终目标分支源码与测试用于判断实现现状；已确认偏差写入 `06-CURRENT-GAPS.md`。

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

产品运行时是 Root-only。PRoot、Alpine、旧 broker 或其它 userspace 模拟/双栈后端不属于当前架构。

## Android App

App 是应用数据库、Provider/Model、工具注册与权限、用户审批、session 选择、运行时编排、shell 生命周期和恢复策略的权威。不要为 MCP、Terminal、Voice 或其它入口再造第二套 Agent、数据库、权限或 session 真源。

## Root 边界

Root 只用于 App 自有基础设施：

- 探测 Root 与 direct chroot 前置条件；
- 校验、安装、修复 `/data/adb/minis/rootfs`；
- 为每个 shell 建立独立 mount namespace 和显式 bind mounts；
- 进入 chroot 后用 `setpriv` 降到真实 App UID/GID并清空 capabilities；
- 执行一次性 legacy 数据迁移；
- 启动固定 loopback 的 Root 网络代理。

`DirectRootRunner` 是内部 launcher，不是 Agent 工具，也不是通用 Root RPC。Agent、MCP、Provider、模型输出不得直接成为 Root 脚本或 `su -c` 参数。

## Ubuntu guest

Ubuntu 24.04 userspace运行在 Android 内核上。chroot 不是 VM，也不是强隔离容器。

对模型和普通工具公开 guest 路径：`/workspace`、`/memory`、`/skills`、`/shared`、`/home/minis` 和 `/var/minis/...`。这些 guest 路径由 direct runtime 绑定到 App-owned backing；Host 绝对路径不是模型提示词里的默认接口。

## Session

当调用链带有效 `session_id` 时，workspace、attachments、offloads、browser 必须来自该 session backing。Terminal、shell、文件链接、附件与 Agent 执行应对同一 session 得到一致视图。

不得用固定 UID/GID或全局 `/workspace` 旁路 session 语义。

## 启动顺序

1. 初始化 App-owned 路径与 session/global backing；
2. 验证 Root 授权；
3. 校验或修复 rootfs；
4. 验证 `unshare` / `mount` / `chroot` / `setsid` / guest `setpriv`；
5. 确保固定 Root 网络代理可用；
6. 完成 Ubuntu provision；
7. 如需要，迁移旧 Root-owned 用户数据到 App 私有目录；
8. 安装/刷新 guest command bridge；
9. 建立 per-shell namespace/binds/chroot，并在 guest 内降权后启动 bash；
10. 只有全部前置条件通过才报告 READY。

## 网络

Guest 的 HTTP/HTTPS 出站通过 `127.0.0.1:18787` 的独立 `minis-root-network-proxy`。该 helper 以 Root 身份负责向外建立连接，用于绕过部分 Android/VPN/BPF 场景对非 Root guest UID 的出站限制；它只提供 HTTP absolute-form 和 CONNECT 转发，不提供命令、文件或通用 RPC。

代理监听地址固定为 loopback，拒绝普通 private/loopback 目标；`198.18.0.0/15` Fake-IP 范围保留用于 VPN/TUN 兼容。DNS 优先读取 Android 当前网络信息并有受控 fallback。真实 VPN/DNS 行为仍需设备验收。

## 非目标

- 不恢复旧 broker 或 PRoot/Alpine 双运行时；
- 不把 guest 宣传成 VM/强沙箱；
- 不把 Root、无障碍、Shizuku、普通 Android API 合并成一条权限阶梯；
- 不通过伪装 FGS 类型维持无限后台寿命；
- 不为了 namespace 整洁顺手重命名全库 Kotlin/Java package。
