# 设备控制 CLI 与 App／沙箱职责续查

日期：2026-10-05。只读源码审计；未修复产品代码。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。对照为本地只读 openminis 快照 `/tmp/openminis-review-20261004`，提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。上游文件通过 `git show` 读取，不以稀疏目录缺失推断功能不存在。本文独立于前七份报告。

## 职责判断

用户指出的方向成立：命令叫 guest CLI，不代表工具的实现和管理已进入沙箱。[GuestCommandBridge.kt:233](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:233) 将命令交给 App 中的 Kotlin handler。`ShizukuOffloadHandler` 有 1,257 行，`AccessibilityOffloadHandler` 有 1,218 行；其中包括命令行解析、十二组系统命令包装、网络策略选择、通知解析、UI 搜索／等待、列表和表单提取。这些流程仍随 APK 固化，Agent 在沙箱内无法独立维护其实现。

工程发现、APK 选择、组合部署与诊断已在[前一份安卓开发工具报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-android-tool-ownership-audit.md:27)记录，本轮不重复计数。SDK／Gradle 实际构建仍通过 guest shell，不能说编译器已经全部搬进 App。

按用户要求，应将开发工作流、命令包装及 UI 自动化策略放到 guest 的工具／技能中，由 Agent 使用和管理。App 保留必要的 Android framework／Binder／Accessibility／安装接口，以及身份、授权、会话文件、输出与进程生命周期边界。授权和脱敏不能靠 Agent 自觉维护。本轮只提出边界与发现，不实施迁移，不恢复 minisd、PRoot 或通用 Root broker。现役合同允许 App 持有 Android 原生工具，因此职责调整是用户明确提出的目标，不能把每个 Kotlin Android 接口都判成合同违规。

## 新问题索引

P1 表示可能作用于错误目标、破坏数据／权限边界或缺少资源上限；P2 表示功能错误与结果误导。优先级基于静态调用链，不代表已在真实设备复现。

| 编号 | 优先级 | 问题 |
|---|---|---|
| DC01 | P1 | 无障碍 CLI 序列化 password 节点值，绕过已有脱敏 |
| DC02 | P2 | 点击被拒绝、第二次点击失败仍返回成功 |
| DC03 | P2 | 多窗口中任一窗口没有目标，就宣称目标已消失 |
| DC04 | P2 | “等待稳定”忽略子树变化 |
| DC05 | P1 | 单包 cache-only 实际调用全局缓存清理 |
| DC06 | P1 | Wi-Fi restrict 加入联网白名单，语义相反 |
| DC07 | P1 | 包 UID 查询失败回退到 0，继续构造网络修改命令 |
| DC08 | P2 | 网络操作失败仍以 exit 0／ok true 收尾 |
| DC09 | P2 | 文本输入把空格改成百分号，并删除撇号 |
| DC10 | P2 | 通知通道操作丢失包／通道目标，block 实际改气泡 |
| DC11 | P2 | guest APK／文件路径直接交给宿主特权进程 |
| DC12 | P2 | 截图丢失上游会话 attachments 语义，返回宿主路径 |
| DC13 | P1 | Shizuku 输出与事件观察在 App 内无限积累 |
| DC14 | P2 | exec 的 timeout／格式参数被拼进实际命令 |
| DC15 | P2 | managed-profile 命令缺少父用户 ID 和 managed 类型 |
| DC16 | P1 | 读取无障碍服务列表失败后，修复逻辑覆盖其它服务 |

<a id="dc01"></a>
## DC01 — password 节点没有经过脱敏

[AccessibilityOffloadHandler.kt:252](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:252) 的 `nodeToJson` 原样读取并写出 `text` 和 `contentDescription`；compact 和完整输出均无 `isPassword` 判断。`ui dump`、`ui find`、`ui node` 和等待出现结果共用这条路径。[同文件:1126](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:1126) 的表单提取还直接写出 text、description／hint；文本、对话框和列表提取也直接读节点 text。

