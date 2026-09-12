# 06 — 当前已确认缺口

> 基线更新：2026-09-10，`refactor/direct-ubuntu-runtime`（PR #235 已合并到 `main`）。本文件只维护重新核验后的当前状态。更早的 broker/PRoot/旧 storage 审计结论保留在 Git 历史、`docs/issue-*.md` 与 `docs/archive/`，不再混入当前缺口正文。

## 已完成的 Direct Ubuntu 迁移边界

以下不再属于“待实现”项：

- 生产 Linux runtime 已切到 Android App-owned Direct Ubuntu 24.04 chroot；
- 旧特权 broker 的 Android/native/build/socket/package 路径已退出生产树；
- runtime payload 为 rootfs-only；
- active guest 用户数据改为从 `Context.filesDir` 派生的 App-owned backing；
- `/data/adb/minis/rootfs` 保持 Root-owned、可替换 runtime state；
- legacy Root-owned 用户数据只作为一次性迁移源；
- guest 使用真实 App UID/GID，并通过 `setpriv` 清空 supplementary groups/capabilities；
- `DirectRootRunner` 仍是内部基础设施；结构化 `root.shell` 已恢复为 local-only Agent 能力，MCP 不可见且不接受 raw command；
- build/package/runtime regression guards 已覆盖旧 broker 身份回归；
- loopback HTTP/CONNECT helper 已与 rootfs payload 分离构建和验证。

## 网络架构说明

网络代理与 Root/chroot 不是同一层。

`minis-root-network-proxy` 是当前实现名称；HTTP/CONNECT 代理协议本身不依赖 Root。当前 Android 部署可让 helper 以特权身份建立出站 socket，仅用于兼容某些 VPN/BPF/UID 策略下 App-UID guest 无法直接联网的情况。

因此当前缺口不是“Root 必须有代理”，而是：**真实设备上何时需要该兼容路径、VPN/DNS/BPF/Fake-IP 切换是否可靠，仍需要设备证据。**

旧 APK 的精简 rootfs 基础包只保证 `curl`/`wget`，因此 Guest 中曾出现
`ping: command not found`。当前分支已将 `iputils-ping` 纳入 provision 包和 readiness probe；
最新 Debug APK 已部署到小米 `24129PN74C` 真机；Guest 内 `command -v ping` 返回
`/usr/bin/ping`，并以 `ping -c 1 -W 5 doubao.com` 实测成功（1 发 1 收、0% 丢包，RTT
约 92 ms）。这只证明当前直连网络路径上的 ICMP 可用，不替代 VPN/DNS/BPF/Fake-IP 矩阵；
HTTP/DNS 检查仍可用受控的 `curl`。

## Guest CLI 命令面

此前确认的“`/usr/local/bin` 只有 `minis-config` 和 `minis-model-use`”缺口已经在当前工作树闭合。Direct Root 不复制上游 PRoot 的 `native_offload` stub，而是在 Ubuntu 启动/恢复时由 `GuestCommandBridge` 为当前已注册的 Android/Minis handler 生成带 loopback token 鉴权的 wrapper；因此命令名、argv/session/cwd/stdin 语义和 Android handler 保持一致，同时不恢复 PRoot、旧 broker 或通用 Root shell。

小米 `24129PN74C` 真机已验证这些命令的 PATH 入口：`android-alarm`、`android-calendar`、`android-clipboard`、`android-contacts`、`android-device`、`android-location`、`android-notification`、`android-open`、`android-photos`、`android-player`、`android-speak`、`android-speech`、`android-weather`、`android-a11y-cli`、`android-shizuku-cli`、`minis-browser-use`、`minis-scheduled`、`minis-sessions-cli`、`minis-config`、`minis-model-use`、`minis-open` 及其浏览器别名；Debug APK 另有 `minis-debug`。`android-device info` 和多个 `--help`/`--version` 调用已获得实际输出。

上游另有 Python 版 `minis-mcp-cli` 资产；它依赖旧 PRoot/Alpine 脚本的自安装 transport。当前分支的 MCP client/server 由 Android 原生实现承载，因此这套 Python 命令未直接复制到 Direct Ubuntu rootfs；若产品要求在 Guest shell 中保留同名命令，仍需单独按当前 MCP 权限、配置和生命周期合同实现。

## 本轮基线复核（2026-09-12）

本轮以 `refactor/direct-ubuntu-runtime` 的已知基线提交
`b89f117989e77188194c332da4c5a539b1a1f519` 为对象，在独立审计工作树中
完成了宿主构建、运行时调用链、上游对账和安全边界复核。已取得的证据包括：

- Debug/Release JVM 测试通过，`assembleDebug` 通过，runtime package、文档来源和
  package-boundary guards 通过；
