# 06 — 当前已确认缺口

> 基线更新：2026-09-12，`main` 合并提交 `422cc29f`（包含审计提交 `53dada42`）。本文件区分已确认实现缺口、未验证设备行为与历史 Issue 快照；旧 broker/PRoot/storage 结论不作为现役实现依据。

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

源码已经补上 DNS 的 UDP 失败后 TCP 重试，单测通过；小米真机还没重测。HTTPS 测指定 IP 时要保留域名，用 `curl --resolve <host>:443:<ip> https://<host>/`，不要关闭证书检查。

## 无人值守会话网络出口

无人值守会话当前没有系统层网络出口限制。工具权限和例程权限分档属于应用层策略，不构成网络隔离；明确允许的浏览器、搜索/fetch 或完整权限下的命令仍可能访问网络。这是维护者明确决定保留的残余风险，本任务不增加网络出口拦截。

旧 APK 的精简 rootfs 基础包只保证 `curl`/`wget`，因此 Guest 中曾出现
`ping: command not found`。当前分支已将 `iputils-ping` 纳入 provision 包和 readiness probe；
最新 Debug APK 已部署到小米 `24129PN74C` 真机；Guest 内 `command -v ping` 返回
`/usr/bin/ping`，并以 `ping -c 1 -W 5 doubao.com` 实测成功（1 发 1 收、0% 丢包，RTT
约 92 ms）。这只证明当前直连网络路径上的 ICMP 可用，不替代 VPN/DNS/BPF/Fake-IP 矩阵；
HTTP/DNS 检查仍可用受控的 `curl`。

## Guest CLI 命令面

此前确认的“`/usr/local/bin` 只有 `minis-config` 和 `minis-model-use`”缺口已经在当前工作树闭合。Direct Root 不复制上游 PRoot 的 `native_offload` stub，而是在 Ubuntu 启动/恢复时由 `GuestCommandBridge` 为当前已注册的 Android/Minis handler 生成带 loopback token 鉴权的 wrapper；因此命令名、argv/session/cwd/stdin 语义和 Android handler 保持一致，同时不恢复 PRoot、旧 broker 或通用 Root shell。

小米 `24129PN74C` 真机已验证这些命令的 PATH 入口（其中 `android-shizuku-cli` 已于 2026-10-10 改名 `android-root-cli`，改名后未在真机复验）：`android-alarm`、`android-calendar`、`android-clipboard`、`android-contacts`、`android-device`、`android-location`、`android-notification`、`android-open`、`android-photos`、`android-player`、`android-speak`、`android-speech`、`android-weather`、`android-a11y-cli`、`android-shizuku-cli`、`minis-browser-use`、`minis-scheduled`、`minis-sessions-cli`、`minis-config`、`minis-model-use`、`minis-open` 及其浏览器别名；Debug APK 另有 `minis-debug`。`android-device info` 和多个 `--help`/`--version` 调用已获得实际输出。

**仍未补齐：`minis-mcp-cli`。** 上游 Python 命令随旧资产树删除后，当前 Guest 没有同名入口；Android 原生 MCP client/server 不是 CLI 的等价替代。提示词这一侧已经不再引导模型运行该命令：`MCPRepository.mcpPromptFragment()` 现在让模型直接调用 `mcp_<server>_<tool>` 工具。缺的只是 Guest 内的 CLI 入口本身。另外，原生 client 的 STDIO 服务目前在 Android 宿主进程里启动，而不是在 Ubuntu Guest 里，装在 Guest 里的 MCP 服务无法通过 STDIO 连接（2026-10-04 审计 F29，未修复）。

旧 launcher 的 `apk add`/pip 自安装逻辑需要适配 Ubuntu，但 Python MCP 客户端并不天然依赖旧 PRoot。后续应补齐显式安装、依赖、App-owned 配置、权限与进程生命周期，再测试 `tools/list`、调用、配置重载和退出清理；不能把缺失的用户能力归为“旧架构所以不用保留”。本次文档整理只如实记录，不声称已经修复。

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
8. 真机确认 DNS 回退和 `android-photos export` 返回的 `/var/minis/offloads/...` 能直接读取。

没有这些设备证据时，只能声称代码/CI 层通过，不能声称全部设备运行验收完成。

## VScreen 真机记录（2026-10-01，小米 24129PN74C / Android 17 / 以 Root 启动的 Shizuku）