[MinisAccessibilityService.kt:215](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/MinisAccessibilityService.kt:215) 返回框架节点，没有替 handler 生成脱敏节点。项目已有 [UiSensitiveValuePolicy.kt:4](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/UiSensitiveValuePolicy.kt:4)，VScreen 的 [UiNodeUtils.kt:266](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/vscreen/service/internal/UiNodeUtils.kt:266) 对这三种值使用它；[工程合同:42](/home/jiale/projects/minis-for-android/docs/contracts/05-ENGINEERING.md:42) 明确要求物理屏与虚拟屏均不得序列化 password 节点值。服务的文本写入验证也已在 [MinisAccessibilityService.kt:564](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/MinisAccessibilityService.kt:564) 避免读取密码，CLI 观察路径却未保持一致。

影响限定为：框架提供的 password 节点字段会进入模型可见结果；不能断言每个 Android 密码框都返回明文，掩码或空值取决于控件与系统。事件记录另在 [MinisAccessibilityService.kt:119](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/MinisAccessibilityService.kt:119) 无条件采集 event.text，也应随观察出口核对，不能仅修一个 JSON 字段。

后续验收：用自建 password 标记控件和测试 canary，覆盖 dump／find／node／form／text／事件等出口；保留原始控件属性及所有 CLI 响应，断言值未出现。未执行，也未收集真实密码。

<a id="dc02"></a>
## DC02 — 点击失败仍被包装成成功

[AccessibilityOffloadHandler.kt:489](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:489) 的 `tapText` 找到节点后，丢弃 clickable 节点 `performAction(ACTION_CLICK)` 的 Boolean；非 clickable 分支也丢弃 `tapXYRaw` 的错误结果，随后无条件返回 `ok=true`。因此系统拒绝点击、手势取消或超时，Agent 仍收到成功。

[同文件:474](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:474) 的 double tap 只检查第一次手势；第二次失败依然成功，第一次失败也仍继续第二次动作。服务已提供完成／取消／超时证据，丢失发生在包装层。后续验收应覆盖节点拒绝、手势取消、第二击失败，核对回执与实际动作次数。未执行。

<a id="dc03"></a>
## DC03 — 等待消失错误地按单个窗口判断

[AccessibilityOffloadHandler.kt:824](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:824) 在每个 root 内重新创建 matches；`present=false` 时，任何一个 root 没有目标都会立即返回 `disappeared=true`。例如窗口 A 没有“提交”，窗口 B 仍显示“提交”，无论遍历先后，只要碰到 A 就会误报消失。

应在一次完整、可用的窗口快照上判断目标是否在所有可观察窗口中缺席，并区分读取失败。后续验收用两窗口分别含／不含目标，交换窗口顺序并模拟窗口读取失败；保留窗口树和回执。未执行。

<a id="dc04"></a>
## DC04 — 稳定签名没有观察子树

[AccessibilityOffloadHandler.kt:868](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:868) 的 `treeSignature` 只摘要 root.text、root.childCount、windowId，以及前台包／类。深层文本更新、列表替换、按钮启用状态、位置变化都可以不改变这些值。[同文件:848](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:848) 随后把签名不变等同 UI 稳定，可能在页面仍更新时让 Agent 继续。

后续验收让固定根布局下的子节点持续变化，分别测试开始稳定和始终不稳定；保存节点快照时间序列与 stable 返回时间。未执行。此类等待策略适合 guest 工具管理，App 应提供可靠观察状态。

<a id="dc05"></a>
## DC05 — cache-only 丢失包目标，变成全局操作

[ShizukuOffloadHandler.kt:237](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:237) 接收 `package clear <pkg> --cache-only`，却执行 `pm trim-caches 1G`，没有 pkg，也没有请求中的 user；exit 0 后返回 `cleared=<pkg>`。

这条系统命令按存储卷与期望空闲空间清理缓存，不提供单包目标，可能清理其它应用缓存，也不能证明指定包的缓存已清空。[AOSP PackageManagerShellCommand：runTrimCaches／help](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/pm/PackageManagerShellCommand.java) 支持这一命令语义。不能将它当作“只清目标包”的近似实现。

后续验收应核对多包缓存及不同剩余空间条件，确认范围与回执真实一致；本轮没有实际清理任何缓存。

<a id="dc06"></a>
## DC06 — 网络限制方向相反，而且没有 Wi-Fi 专属语义