- 早期 `ZTE MU3356`（Android 15，ADB over network）证据仍只证明无 Root 时的失败关闭，
  不作为当前 Root 通过证据；
- 小米 `24129PN74C`（Android 17/HyperOS，16 KiB pages）已安装当前 Debug APK，并通过
  App 内真实 Terminal 进入 Direct Ubuntu；`id` 返回设备实际 App UID/GID，文件创建、复制、
 读取、删除成功，关闭 Terminal 后 `bash -l` 被回收；连续重开仍得到干净提示符；
- 同一小米机上强停 App 后重新打开、再次进入 Terminal 的冷启动通过；MiMo v2.5 Provider
  的界面添加、文本流式请求和独立 TTS 请求通过；
- Guest shell 自杀场景（`kill -9 0`）后 Terminal 返回主界面，未留下 `bash -l`，随后再次
  进入 Terminal 成功；这不替代并发多 session、Root helper 崩溃和手机重启证据；
- 该设备上的 instrumentation APK 仍被 HyperOS 的安装限制拒绝，因此不能把宿主编译
  结果等同于 instrumentation 已在真机执行；`adb shell su` 不可用也不能反推 App 内
  KernelSU-mediated Direct Root 路径失败，二者是不同入口；

## 当前明确待设备验收

CI/宿主测试不能替代以下证据：

1. KernelSU/Magisk/APatch 等目标 Root 方案的真实授权与生命周期；
2. SELinux 下 `su → unshare → mount/bind → chroot → setpriv` 的完整执行；
3. 非固定 App UID/GID 下的 owner、读写与 session workspace 一致性；
4. 无 VPN → VPN、VPN A → VPN B、VPN → 无 VPN 的 DNS/路由刷新；
5. `198.18.0.0/15` Fake-IP/TUN、Android BPF/UID policy 下 guest `curl` / `apt`；
6. Root 授权拒绝/允许分支、手机重启后重开、升级 APK 保留数据和多个 Terminal session；
7. 终端反复打开/关闭、App 进程死亡与 OEM 后台策略下的完整矩阵，以及 VPN/TUN/BPF
   切换下的真实 guest 网络行为。

没有这些设备证据时，只能声称代码/CI 层通过，不能声称全部设备运行验收完成。

## 当前开放问题

GitHub Issue 是独立工作队列，不等于每条描述都仍与当前 Direct Ubuntu 源码一致。2026-09-10 仍 open 的主要项包括：

### 安全 / 数据完整性

- #230 OAuth 回调与 token 响应日志泄露风险；
- #231 备份恢复读取端缺少资源上限；
- #232 加密备份允许未认证的额外 payload；
- #233 旧闹钟迁移 idempotency 问题。

### Android / Chat / 文件 / 终端

- #229 进程恢复时文件浏览/预览 Holder 丢失；
- #184 消息删除 DB-first 一致性；
- #185 SOUL.md 默认写入与读取失败区分；
- #186 PTY UID/GID 与 session workspace；
- #187 文件链接 staging 主线程 I/O；
- #188 粘贴内容提交前消费；
- #189 PTY child reap/zombie；
- #190 VPN 下 Ubuntu DNS/网络切换；
- #183 `minis://` 双编码与 `+` 解码；
- #182 Release VAD JNI/R8 兼容。

### UI / 维护性

- #192 Root 权限模式页面导航入口；
- #216 ProviderRepository 同步 `runBlocking` 持久化；
- #217 ChatViewModel 职责拆分；
- #218 ChatScreen / StreamingMarkdownText 拆分；
- #223 rclone AAR 构建前 16 KiB/ABI 校验建议。

其中部分 Issue 的正文仍引用旧 runtime/broker 术语，或描述的是早于 PR #235 的代码。处理前必须先对最终当前源码重新审计；如果问题已被后续提交解决，应关闭/更新 Issue，而不是照旧正文重复实现。

## 文档已知非缺口

以下内容故意允许保留旧术语：

- `docs/archive/**`；
- `docs/issue-*.md` 中明确标记的历史实现记录；
- regression guard / negative test 中用于阻止旧实现回归的字符串；
- Git 历史与历史 patch snapshots。

这些不是生产依赖。

## 维护规则

1. 新 gap 必须有当前代码、最新测试或可复现设备行为支持；
2. Issue 标题/旧 PR 不能代替当前调用链证据；
3. 修复后重新核对最终目标分支，再从本文件移除；
4. 网络问题必须区分 guest direct networking、兼容 proxy、DNS、VPN/TUN、BPF/UID policy，不得全部归因于 Root；
5. 构建/fixture/CI 证据与物理设备证据分开记录。