> 历史记录：这是 Shizuku UserService 时期的实测。2026-10-10 起虚拟屏服务改由 libsu root 服务承载，见下方「纯 Root 路线」一节；下文提到的 `USER_SERVICE_VERSION`、adb 启动的 Shizuku 等不再适用。

- 此前设置页显示「Shizuku 授权失败」并不是授权问题：`network_security_config.xml` 里冗余的 `localhost`/`127.0.0.1` `domain-config` 会让 Shizuku 拉起的 UserService 进程在初始化时抛出 `Found multiple conflicting per-domain rules` 并退出，App 侧表现为 8 秒 Binder 超时。已删除该冗余配置（`base-config` 已允许明文，HTTP 仍由 `ProviderTransportPolicy` 限制）。
- Root 启动的 Shizuku 使服务以 uid 0 运行，旧代码硬性拒绝（`root_user_service_refused`）；现按上文接受 root。Binder 身份是按进程而不是按线程的（实测线程内 `setuid` 后虚拟屏所有者仍为 uid 0），所以不能在进程内降为 shell。
- 拉起应用改用 `cmd activity start-activity --display`：手工构造的 `ActivityThread` 不是系统认识的调用方进程，`Context.startActivity` 会得到 `Not allowed to start activity`。输入探测放在拉起之后（空显示屏上没有窗口可接收按键）。
- 在该设备上探测 10 步全部通过（包括虚拟显示屏、UiAutomation、拉起设置页、输入、非黑屏截图）。**未验证**：智能体实际操作时实时查看器的画面帧、adb 启动的 Shizuku（shell 身份）路径、其它 OEM。
- 实时查看器（2026-10-01 同一台设备）：查看器不再每秒截图，而是由 UserService 把虚拟屏的每一帧以 `HardwareBuffer` 经 Binder 推给 App，在自定义 View 里直接绘制（实测滚动时 85–122 帧/秒计数，虚拟屏本身的刷新率是 60 Hz，计数包含重复帧）；触摸按拖动实时转成 `MotionEvent` 注入虚拟屏；查看器可开启 / 关闭虚拟屏、返回 / 主页、在其上启动应用、向聚焦输入框填入文字。
- UserService 改为 `daemon(true)`（版本号 2）：虚拟屏不再随 App 进程被杀而消失，实测强杀 App 后 display 仍在、重新打开查看器能接回。代价是：升级 App 后只有 `USER_SERVICE_VERSION` 变化时 Shizuku 才会换掉旧服务，改动 UserService 或 AIDL 必须同步加大这个数字。**未验证**：Release（R8）构建下的帧流（已补 `IVirtualScreenFrameSink` 的 keep 规则）、adb 启动的 Shizuku（shell 身份）下的帧流与触摸、长时间（数小时）保持。
- 教 AI 知道坐标：`android.vscreen.open`、`status`、`observe`、`screenshot` 的结果都带 `displayWidth/displayHeight` 和坐标说明；虚拟屏上的 `x/y` 默认按显示屏像素（此前默认是「截图坐标」，没有截图就被拒绝，AI 只好先截一张图），截图默认按显示屏原分辨率输出（此前被缩到最长边 1280）。手机上的实际 AI 调用效果**未验证**。
- 虚拟屏的桌面与互不干涉（2026-10-01，UserService 版本 7，小米 24129PN74C 真机验证）：
  - 小米自带的副屏桌面（`com.miui.home/.launcher.SecondaryDisplayLauncher`）在虚拟屏上只画出一片空白，所以 Minis 自带 `VirtualScreenHomeActivity`（应用网格 + 时间，导出但在非虚拟屏上立即 `finish()`，不会成为物理屏的桌面）。开启虚拟屏时和按「主页」都由 UserService 用 `cmd activity start-activity --display` 拉起；桌面里点图标也经 UserService 启动应用（App 进程自己启动别的应用会被 MIUI 的「关联启动」弹窗拦下）。真机验证：桌面显示真实应用图标，点 Chrome 在虚拟屏上打开，物理屏前台不变。
  - 对物理屏的干扰，真机上量到并改了三处：
    1. `UiAutomation.connect()` 之前等于传 0：日志里 `Registering UiTestAutomationService (flags=0x0)` 之后紧跟 `unbindService ... MinisAccessibilityService`，即系统在它存在期间**解绑所有其它无障碍服务**（包括 Minis 自己的和 TalkBack）。第一次修改还没生效：代码优先取了无参 `connect()`；现在取 `connect(int)` 并传 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，日志变成 `flags=0x1`，探测期间 Minis 无障碍服务一直保持绑定。同时把它的事件订阅清零。
    2. 虚拟屏上的窗口一出现就抢走系统的顶层焦点（`mTopFocusedDisplayId` 变成虚拟屏），物理屏的按键 / 键盘就收不到。现在每次启动、点击、滑动、按键之后立即、并在 0.35 / 0.9 / 2 秒后再把焦点还给物理屏的最上层任务（`FocusBridge`，反射 `ActivityTaskManager.setFocusedRootTask/Task`）。真机验证：开屏、启动 Chrome、点击后 `mTopFocusedDisplayId` 一直是 0。
    3. 在虚拟屏拉起应用改成 `NEW_TASK | MULTIPLE_TASK`，已在物理屏运行的应用不会被搬走（Chrome 在虚拟屏上开了独立窗口；**没有**专门拿「同一应用在物理屏已打开」的情况对比过）。
  - **未验证**：AI 实际调用 observe / 点击时的路径（只验证了探测里的 UiAutomation 连接）、adb 启动的 Shizuku（shell 身份）下的上述行为。
