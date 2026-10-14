# 应用入口、后台服务与权限等待通道静态审计

日期：2026-10-05。源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。固定 OpenMinis 对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，本地对象库 `/tmp/openminis-review-20261004`，本轮没有拉取或执行上游代码。

本轮只阅读、记录。未修改产品代码，未运行测试、构建、模拟器、adb、Root、设备或云任务。旧报告保持原样。以下触发顺序是源码推演，验收描述均待后续明确授权执行，不是已完成的设备复现。

## 范围与论文关系

论文及引用聊天已另行记录在[论文复核报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-harness-paper-review.md)。本轮继续核对 Harness 的具体责任：设置写入是否真正改变执行行为、状态是否属于正确会话、权限答复是否属于正确请求，以及 Android 生命周期是否保持这些关系。

这些问题不能靠更换 Agent 框架或把所有 Android 代码移入 guest 解决。系统权限弹窗、前台服务、Launcher alias 和 Android 通知仍需要 App 原生桥；开发工作流如何由沙箱内 Agent 管理，已有独立报告。本轮也未重复注释噪音、通用 helper 重复、Lint 基线及上一轮 CB07 的能力探测缓存问题。

本轮新增 **13 项**，均按 P2 记录；其中 2 项涉及权限请求关联，影响等待/拒绝/返回的正确性，但没有设备证据支持将其描述成 Android OS 权限绕过。

| 编号 | 已定位的问题 |
|---|---|
| BG01 | CLI 后台通知开关写入未被通知组件读取的偏好键 |
| BG02 | CLI Live Updates 写入正确偏好键，却没有更新运行中的 StateFlow |
| BG03 | 增强后台执行开关没有生产消费者 |
| BG04 | UI 关闭 Live Updates 后没有立即重建普通通知 |
| BG05 | 并行会话共用工具名称、结果及跳转目标 |
| BG06 | Android 权限与设置通道的请求覆盖、取消清理和回调缺少归属 |
| BG07 | 异步完成通知能越过前台清理和关闭开关 |
| BG08 | Activity 重建会重放冷启动链接并覆盖较新的导航状态 |
| BG09 | 外观页的选择状态不跟随 CLI 修改 |
| BG10 | 图标切换失败没有恢复 alias，UI 仍显示成功选择 |
| BG11 | 隐藏悬浮窗跨屏幕尺寸恢复时没有校正持久化位置 |
| BG12 | 子代理完成被当成用户任务完成发送系统通知 |
| BG13 | App 强制浅色与系统深色时，系统栏图标仍按系统深色选择 |

## BG01 / P2 — 后台通知配置写入另一把键

[ConfigBuiltins.kt:427](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:427) 注册 `background.notifications`，后端是 `background_settings / background_notifications_enabled`。实际 [BackgroundSettingsRepository.kt:24](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BackgroundSettingsRepository.kt:24) 初始化通知开关时读取的是 `taskNotificationsEnabled`，常量见同文件第 89 行。UI 与 [BackgroundTaskNotifier.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt:70) 都消费 repository 的流。

因此 CLI 设置 false 并读回 false，实际通知仍按原开关发出；重启也不能修复键名错位。反过来 UI 关闭通知后，CLI 仍可能报告默认 true。这里不是 apply 异步落盘延迟。固定上游 ConfigBuiltins 第 473–495 行与 repository 第 24–37 行有同样错位。

修复方向：配置字段通过实际 repository 读写，保持一个权威值。待验收：CLI→UI→后台完成及 UI→CLI 双向一致，重启后保持；关闭通知不得继续发出完成通知。

## BG02 / P2 — Live Updates 的 CLI 写入没有驱动活跃组件

[ConfigBuiltins.kt:468](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:468) 的键名 `dynamicIslandEnabled` 正确，但 [PrefsFields.kt:34](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/fields/PrefsFields.kt:34) 只写 SharedPreferences。[BackgroundSettingsRepository.kt:65](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BackgroundSettingsRepository.kt:65) 仅构造时读取偏好，其流只在 `setDynamicIslandEnabled()` 内更新，没有偏好监听器。

[后台页:97](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/BackgroundSettingsScreen.kt:97)、[服务:377](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/AgentForegroundService.kt:377) 和通知构造第 781 行均读旧流。因此在能力已经具备、字段可写的进程里，CLI 成功也不能改变当前 UI、promoted notification 或浮窗互斥选择；重新构造 repository 才读取落盘值。固定上游也采用相同偏好流结构。

本项与 CB07 不重复：CB07 是字段注册时冻结设备能力；本项在能力正常且写入成功时也存在。待验收：不重启进程，CLI 开关应同时更新页面及服务实际样式，撤销同样即时生效。

## BG03 / P2 — 增强后台执行字段没有执行作用