[ShizukuOffloadHandler.kt:658](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:658) 对 `network restrict <pkg> --wifi` 执行 `add restrict-background-whitelist UID`；allow 则 remove。AOSP 的该 whitelist 表示允许计量网络后台流量，加入是豁免，方向与 restrict 相反；它也不是禁止／允许所有 Wi-Fi 流量。[AOSP NetworkPolicyManagerShellCommand：setUidPolicy／addRestrictBackgroundAllowlist](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/net/NetworkPolicyManagerShellCommand.java)

同段的 data blacklist 也针对计量网络后台策略，不能据此宣称关闭移动数据全部流量。background 分支设置后台运行 AppOp，不能混同为通用网络防火墙。系统／OEM 最终支持情况未验证；命令选择与名称不符已由源码确认。

后续验收应分别记录策略表、前后台、计量／非计量网络下的实际请求结果，禁止仅凭 shell exit 0 宣称断网。未执行。

<a id="dc07"></a>
## DC07 — 查询失败把目标 UID 变成 0

[ShizukuOffloadHandler.kt:683](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:683) 对 dumpsys package 不检查 exitCode；未匹配 `userId=` 时返回字符串 `0`。不存在的包、输出变化、超时或读取失败，都可能继续构造 UID 0 的网络策略修改命令，stats 也会查询／报告 UID 0。

这是错误目标选择；应拒绝继续执行并说明包身份未知。未证明设备接受对 UID 0 的具体 netpolicy 修改，不能声称已经改变 root 流量。后续验收覆盖不存在包、查询失败／超时和目标用户不同的情况，保留 argv，确认未知身份时没有任何修改命令。未执行。

<a id="dc08"></a>
## DC08 — 网络操作错误没有传播到工具状态

[ShizukuOffloadHandler.kt:650](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:650) 仅将每条命令是否 exit 0 存为 Boolean；[同文件:669](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:669) 无条件 `okEnvelope`，即使全部失败，仍是 exit 0 和 `ok=true`，没有失败 stderr／exitCode。未提供任何作用域 flag 时也成功返回空对象，实际没有操作。

后续验收让其中一项或所有项失败，并覆盖缺少 flags；要求整体状态明确反映失败／部分完成，保留各项真实回执，防止 Agent 误认限制生效。未执行。

<a id="dc09"></a>
## DC09 — 输入文字被静默改写

[ShizukuOffloadHandler.kt:728](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:728) 将空格替换为单个 `%`，并删除所有 `'`，然后通过 argv 调用 input text；成功回执却仍显示原文字。例：`a b` 实际参数变成 `a%b`，`don't` 变成 `dont`。