- 用手机自己的 `am start`/`monkey` 启动应用时，没指定 `--display 0` 会落到拥有焦点的虚拟屏上（本 App 的主界面曾因此出现在虚拟屏里）；这是测试方法的问题，从桌面图标启动不受影响。
- 探测与 UiAutomation 互斥：同一时刻系统只允许一个 UiAutomation 客户端，Maestro 等自动化驱动在后台时会让「UiAutomation」步骤报 `already registered`。

## VScreen capability pending hardware validation（2026-09-30）

> 历史记录：写于 Shizuku 时期。服务宿主已换成 libsu root 服务，V1–V4 的待验收项仍然有效，只是「UserService」现在指 root 服务。

VScreen 使用 Shizuku 协议 **UserService**（shell 或 root 身份，见 05 合同）和随 Android/OEM 版本变化的隐藏系统 API；能力默认关闭，只有当前系统/ROM 指纹下的设备自检全部通过才允许用户启用。指纹变化或自检失败会持久清除 enabled 状态，必须重新通过自检并由用户再次启用。UserService 仅接受非物理 display ID；物理主屏输入/观察、未经限定的 socket、视频/OCR 路径均不属于本功能，本实现也不增加系统网络出口拦截。

此工作区当前没有连接的 Android 真机或模拟器，因此没有声称隐藏 API 在目标 ROM 上通过真实运行探测。宿主编译与单测不能替代以下设备证据：

- **V1**：在 Xiaomi 15/目标 HyperOS 上实测 UserService 隐藏 API 兼容性、自检失败关闭，以及连续 20 次虚拟显示创建/释放和资源清理。
- **V2**：后台/恢复、双任务租约互斥、跳回主屏、物理屏始终不被输入/观察、安全窗口截图拒绝和虚拟显示释放。
- **V3**：在设备上验证关闭 VScreen 与 probe 失败时设置页、例程保存提示和 UI 工具错误分支，不因异常状态误启用。
- **V4**：在真机运行 READ_ONLY 例程验证 `ls` 成功、`rm` 明确拒绝且运行记录显示摘要；确认 FULL 必须在编辑器确认；从终端 CLI 试图 `--tier full` 被拒；验证只读临时写与 MCP 拒绝实际作用于例程 session。

当前代码将例程 session 临时写路径限定为 `/var/minis/offloads`（Guest `/tmp` 别名），但没有新增网络出口拦截。浏览器工具仍可能提交表单或触发下载，这是 READ_ONLY 档位的残余风险；网络请求在各档位下均没有系统级出口限制。例程 tier 绑定到 `ScheduledAgentRunner` 使用的 session ID；不同 session 的委派目标/唤醒回合不继承该 tier，但仍受 F3 无人值守策略约束。若它们复用相同 session ID，则共同受该 session 当前最严格的活跃 tier 限制。上述行为也需要在设备上核验，不作为已通过的真机结论。

**同会话并发限制：** 当前权限门按 `sessionId` 而非 turn token 识别例程范围，因此在例程运行窗口内，复用同一 `sessionId` 的前台交互、委派或唤醒回合会共享最严格的活跃 READ_ONLY/F3 无人值守状态。不会通过并发回合临时关闭 tier；denial preview 也按 session 收集，尚无 per-turn 归属。独立的 per-turn 隔离/归因尚未实现，宿主测试不能替代该并发行为的设备验证。