[ConfigBuiltins.kt:431](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBuiltins.kt:431) 声称 `background.enhanced` 控制后台继续运行，写入 `enhanced_background_execution`。对 `src/android/app/src/main/java` 搜索该键仅有注册写入，没有生产读取；后台页面也没有对应状态。[AgentForegroundServicePolicy.kt:16](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/AgentForegroundServicePolicy.kt:16) 实际以 active session 数决定运行，与该字段无关。

这是一个暴露给 Agent 的无效行为开关：开启不能增加保活，关闭不能阻止后台执行。不能据此断言当前 FGS 一定无法后台运行；已确认的是工具描述与真实控制边界不一致。固定上游保留相同字段。

修复方向：按产品真实语义移除无消费者字段，或接入确有需要的现有执行策略；不要为了让旧开关看起来有效而另加常驻服务。待验收：字段每个宣称的状态都对应可观察行为，或明确返回不支持。

## BG04 / P2 — Live Updates 降级漏掉通知刷新

与 BG02 分开：[后台页:211](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/BackgroundSettingsScreen.kt:211) 已通过 repository 正确写入并更新流。[AgentForegroundService.kt:451](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/AgentForegroundService.kt:451) 在 Live Updates 为 active 时调用 `refreshOngoingNotification()`；关闭后进入普通浮窗分支，该分支没有对称的通知刷新。其后续刷新依赖另一次服务启动/工具状态更新。

触发顺序：promoted 通知已显示；Agent 正等待 Provider；用户在后台设置关闭 Live Updates。浮窗路径采用新开关，既有系统通知却仍是旧 promoted 样式，直至后续工具等更新。源码没有周期刷新器，故不能把它描述成最多一秒的延迟；实际 OEM 展示及持续时长待设备验证。

修复方向：样式选择发生两方向变化时，都重建已存在的 FGS 通知。待验收：关闭即降级，重新开启即升级，长等待期间也保持一致。

## BG05 / P2 — 工具展示与会话目标并非同一条状态

[SessionActivityTracker.kt:298](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/SessionActivityTracker.kt:298) 在每个新 active session 上覆盖 `_currentSessionId`；[第 511 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/SessionActivityTracker.kt:511) 更新工具没有 session 或 tool-call ID；[第 532 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/SessionActivityTracker.kt:532) 完成时也不检查拥有者，直接清空全局工具状态。真实调用见 [ChatViewModel.kt:9293](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9293) 与第 9539 行。

不需要同一线程上的同时写入也能触发：A active→B active→A 开始工具 X，则工具名属于 A，currentSessionId 仍为 B；[服务:550](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/AgentForegroundService.kt:550) 将两者一起交给浮窗，[ToolOverlayController.kt:675](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/ToolOverlayController.kt:675) 点击打开 B。若 A 与 B 的工具重叠，A 先完成会把 B 尚在运行的状态清掉，还会将 A 的 outcome 赋给快照中的 B 工具。

固定上游仍保留无 session 参数的展示接口；但其第 787–845 行已经加入 `inFlightTools` 计数及 `clearToolRunning()` 的 `>1` 保护，本地没有这一保护。上游计数也不能单独证明跨会话跳转归属已经正确。

修复方向：复用 session/tool-call 身份维护状态与完成归属，再选择显示对象；不能只补全局布尔或计数。待验收：两个会话交错开始、完成、失败及取消，浮窗文案、点击目标和运行标记须对应同一任务。

## BG06 / P2 — 系统权限和设置等待槽可互相覆盖

[OffloadPermissionManager.kt:217](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt:217) 将每次 Android 请求写入同一个 continuation；取消和超时无条件清空槽。`respondToAndroidPermission()` 第 240 行只收到结果，不能知道结果属于哪次请求。[MainActivity.kt:316](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:316) 将系统回调压成 `allGranted`，丢弃返回的权限名称关联。

同样，[设置请求:300](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt:300) 和 `respondToSettingsGate()` 第 350 行使用单槽；SettingsGateRequest 的 id 是能力名，不是请求实例 ID，回调也未携带它。MainActivity 第 358–390 行对非 null 请求建立独立 AlertDialog，流被清为 null 时不关闭旧 dialog。

触发顺序：A 等待→B 覆盖槽→A 回调把 B 恢复，或 A 超时清空 B；旧设置 dialog 的按钮同样会回应当前 B。已有调用来自 Contacts、Location、Notification 等 guest handler 和直接工具。ASK_ONCE 第 630–643 行已有 Mutex，但它只保护另一条业务授权通道，没有覆盖这两条 Android 通道。固定上游也有相同单槽结构；本地新增 NO_UI、总流程预算并未建立请求归属。