AOSP 的 input text 识别 `%s` 为空格，单个 `%` 并不等价；已经使用 argv，也没有因 shell 单引号而删除正文字符的必要。[AOSP InputShellCommand：sendText](https://android.googlesource.com/platform/frameworks/base/+/8417c7bb1cbb/services/core/java/com/android/server/input/InputShellCommand.java)

后续验收覆盖空格、撇号、百分号及平台不支持字符，核对实际控件内容与回执。未执行。

<a id="dc10"></a>
## DC10 — 通知通道包装不保持调用目标

[ShizukuOffloadHandler.kt:793](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:793) 验证了 list 的 pkg，却不用于 dumpsys 命令或结果过滤；随后扫描全局通知输出中的所有 channel，可能把其它包的通道归到本次查询。

[同文件:807](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:807) 的 `channel set <pkg> <channelId> --block/--unblock` 虽要求 channelId，却只执行应用级 `set_bubbles pkg 0/1`，完全不传通道 ID。系统修改的是应用气泡偏好，不是指定通知通道的屏蔽状态。[AOSP NotificationShellCmd：set_bubbles](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/notification/NotificationShellCmd.java)

这里不额外断言所有 OEM 都不支持 importance 分支的命令；当前 block／unblock 的命令与目标错误已经足够成立。后续验收用两个包、多个通道，分别核对 channel 状态、其它通道和应用气泡设置。未执行。

<a id="dc11"></a>
## DC11 — guest 路径进入了宿主进程的路径空间

[ShizukuOffloadHandler.kt:191](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:191) 把 APK 参数直接传给宿主 Shizuku 的 pm install；[同文件:852](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:852) 的 pull／push 也是宿主 `cp -r src dst`。这些方法没有使用 request.sessionId／cwd，没有 guest 路径解析或暂存。

guest `/workspace` 由 [UbuntuKernel.kt:548](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:548) 绑定对应会话目录；宿主 Shizuku 进程没有因此进入该 chroot 的路径空间。Agent 从 `/workspace/app-debug.apk` 安装，或将设备文件 pull 到 `/workspace/...`，会查找宿主对应路径，不能兑现沙箱内“local”文件语义。合法 Android 宿主路径仍可工作；不声称全部安装失败。

命名 Android 工具已有 session 文件暂存路径 [AndroidApkInspector.kt:215](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt:215)，其缓存缺陷另见 AD02，不能直接当作已经安全。后续需要一个明确的文件域边界，而不是继续维护两套含混路径规则。验收覆盖两会话同 guest 路径、相对路径和显式宿主路径，保留源／目标文件摘要。未执行。

<a id="dc12"></a>
## DC12 — 删除 PRoot 依赖时丢失了截图文件语义

[AccessibilityOffloadHandler.kt:422](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:422) 默认写入 `getExternalFilesDir("a11y_screenshots")`，失败时退到 cacheDir，再把 file.absolutePath 放进 path。分发到 `uiSub` 时已不传 request.sessionId。

固定上游的 `sandbox/offload/AccessibilityOffloadHandler.kt` 会将截图放入调用会话的 attachments/screenshots，返回 guest 路径、minis_url，并单列 host_path；还处理 helper 实际挂载的会话。本地迁移删除 PRootKernel 依赖时，把整个会话文件解析一并替换掉，而非改接现役文件边界。

现役 bind 在 [UbuntuKernel.kt:535](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:535) 为会话 attachments 准备目录，随后挂到 `/workspace/attachments`；这个任意 externalFilesDir／cacheDir 截图目录不在该绑定中。默认响应因而不能直接作为 guest 可读路径，截图也没有会话归属。`--inline` 可绕过文件路径问题，不等于默认路径已正常。未证明其它全局映射泄露此文件。

迁移方向是保留会话 attachments 契约，改用 Direct Ubuntu 的现役映射，不恢复 PRoot。后续验收从两个会话和 helper 分别截图并直接读回，对比 path、内容摘要与实际 backing。未执行。

<a id="dc13"></a>
## DC13 — 输出资源在 App 内缺少字节上限

[ShizukuBackend.kt:220](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/ShizukuBackend.kt:220) 的 stdout／stderr reader 持续往 StringBuilder 追加，既无总字节上限，也无单行上限。进程 timeout 只限制时间，不能限制高速输出在该时间内占用的 App 堆；handler 之后还会构造 combined 和 JSON 副本。传输层最后才限长也来不及保护这段内存。

[AccessibilityOffloadHandler.kt:909](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/AccessibilityOffloadHandler.kt:909) 的事件 watch 虽把时长压到最多 120 秒，但单次订阅队列和最终输出 StringBuilder 都无事件数／字节上限。服务自己的 eventRing 有 1,024 条上限，不能替独立 watch 队列限制资源。

应在原生读取／收集边界控制资源、明确标记截断，并统一超限时的进程／监听者清理；策略工具下沉后也不能把这一宿主保护交给 Agent。后续验收用有界的大输出和高频测试事件，核对峰值内存、终止、输出截断标志与后台线程／监听者回收。未执行，没有发送资源耗尽输入。

<a id="dc14"></a>
## DC14 — exec 把自己的参数传给子命令

[ShizukuOffloadHandler.kt:938](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:938) 一方面从 OffloadArgs 读取 `--timeout-ms`，另一方面把未剔除选项的 rest 全部 join 成命令交给 `sh -c`。其帮助明确允许末尾 timeout 参数；例如 `exec sleep 2 --timeout-ms 1000` 实际执行文本中仍含 `--timeout-ms 1000`，使它变成 sleep 的参数。`--compact`／`--quiet` 等格式参数也有相同问题。

应明确 CLI 自身参数与原始子命令的边界，保留合法子命令参数和 quoting。后续验收保存请求 argv、实际子命令 argv、回执格式与 deadline；覆盖无选项、带 timeout 和带格式选项。未执行。该项是命令序列化错误，不重复此前 root.shell 权限入口的问题。

<a id="dc15"></a>
## DC15 — managed-profile 生成的是缺参数命令

[ShizukuOffloadHandler.kt:604](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ShizukuOffloadHandler.kt:604) 在 `--managed-profile` 时只加 `--profileOf`，然后直接追加 name；没有父用户 ID，也没有 `--managed`／managed user type。正常名字会被系统当作父用户参数解析，后续也缺少用户名字。

AOSP 明确要求 profileOf 后跟 USER_ID，managed 类型由独立参数设置。[AOSP PackageManagerShellCommand：runCreateUser／help](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)

后续验收先检查构造出的完整 argv，再在获授权的设备场景验证父用户和 profile 类型；本轮未创建／删除任何用户。

<a id="dc16"></a>
## DC16 — 修复读取失败可能关闭其它无障碍服务

[AccessibilityRecoveryManager.kt:300](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/accessibility/AccessibilityRecoveryManager.kt:300) 读取 ENABLED_ACCESSIBILITY_SERVICES 抛异常时返回 emptyList，随后以“空列表 + Minis”构造 merged，通过已授权 Shizuku 覆盖整个 secure setting。

因此 App 普通权限读取失败、但 Shizuku 写入成功时，本来启用的其它服务会被从列表移除。该方法的注释承诺保留 TalkBack／密码管理器等服务，实现却把“无法读取”当成“没有其它服务”。同一管理器的 isGrantRevoked 已将读取异常与确认撤销区分，修复分支没有保持这一失败关闭边界。

应在无法确认原列表时拒绝覆盖，保留原始状态；并核对读写之间列表变化，避免丢更新。后续验收让其它测试服务已启用，模拟 App 查询失败而特权写可用，确认不会写出仅含 Minis 的列表。未执行，也未操作系统设置。

## openminis 对照与归因

对照不是把上游当作无错标准。Shizuku 包装中的 packageClear、networkRestrict、pkgUid、inputText、notificationChannel、filePullPush、packageInstall、handleExec、userCreate，与固定上游相应完整函数段一致。无障碍 handler 的 nodeToJson、tapText、waitAppear、treeSignature、collectFormFields、eventWatch，以及修复管理器 repairWithShizuku，也保留相同函数段。

因此 DC01—DC11、DC13—DC16 属于继承逻辑／共同缺口；DC01 另与本地明确密码观察合同及已有脱敏接口冲突。DC12 是本地移除旧运行时依赖时的功能回退。没有把这些发现归咎于本轮可能正在工作的其它 Agent，也没有据这些 CLI 认定退役 minisd／PRoot 已恢复生产。

## 完整阅读与验证范围

本轮新增全文阅读 13 个文件，共 4,302 行：两个 handler；ShizukuManager／Backend；MinisAccessibilityService、NodeRegistry、AccessibilityRecoveryManager、MainThreadCallBridge／Gate、ScreenshotWindowPolicy；UiSensitiveValuePolicy；既有 ShizukuBackendStateTest 与 UiSensitiveValuePolicyTest。既有测试只阅读，未运行，不能证明 CLI、原生进程或设备行为已通过。

累计可核验全文记录为 **53／1,546 个代码／配置／源资源／fixture 文件，11,640 行**；另外 6 个文件仅片段核对，不算全文。全仓库 1,670 个 tracked 文件，124 个普通文档／许可证／二进制／词典数据不在该阅读分母。当前任务还远未达到“所有代码读一遍”，不以搜索命中充当全文覆盖。

具体文件版本 SHA-256 与全文区间登记在[阅读覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json:1)。本轮片段核对了权限门禁及 Ubuntu 会话 bind；授权门禁存在，不能把以上功能错误夸大为任何远程调用方都可直接控制设备。

没有修改产品代码，没有构建、运行测试、启动模拟器、调用 adb／Root／设备命令或使用 cloud 资源。正文验收均为后续建议，未执行。仅做本地源码、上游固定快照、官方 AOSP 命令语义及报告链接／版本的静态核对；OEM、SELinux、Shizuku 实际权限和系统最终效果仍需设备证据。