## 系统提示词：自定义输入框与提示词模块（2026-09-18）

Settings → System prompt 的主入口现在是设备主人自己的系统提示词输入框（类似 Codex 的 custom instructions）：
保存内容写入 `<filesDir>/system_prompt/custom.md`，注入到组装后提示词的最前面并带优先级声明，
与人格（SOUL.md）、回复风格、预设、Bot 指令、会话级 Soul 冲突时以它为准；留空则不注入。同一份文本也通过
config registry 暴露为 `prompt.custom`（带确认与审计回退）。内置的工具/风格章节降为二级页
（Settings → System prompt → 内置提示词模块），默认文案在 `assets/prompts/<id>.md` 一文件一节，
覆盖写入 `<filesDir>/system_prompt/<id>.md`，索引与开关为 `prompt.modules`、
`prompt.<id>.text`、`prompt.<id>.enabled`。

已确认的边界与缺口：

- **默认文案未变**：无覆盖且自定义框为空时，组装结果与抽取前提示词逐字节一致（记忆开/关两种状态），
  由 `SystemPromptComposerTest` 的两个 legacy fixture 固定。
- **真机验证在来源仓库完成（minis-for-android a113ad1e，小米 24129PN74C，Debug 包），本仓库未复跑**：
  输入框渲染正常，9.1k 字符文本经剪贴板粘贴并保存成功，
  `custom.md` 落盘 23955 字节；`prompt.custom` 经 config bridge 可读；
  `debug.llmRequests` 抓到的真实请求里，自定义块位于内置模块之前（offset 1065 < 11187）；
  模块的编辑/开关/恢复默认在真机往返验证，agent 侧写入会实时反映到已打开的设置页。
- **覆盖与自定义文本不进入备份**：`<filesDir>/system_prompt/` 不在 backup/restore 类别中，换机恢复后自定义内容会丢失。
- 运行时片段（skills / MCP / GLOBAL.md / 每日记忆 / Runtime context）仍由既有路径生成，不在这些模块内。

## 历史队列

旧 Issue 编号和 2026-09-10 的状态快照移至[历史记录](../archive/RUNTIME-HISTORY.md)，不再混入当前缺口。是否仍开放、是否已修复，处理前查源码和远端状态。

## 文档已知非缺口

以下内容故意允许保留旧术语：

- `docs/archive/**`；
- `docs/issue-*.md` 中明确标记的历史实现记录；
- regression guard / negative test 中用于阻止旧实现回归的字符串；
- Git 历史中的旧计划与 patch snapshots（不再随当前文档树保存副本）。

这些不是生产依赖。

## 维护规则

1. 新 gap 必须有当前代码、最新测试或可复现设备行为支持；
2. Issue 标题/旧 PR 不能代替当前调用链证据；
3. 修复后重新核对最终目标分支，再从本文件移除；
4. 网络问题必须区分 guest direct networking、兼容 proxy、DNS、VPN/TUN、BPF/UID policy，不得全部归因于 Root；
5. 构建/fixture/CI 证据与物理设备证据分开记录。

## 外部参考项目对照（2026-09-18）

对照外部参考项目（Eta，Mangi-11/Eta @ c15de97）的行为与设计后确认的缺口。逐项证据与可移植规格见 `docs/analysis/`；参考关系与许可边界见 `PROVENANCE.md`。

已在本分支闭合：