本项确认的是串答、遗失等待者和错误成功/拒绝分类。Android 本身仍检查 OS 权限；AndroidConnectivityOps 还在返回后复核 grants，settings 分支还会轮询 check，不能据此声称任意工具越过 OS 权限。待验收：不同权限并发、A 超时后 B 请求、旧 dialog 返回、Activity 重建时，每个答复和清理只能影响对应请求。

## BG07 / P2 — 完成通知在挂起后没有重新检查状态

[BackgroundTaskNotifier.kt:69](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt:69) 在启动 IO 协程前检查开关和前后台。随后 `getSession()` 挂起，恢复后直接 post；实际 post 第 91–100 行仅复核 Android 通知权限。[MinisApp.kt:1016](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1016) 在 App 回到前台时清理现存完成通知，没有取消未完成的通知查询协程。

所以“后台任务结束→查询尚未完成→回到前台并清理→查询恢复”会新发出一条前台完成通知。等待期间通过 UI 关闭任务通知也不能阻止这次 post。本项与 BG01 的键名错位无关，直接通过正确 setter 关闭也有窗口。

固定上游保留了查询前检查，没有在本次核对片段中建立取消代际。修复方向：真正提交通知时复核前台状态、开关及所属完成事件；前台清理应使旧待发事件失效。待验收：延迟数据库查询，穿插前台切换、关闭通知或同会话重新运行，旧事件不得迟到发出。

## BG08 / P2 — 冷启动 Intent 被当成重建时的新用户动作