- MCP 客户端响应无字节上限：`mcp/client/MCPHttpTransport.kt` 之前用 `body.string()` 整体读入。现按 4 MiB 预算读取，先查 `Content-Length`，再按流分块计数，超限失败关闭。
- `tools/list` 只读 `input_schema`：规范字段是 `inputSchema`（camelCase）。现两种拼写都接受，规范拼写优先。
- 工具入参只查必填与空串：preflight 追加 `tools/runtime/ToolCallValidator.kt` 的一致性检查，超过 256 KiB、嵌套超过 32 层或声明类型未知时失败关闭。
- 技能 ZIP 导入无界：`data/repository/SkillArchiveReader.kt` 按压缩流 32 MiB、单条目 4 MiB、总量 16 MiB 预算读取，并拒绝绝对路径、`..`、反斜杠、盘符、NUL 与重复条目。
- 技能安装非事务：`SkillPackageInstaller.kt` / `SkillRecoveryJournal.kt` / `SkillMutationLock.kt` / `SkillTransactionStore.kt` 把安装改成暂存 → 原子提交 → 恢复日志 → 跨进程文件锁；`SkillRepository` 的 add/update/rename/delete/rescan/import/读路径与 `BackupImporter` 的技能恢复全部走同一事务，恢复未完成时 fail-closed。
- GUI 动作只回布尔：`tools/android/AndroidUiActionEvidence.kt` 引入五态证据与来源并写进工具 JSON；`accessibility/MainThreadCallGate.kt` / `MainThreadCallBridge.kt` 覆盖手势、pinch、back/home 与 CLI key 的迟到调用；`set_text` 增加读回校验（密码字段不回读）。
- 截图与 UI 观察窗口不一致（代码层）：观察、截图、ref 解析与锚点采样共用 `MinisAccessibilityService.visibleWindowSet()` 与 `ScreenshotWindowPolicy`，不可解析包名或窗口表不可读时拒绝，截断快照不再被当作“未变化”。
- 压缩缺批次边界与摘要校验：切割只在完整工具批次之间、保护最新用户轮，摘要需正常结束、非空、有界、无工具调用且确实缩小；`chunkForSummary` 现在真正驱动多次摘要调用，溢出对半受 `MAX_OVERFLOW_ATTEMPTS` 约束，区间起点也回退到安全边界。
- 敏感工具原文落盘：`tools/ToolSensitivePolicy.kt` 覆盖 Room transcript、运行检查点 transcript 与模型上下文快照；匹配按注册表同款归一化，并修正了此前写成 `android_clipboard` 等**从未匹配任何已注册工具**的名字。
- 在途 run 无恢复记录：`RunCheckpointStore` / `RunCheckpointRecorder` / `RunContextSnapshot` / `RunRecoveryCoordinator` 记录 UI 事件、脱敏 transcript 与模型上下文快照并在加载时对账；活跃状态或终态未知时不判定中断。
- 记忆注入无窗口预算补完：`MemoryInjectionBudget` 增加内容 `revision`（SHA-256）与注入片段头部，正文沿用窗口预算、截断标记与标题索引。
- 模型失败无分类、重试无纪律：`provider/LLMFailureClassifier.kt` 提供三分类与 `decide()`；已产生副作用后失败即终态、重试前丢弃失败轮思考、退避可取消、溢出走压缩后重试。

仍开放（本次仅确认，未实现）：

- 上述所有移植项的真机结论：技能事务的 rename/fsync 行为、无障碍窗口集合与截图包含关系、运行检查点在真实进程死亡后的恢复动作、设备端 `dumpsys window` / `wm size` 输出差异，均未做真机验证。
- `ChatRepository.summarizeToolUse` 会把 `memory_write` / `memory_get` 的参数截断到 100 字符写进 `chat_sessions.last_message_preview`（会话列表预览，不属于 transcript），未脱敏；是否收敛由产品决定。
- 敏感工具分类表按工具名维护（注册表归一化 + `mcp_` 前缀），新增读取私密数据的工具需要同步补录。
- 流式 Markdown 缺未闭合结构投影：行内标记未闭合时会先渲染字面量再突变。
- 启动健康状态是首个失败短路，没有聚合视图。

以上开放项均未做真机验证，不得据此声称设备行为结论。

## 纯 Root 路线：移除 Shizuku（2026-10-10）

项目只走 Root，不再接入 Shizuku 协议（Shizuku / AXManager / Sui）。移除前的完整代码存档在分支 `archive/before-root-only`（`6a6b450`）；免 Root 的 PRoot + Ubuntu 后端记为暂缓的 Issue #184。

改动：

- `android-shizuku-cli` 改名 `android-root-cli`，子命令和 JSON 不变，底层经 `RootProcess` → `DirectRootRunner` 用 `su` 执行。权限开关 `shizuku_cli` 自动迁移为 `root_cli`。旧 rootfs 里残留的 `android-shizuku-cli` 包装脚本在下次安装 guest CLI 时删除。
- 虚拟屏服务改由 libsu `RootService`（daemon 模式）承载，只接受 uid 0；保存的旧探测结果因指纹里多了宿主标记而失效，需要重新通过一次自检才能启用。
- 无障碍授权修复、受限设置解除、一键授权都改用 Root；设置里的「Root 与 Shizuku」页换成「Root」页，就绪清单去掉 Shizuku 项。