[MainActivity.kt:742](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:742) 处理新 Intent 后不调用 `setIntent()`；第 509–515 行每次 onCreate 解析 `Activity.intent.data`，只要可识别就优先于保存的 session。Android 原生 `onNewIntent()` 不自动更新 getIntent，这是 [AOSP Activity 合同](https://android.googlesource.com/platform/frameworks/base/+/1ff961dd6d51247e82e41de052f04fd0b577f09b/core/java/android/app/Activity.java) 的明确行为。

触发顺序：冷启动链接打开 A；用户导航或暖链接打开 B；locale/UI mode 等导致 Activity 重建；保存状态含 B，但旧冷启动链接 A 优先，[AppNavigation.kt:296](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:296) 再次导航 A。Manifest 第 200 行接管 orientation/screen 变化，所以不把普通横竖屏旋转当成已证实触发；它未接管 locale/uiMode。

仅 setIntent 仍不足：用户从最新链接再手动导航后，该链接也会成为旧动作，需要区分已消费链接与本次新 Intent。固定上游有相同优先级和漏更新结构。待验收：A→B→重建保持 B，新到链接 C 则仍优先打开 C；一次性 camera/voice 动作不能因重建重放。

## BG09 / P2 — 外观设置显示旧选择

[AppearanceScreen.kt:202](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/AppearanceScreen.kt:202) 的主题、强调色、UI 样式、字体等只通过 remember 读取一次，没有 SharedPreferences listener。[MainActivity.kt:525](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:525) 根界面却有监听器。

页面保持挂载时，后台 Agent 经 minis-config 改主题或字号，根 UI 已变，设置页仍勾选旧主题/显示旧字号；重组不会重新初始化 remember。字体是否“已修改”第 211 行也会沿用旧快照。本项不重复 CB06 的语言未应用问题：这里即使实际效果已更新，控制页面仍显示旧值。

修复方向：使用已有偏好观察方式使设置页与应用消费者一致。待验收：页面打开期间修改与撤销配置，勾选、滑块及重置入口跟随实际值。

## BG10 / P2 — Launcher alias 半途失败没有恢复

[AppIconRepository.kt:63](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/AppIconRepository.kt:63) 按 Auto、Light、Dark 顺序逐个切换 alias，全部成功才保存偏好；任何一次失败仅记录日志并返回 false，没有恢复已经切换的 alias。[AppearanceScreen.kt:438](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/AppearanceScreen.kt:438) 先改变 selectedAppIcon，忽略 apply 的返回值。

条件性失败场景：当前 Auto，切到 Dark；Auto disable 成功，随后系统调用失败，可能留下无启用 Launcher alias 的状态，而 UI 仍勾选 Dark、偏好仍是 Auto。再次点勾选的 Dark 不会重试；想恢复 Auto 时，repository 又以“偏好已是 Auto”直接返回。本项未验证任何 OEM 一定触发异常，只确认异常路径没有恢复且反馈不真实。

修复方向：保留可恢复的 alias 状态并处理失败，UI 以真实结果更新选择；优先平台支持的批量能力，兼容路径也须失败恢复。待验收：中途失败后至少一个有效入口保留，页面正确反馈并可重试。

## BG11 / P2 — 屏幕变化发生在浮窗隐藏期，恢复位置可在屏外

[ToolOverlayController.kt:254](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/ToolOverlayController.kt:254) attach 只检查持久化 x/y 非负，随后原样赋给 WindowManager。第 647–660 行拖动保存绝对像素；第 751–754 行屏幕变化的校正先要求 view 非 null，隐藏状态不校正偏好。

触发顺序：宽屏/横屏保存右侧位置；浮窗在 App 前台被隐藏；隐藏时切到较窄屏幕；后台任务继续、重新 attach。保存的正 x 可能超过新屏幕宽度，下一次 configuration change 前没有 clamp，导致浮窗无法看到或点击。固定上游同样只在有 attached view 时 clamp。

修复方向：每次 attach 根据当前尺寸校正位置，复用已有 clamp 算法。待验收：可见与隐藏两种状态下分别跨横竖屏/分屏变化，恢复后的浮窗完整落在可用屏幕内。

## BG12 / P2 — 子代理完成也发送用户级通知

[AppSubAgentPort.kt:63](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt:63) 创建 child 并标记 `SOURCE_SUB_AGENT`，执行同一 AgentRunner。SessionActivityTracker 第 383–384 行对每个曾 active 的 session 发 completion listener；[BackgroundTaskNotifier.kt:75](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/notification/BackgroundTaskNotifier.kt:75) 虽已读 session，却不看 source，随后为每个 child post 通知。

父代理后台委派多个 child 时，会出现每个内部步骤各一条“任务完成”，用户任务本身仍可能运行，点击还落到内部 child 会话。这里不是把所有独立 Bot 会话都判成不该通知；已确认的是显式 SOURCE_SUB_AGENT 的内部步骤未过滤。

固定上游 BackgroundTaskNotifier 第 97–104 行已经通过 `session?.isChild == true` 过滤；本地实体没有这个扩展属性，但已有 source 可判断，不必增加新通知框架。待验收：后台父任务委派多 child，只在应通知的用户任务终态发通知；正常独立任务通知保留。

## BG13 / P2 — 系统栏深浅选择与 App 强制主题分离

[MainActivity.kt:541](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:541) 在用户选择浅色时明确 `darkTheme=false`；第 563 行却调用没有自定义 detectDarkMode 的 `SystemBarStyle.auto(TRANSPARENT, TRANSPARENT)`。默认检测根据 Resources 的 UI_MODE_NIGHT，透明 scrim 不决定图标颜色；参见 [AndroidX API 合同](https://developer.android.com/reference/androidx/activity/SystemBarStyle) 与 [AndroidX 实现](https://android.googlesource.com/platform/frameworks/support/+/641888d2c2a363a7fff50419366455793fda8fe4/activity/activity/src/main/java/androidx/activity/EdgeToEdge.kt)。本机缓存 1.10.1 sources 的 auto 第 139–145 行也读取 uiMode，第 291–292 行按检测值设置两种系统栏图标。这里只读取依赖源码，未解析或构建当前 Release 的最终依赖图。

所以系统处于深色、App 强制浅色时，普通页面浅色背景上系统栏仍可使用浅色图标，降低可读性；有自身系统栏覆盖的特殊预览页面不代表根界面已正确。固定 OpenMinis MainActivity 第 538–554 行采用相同写法。

修复方向：系统栏选择使用已确定的 App darkTheme，保持实际图标与页面背景一致。待验收：系统深/浅 × App 自动/强制深/强制浅，分别保存状态栏、手势/三键导航截图与实际图标状态；未做设备截图验证。

## 保留的正确边界与阅读进度

AgentForegroundServicePolicy 以 active work 控制 FGS，presence 本身不保活，并使用 START_NOT_STICKY；对应已有测试源码已读，没有执行。系统权限无 UI 时立即 NO_UI、普通业务 ASK_ONCE 的串行等待、guest 的 App UID/capability 边界仍应保留。AppIconRepository 的 ComponentName 使用实际 Context 包名和 Kotlin alias 类名，不能仅因类名是 com.openminis.app 就判 applicationId 错误。

本轮新增全文阅读 **17 个文件、6,107 行**：MainActivity、AppearanceScreen、BackgroundSettingsScreen、BackgroundSettingsRepository、LocaleAwareCase、AppIconRepository、PowerOptimizationManager、SessionActivityTracker、DeepLinkCoordinator、DeepLinkHandler、BackgroundTaskNotifier、OffloadPermissionManager、AgentForegroundService、ToolOverlayController、AgentForegroundServicePolicy、其已有测试及 ChatSessionEntity。逐个 SHA-256 和范围见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。AppNavigation、MinisApp、ChatViewModel 及本轮相关 handler 只读了调用片段，不提升为全文覆盖。

累计全文阅读 **346/1546 个纳入文件、75,565 行**。尚有 1200 个文件未完成可核验全文阅读；全库目标仍未完成。全部旧报告和 tracked 文件哈希须在交付前保持原值。静态阅读确认代码关系，不代替 Android/OEM、通知调度、实际权限弹窗和失败注入的设备证据。