只有编译、JVM 单测和 lint 的证据，以下**全部未在真机验证**：

- libsu `RootService` 在 HyperOS / KernelSU 上的首次绑定（含 su 授权弹窗）、daemon 模式下强杀 App 后虚拟屏保留与重新接回、App 升级后旧 daemon 被结束；
- `ShellContext.initialize()` 在 libsu 拉起的 `app_process` 进程里的行为（此前只在 Shizuku UserService 进程里验证过）、10 步探测、UiAutomation、帧流与触摸；
- `android-root-cli` 各子命令经 `su` 的结果与耗时（每次调用都新起一个 su 进程）；
- Release（R8）构建下 libsu 自带的 keep 规则是否足够。

已知缺口：

- 设备完整性保护（Issue #182）已实现（见 04 安全合同），但见下一节：全部未在真机验证。

已接受的设计（不是缺口）：

- 远程 MCP 调用方可以经 `linux.shell` 在 guest 里调用 `android-root-cli exec`。维护者决定保留：每次调用都需要用户在手机上批准一次性 confirm（`MCPServer.handleToolCall` 的 MCP_CONFIRM 门，`AutonomyMode` 不参与，「全自动」不会跳过它），且 `root_cli` 开关默认关闭。这与 `root.shell`（本地专用、MCP 不可见）是两回事；改为拒绝远程调用需另开 PR。

## 终端会话归 App 所有（#183，2026-10-10）

已实现（宿主测试覆盖逻辑，未上真机）：`TerminalSessionManager`（App 级单例）持有用户终端及其 emulator，Terminal 页只附着/分离；`TerminalForegroundService`（`specialUse`）在有存活终端进程时常驻；标签页（上限 8）；鼠标上报 1000/1002/1003 + SGR 1006；全屏程序里滑动转方向键（遵守 DECCKM）；捏合缩放并重算行列；括号粘贴；`AgentTerminals` 空闲回收不再关闭前台有程序的终端。

判断“终端里有程序在跑”靠两个信号：PTY 主端的 `tcgetpgrp()` 与 shell 自身进程组的比较；以及 guest 每个提示符输出的 `OSC 133;A`（回车后没看到新提示符 = 仍在运行）。无法判断时按“在运行”处理（关闭前确认、不回收）。

**未验证（需要真机）：**

- 小米 24129PN74C / HyperOS 切到后台 30 分钟以上，终端进程是否被系统或 OEM 杀掉；前台服务通知在该机型上的表现。
- 原生 `tcgetpgrp` JNI：生产 C 代码已在 Linux 宿主用真实 JVM 和子进程验证（`scripts/test_pty_bridge.py`：空闲 shell 等于自身进程组、前台有程序时不等、坏 fd 返回 -1）；Android NDK / bionic 构建与真机结果未验证。
- 当前 `su`（KernelSU / Magisk / APatch）是否把 PTY 直接交给 shell。如果某个 `su` 用自己的 pty 中继，PTY 主端看到的前台进程组不会变化，这时只剩 `OSC 133;A` 一个信号；用户在 `.bashrc` 里替换了 `PROMPT_COMMAND` 时两个信号都会失效，终端会被当成空闲（关闭标签不提示，Agent 空闲回收可能关掉它）。
- vim（`:set mouse=a`）、htop、tmux（`set -g mouse on`）、less / man 中点击与滚动的实际手感；捏合后 `tput cols` 随之变化。
- 往 bash 粘贴多行内容不逐行执行（括号粘贴在真实 bash/readline 上的表现）。

**已知限制：**

- 系统或 OEM 杀掉 Minis 进程、升级 App 时，PTY 主端随进程关闭，shell 收到 SIGHUP，任务照样断。这是第二阶段（PTY 持有者放到 App 进程之外，如 guest 里的 tmux / dtach，重启后重新附着），#183 不做；`TerminalSessionManager` 作为唯一持有者，不堵这条路。
- 运行时维护（`TerminalSession.stopAll*`，由 rootfs 修复、挂载变更触发）调用方在非 UI 的运行时层，无法在那里弹出确认。实现为“停止前通知管理器”：Terminal 页弹一次对话框，页面不在前台时发一条通知。需要“先确认再维护”的流程要由发起维护的 UI 自己先检查 `TerminalSessionManager.busyCount()`，目前没有这样的入口。
- Agent 终端的标签是文本快照（没有颜色、光标位置），不是第二个 emulator 视图；“接管”后输入直达该终端。
- 剩余的后台 job（`cmd &`）不算前台程序：关闭标签不会提示，也会被当作空闲。

## 2026-10-08 之后新增的未验证项

下列行为已有单元测试或模拟器测试，**没有**在真机上完整验证，不得据此声称设备结论：

- 后台 shell 作业的 stdin（`job_input`）：命名管道在 chroot 内的行为只在宿主测试中验证，真机上只验证了写入与 EOF 的基本路径。
- 特殊权限（安装应用、精确闹钟、使用情况、全屏通知、修改系统设置、电池优化与流量节省豁免）的一键授予：小米手机上验证了其中三项；全屏通知、修改系统设置的授予和各 OEM 的设置页入口未验证。
- 模型错误的本地化说明（`LLMErrorPresenter`）：按状态码和各家服务商的错误格式写了单元测试，没有在真机上让服务商真正返回 400/404/413 去触发。
- 压缩截止点按"气泡合并的存储 id"匹配：有单元测试，没有在真机上复现原来"整个对话变灰"的场景。
- 文本附件正文内联（每文件 60k 字符、每条消息 120k）：用脚本化模型的模拟器测试验证了请求体内容，没有对真实服务商验证过 token 开销。
- 以上之外，`minis-mcp-cli` 仍缺失，其余设备验收边界不变。

## PR4 — 下个版本移除旧模型组 Room 表（暂缓）

PR2/PR3 只把运行配置迁移到固定 Model Slots，并保留旧表作为迁移/回滚兼容边界；本阶段不删 `provider_model_groups` 表、不升级 Room schema。后续 PR4 才能在数据升级路径和真机备份恢复验收完成后删除该表并将 Room schema 从当前 v4 升至 v5。

## 设备完整性保护（Issue #182，2026-10-10）

只有 JVM 单测、编译和 lint 的证据；`ProtectedView` 生成的脚本、mount namespace、能力裁剪、存储水位冻结都**没有在真机上跑过**，不得据此声称设备结论。需要在小米 24129PN74C / HyperOS（KernelSU）上确认：

- `unshare -m` 在 `su` 起的进程里可用，`mount -o rprivate,bind / /`、`/dev/block` 换 tmpfs、对 `/data/adb`、`/apex` 等目录的只读 bind 与 `remount,bind,ro` 都能成功；任何一步失败会让特权命令返回 `DEVICE_PROTECTION_UNAVAILABLE`（失败关闭）；
- rootfs 里的 `setpriv` 经 `ld-linux-aarch64.so.1 --library-path` 在 chroot 之外能运行，`--bounding-set` 去掉 `CAP_SYS_ADMIN` 后 `pm`/`cmd`/`settings`/`am`/`dumpsys`/`input`/`wm`/`appops` 与现有 `android-root-cli` 子命令没有回归；
- 视图里 `mount -o remount,rw /system`、`dd of=/dev/block/…`、`rm -rf /data/system` 确实失败，`su` 桩生效；
- KernelSU 的 `/data/adb/ksu/bin/su` 被桩覆盖后，视图外的 App 自身 `su` 不受影响；
- 存储水位：guest 写大文件逼近阈值时自动冻结、发通知，清理后恢复；冻结只作用于 `shells/shell-*.pid` 记录的进程组；
- 小米补充名单（`com.miui.securitycenter`、`com.lbe.security.miui`、`com.miui.home`、`com.xiaomi.xmsf`、`com.miui.system`）需真机核对，其余 OEM 没有补充名单。

已知残余：

- 原始 `service call` 直接发 binder 事务，不经 `pm`/`cmd`/`settings`/`am` 前端，绕过核心包和设备状态检查。本保护防的是 Agent 手滑，不防刻意绕过。
- 命令词判定看不穿变量、`$(…)`、脚本文件；这类只靠视图兜底，且视图只管路径类（分区、系统分区、`/data` 系统数据），管不到 binder 类（核心包、恢复出厂、用户 0）。
- `/data/adb/minis` 因例外保持可写，`rm -rf` 它会毁掉 rootfs（可重装），不会让手机变砖。
- `/apex`、`/system` 等目录在系统挂载为只读的设备上，视图的只读 bind 是第二道保险，不是第一道。
- 分区读取（`partition read`）在视图之外以 root 执行，只读块设备，目标文件走与其它命令相同的写路径检查。
