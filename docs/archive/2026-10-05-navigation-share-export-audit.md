# 导航、系统分享、文件浏览与会话导出静态审计

日期：2026-10-05。独立报告，不合并或改写此前报告。新增记录 12 项；另复核两项历史队列问题，后两项不计入新增数量。

本地源码固定为 `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 对照固定为 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读取 `/tmp/openminis-review-20261004` 的 Git blob，没有拉取、切换或执行上游。以下结论是静态调用链证据；没有运行测试、构建、模拟器、adb、Root、设备或云任务，没有修改产品代码。

## 新增记录

| 编号 | 优先级 | 问题 |
|---|---|---|
| SH01 | P1 | 消费一个分享时清空暂存目录，能删掉另一个正在交接的文件 |
| SH02 | P2 | `text/plain` 的单文件分享只读 EXTRA_TEXT，忽略 EXTRA_STREAM |
| SH03 | P2 | 内存分享合并没有数量或累计字节上限，长文本落盘也绕过预算 |
| SH04 | P2 | 附件导入失败仍确认消费并删除原件，没有保留失败项 |
| NV01 | P2 | 冷启动一次性导航被 RESUMED 守卫丢弃后没有重试 |
| NV02 | P2 | 暖启动分享在首页或设置页没有交接到聊天的路径 |
| NV03 | P1 | 快捷启动的草稿 ID 在重组时变化，导致导航图重置 |
| NV04 | P2 | 抽屉／分栏实际会话与外层 route 脱节，保活与保存状态取错 ID |
| FB01 | P1 | 文件浏览器旧目录加载可以覆盖新目录列表，操作对象与面包屑不一致 |
| EX01 | P2 | 同一会话导出复用 ZIP 路径，已分享的 URI 会指向后一次导出 |
| EX02 | P2 | 导出附件统计仍识别旧 schema，Markdown 又把全部附件称为图片 |
| EX03 | P3 | Markdown 导出的元数据仍声明为 text |

P1 表示可能影响数据交接、导航状态或文件操作对象，需优先处理；没有将这些静态缺口描述成已实测利用或已发生的数据事故。

### SH01：分享清理跨越了本次交接的所有权

[ShareReceiverActivity.kt:57](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt:57) 在 IO dispatcher 复制输入；[同文件:308](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt:308) 先在共用 `filesDir/share_extension` 建立目标文件并写入，复制结束之后才保存 PendingShare。[ChatScreen.kt:622](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:622) 消费 A 后调用无 keep 参数的清理；[SharedShareStore.kt:111](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/SharedShareStore.kt:111) 删除目录内所有文件。

可达次序：A 已缓冲；B 的 IO 复制已创建文件但未交接；A 的主线程消费者完成并清空目录；B 完成后保存的文件名已经没有对应路径。B 随后导入时返回 null。另一个窗口是 B 已暂存但还在 Provider JSON 导入确认框中。过期消费 [ShareCoordinator.kt:117](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareCoordinator.kt:117) 也清空整个目录。只保护已缓冲的文件不能保护生产中的文件。

固定上游同样有目录级清理，但 Receiver 没有本地新增的 IO 分发；不能声称上游已验证存在完全相同的 IO／主线程交错。修复方向：清理只针对本次已确认处理的文件，生产中和其它 pending 文件须保留。待设备 E2E：用可延迟读取的 ContentProvider 交错两次分享，保存两份内容摘要、交接记录与接收结果，验证两份文件都能到达。

### SH02：按 MIME 分支误把文本文件等同于内联文本

[ShareReceiverActivity.kt:247](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt:247) 遇到 `text/plain` 只读取 EXTRA_TEXT；缺失或为空便直接 return。EXTRA_STREAM 的 URI 读取在 else 分支，文本文件不会进入。Manifest 注册了任意 MIME 的 ACTION_SEND，因此带 `text/plain`、EXTRA_STREAM 而没有 EXTRA_TEXT 的单文件 Intent 会匹配应用，却得到空分享。ACTION_VIEW 和 SEND_MULTIPLE 走不同分支，不能用它们的成功证明单文件 SEND 正常。

固定上游同样存在这个分支。修复方向：根据实际载荷处理内联文字与 stream，明确同时携带两者时的规则。待 E2E：同一 TXT 分别走 SEND、SEND_MULTIPLE、VIEW，并覆盖只有 EXTRA_TEXT、只有 EXTRA_STREAM 和同时携带两者；保留 Intent 摘要与 composer 中的文件内容。

### SH03：限制止于一批 URI 和一条偏好记录

[ShareReceiverActivity.kt:42](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt:42) 定义 URI 附件 16 MiB／批次 32 MiB；[同文件:253](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt:253) 的长文本直接 writeText，没有使用传入的 ShareCopyBudget。[SharedShareStore.kt:35](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/SharedShareStore.kt:35) 的 50 项上限仅用于偏好记录合并。

[ShareCoordinator.kt:71](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareCoordinator.kt:71) 把每次从偏好读出的内容继续合并进内存 buffer，没有项数或字节总额限制，第 86 行每次合并还会续期。只要聊天尚未消费，每次偏好记录都清空后再接收下一批，51 个不同的小分享即可超过 50 项；多个合法的 URI 批次也能超过 32 MiB。不要把单个 Binder Intent 能携带无限文本当成复现前提，本问题发生在多次交接的累计状态。

固定上游同样缺少内存合并上限，长文本同样直写；其 URI 复制也没有本地的新增预算。修复方向：在最终待消费队列统一计量，并对文本 UTF-8 字节使用相同政策，溢出需要明确反馈与按所有权清理。待 E2E：聊天未挂载期间连续分享 51 项及多个有界附件批次，核对队列、磁盘总量、保留项与提示。

### SH04：附件失败没有确认机制，随后不可重试

[ShareCoordinator.kt:113](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ShareCoordinator.kt:113) 在处理之前清空 buffer；[ChatViewModel.kt:5281](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5281) 对文件不存在或复制异常返回 null。[ChatScreen.kt:617](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:617) 忽略返回值，之后仍标记分享已注入并删除暂存原件。比如 cache 复制因空间不足失败，文件没有加入 composer，原件仍被清理；多个附件中成功项与失败项也没有区分。

这是 SH01 之外的独立失败处理缺口：即便没有另一个分享参与，正常单次复制失败也会丢失重试材料。固定上游消费者同样忽略导入结果并全量清理。修复方向：确认成功项后才删除原件，失败项保留并提供可见错误／重试。待 E2E：一批两项，控制其中一项的复制失败；保存 composer、失败项原件与重试后的内容摘要。

### NV01：用户点击守卫被用于一次性启动事件

[SafeNavigate.kt:35](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/SafeNavigate.kt:35) 在当前 entry 未达 RESUMED 时直接 return，没有排队。[AppNavigation.kt:291](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:291) 的初始 deep link effect 用它打开终端、会话、环境变量和设置；effect 只随 initialDeepLink 变化，生命周期后来到 RESUMED 不会重跑。

同文件第 351 行起的普通 launch-session resolver 已显式等待生命周期，并解释初始 graph 挂载时可处于 STARTED；但第 363 行有 deep link 就跳过这个 resolver，未给启动链接提供补偿。结果是命中该时序时启动请求永久丢弃。这个结论是静态条件链，不宣称每次冷启动都会失败。

固定上游有同样的初始 deep link／守卫结构。修复方向：一次性外部事件等待可导航状态后消费；点击防重复机制不能把事件静默丢弃。待 E2E：从未运行的进程打开设置、终端、已存在会话等链接，保存 entry 生命周期与最终 route，覆盖首次布局尚未 RESUMED 的窗口。

### NV02：暖分享只补偿 SESSION_LIST

[AppNavigation.kt:447](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:447) 的 bufferVersion effect 只在外层 route 为 SESSION_LIST 时进入 fresh chat。暖启动时应用稳定停留在 ASSISTANT_HOME 或设置页，ChatScreen 已不在 composition；新的 buffer 既没有消费者，也不会通过这段逻辑导航到聊天。冷启动 resolver 对 pending share 的特殊处理不能补偿已经运行的应用。

用户可先暖分享到设置页，再等待超过 5 分钟后进入聊天，内容在消费时过期；期间入口没有把用户带到可处理的页面。固定上游相同补偿只覆盖 SESSION_LIST。修复方向：按当前可见聊天和明确的分享目标交接，其它页面应进入可消费的聊天，避免让旧的后台页面抢消费。待 E2E：首页、设置、文件浏览、当前聊天四个稳定起点分别接收暖分享，核对目标会话和内容，不自动发送。

### NV03：普通重组重播快捷启动，并重新设置 graph

[AppNavigation.kt:468](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:468) 在 composable 函数体内每次计算快捷启动路由；NewChat、NewVoiceChat、NewCameraChat 都重新生成 UUID，语音／相机还重新写入 pending action。它们没有 remember 或一次性消费边界。该 composable 第 446 行订阅 bufferVersion，因此同一 Activity 内收到新分享就能触发重组，不需要 Activity 重建。

项目使用 Navigation 2.8.5。已只读核对官方 [navigation-compose 2.8.5 源码包](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-compose/2.8.5/navigation-compose-2.8.5-sources.jar)：NavHost 以 startDestination 为 remember key，并把结果重新赋给 controller.graph；[navigation-common 源码包](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-common/2.8.5/navigation-common-2.8.5-sources.jar) 的 NavGraph.equals 比较 startDestinationId；[navigation-runtime 源码包](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-runtime/2.8.5/navigation-runtime-2.8.5-sources.jar) 的 setGraph 在 graph 不相等时清除旧 graph 的 back stack。UUID 改变会改变启动路由 ID，因此不是只创建一个未使用的字符串，而是会回到另一个 fresh draft，并可能再次启动语音／相机。

HTML 快捷方式的 pending preview 同样在函数体里重播，但其固定路由没有上述 UUID 重置链，需分别处理。固定上游有同样的快捷启动代码。此条独立于此前 BG08 的旧 Intent 在 Activity 重建时重播。修复方向：启动目标稳定化，一次性 action 只消费一次。待 E2E：快捷启动后输入草稿／切换页面，再触发 bufferVersion 变化，记录 session ID、back stack、草稿和 action 调用次数。

### NV04：可见会话没有同步给 Activity 的导航观察者

[ChatSplitScaffold.kt:538](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/ChatSplitScaffold.kt:538) 的分栏选择仅修改 selectedSessionId 并调用 pane navigator；[同文件:1019](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/ChatSplitScaffold.kt:1019) 的手机抽屉仅修改本地 currentSessionId。实际 ChatScreen 可以已经显示 B，外层仍为 chat/A，甚至仍为 SESSION_LIST。

[MainActivity.kt:587](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:587) 只观察外层 back stack，据此 setPresent／setAbsent；第 642 行又保存同一 ID。这会对 A 而非 B 记录 presence；SESSION_LIST 内显示聊天时则没有 presence。没有 active stream 的情况下，保活条件不能表示实际可见聊天。手机重建还会在 [ChatSplitScaffold.kt:974](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/ChatSplitScaffold.kt:974) 的 effect 重新用 initialSessionId 覆盖已保存的内部选择。不能保证 rememberSaveable 会保住 B。

固定上游分栏也使用内部 navigator，MainActivity 同样只看外层 route；本地新增手机抽屉又扩展了问题范围。此条与 BG08 不同，即使没有旧冷启动 Intent，外层路由和实际选择也已不一致。修复方向：让 Activity 的 presence 和恢复数据来自实际可见会话；恢复时不要再以过期初始参数覆盖内部选择。待 E2E：外层 chat/A 内切换 B，以及 SESSION_LIST 内选 B，核对 presence 集合、Home 后服务状态、保存状态与重建结果。

### FB01：目录请求没有失效边界

[FileBrowserViewModel.kt:442](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt:442) 与 [同文件:483](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt:483) 每次独立启动 IO job，没有取消前一次或在提交时核对请求目录。loadGuestItems 捕获旧 directory，完成后直接写 rawItems 与当前 uiState；新目录已在 navigate／goBack 时更新了面包屑和 currentPath。

可达次序：进入慢目录 A，立即通过 [FileBrowserScreen.kt:139](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserScreen.kt:139) 的面包屑返回 B；B 先完成；A 后完成并覆盖列表，页面显示 B 的路径和 A 的文件。loading spinner 只替换内容区，面包屑和返回入口仍可用。旧 FileItem 保留 A 的实际路径，[FileBrowserViewModel.kt:365](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sandbox/FileBrowserViewModel.kt:365) 的删除等操作据此执行，因此同名文件可能使用户在 B 的面包屑下操作 A 的文件。没有认定用户已实际误删。

固定上游 host 目录加载同样无失效检查；本地 guest 请求新增同类窗口。修复方向：结果、错误和 loading 状态都只允许当前请求提交，且列表与路径是同一读取状态。待 E2E：控制 A 慢、B 快且有同名不同内容文件，保存请求顺序、路径和行对象，确认迟到结果不会改变 B 的列表及操作对象。

### EX01：已发出的内容 URI 没有独立导出产物

[ChatExporter.kt:129](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt:129) 最终 ZIP 名只包含标题和 session ID 前 8 位；同一会话再次导出先删除旧文件，再在同一路径写新内容。[SessionListScreen.kt:3019](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/sessions/SessionListScreen.kt:3019) 把这个路径对应的 FileProvider URI 发给接收方。临时 workDir 虽有 UUID，但最终分享路径没有。

接收方晚读第一次分享时，URI 可能已经对应第二次导出的更新内容或另一种格式；无需假设两个 session 的短 ID 碰撞。并发导出同一会话还可能同时写同一个文件。固定上游也复用该 ZIP 路径。修复方向：每次导出拥有独立的最终文件，在完整写好后交接，清理不能覆盖仍可读取的旧产物。待 E2E：接收方延迟读取，先导出 JSON 再导出 Markdown，分别校验两份 URI 的格式和摘要；另覆盖同时导出。

### EX02：当前 mediaRef 与旧附件统计不一致

[ContentPart.kt:42](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ContentPart.kt:42) 和 [ChatViewModel.kt:11036](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:11036) 定义、持久化的附件类型为 `mediaRef`，MIME 在 value.mimeType。[ChatExporter.kt:410](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt:410) 却只统计 image/image_url/video/video_url，普通当前附件会在 Summary 和 session.json 中得到 0。

本地 Markdown 分支第 240 行认识 mediaRef，但把所有 mediaRef 数量传给 [ConversationMarkdownExporter.kt:40](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ConversationMarkdownExporter.kt:40) 的 imagesFormat；PDF、音频和视频也被称为 image(s)。这里没有声称导出会携带附件原始字节，问题是缺失附件的数量／种类说明不可信。

固定上游已有旧 schema 统计错误，但没有本地 Markdown 格式及其全量附件图片标签。修复方向：按现役持久化 schema 和 MIME 分类，通用缺失附件说明不应冒充图片计数。待 E2E：同一会话准备图片、视频和 PDF，导出各格式，核对元数据与文字说明，保存原始 parts JSON 对照。

### EX03：新格式没有贯穿元数据

[ChatExporter.kt:96](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt:96) 识别 markdown 并写 .md，但 [同文件:301](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt:301) 的 Summary.format 仍只有 json／text；第 339 行原样写进 session.json。Markdown ZIP 内部声明因此与实际 transcript 格式不同。

固定上游只支持 JSON／text，原二选一在该版本中没有这个新增分支缺口。修复方向：格式判定贯穿文件扩展名、Summary 与 metadata，未知值按明确规则处理。待 E2E：从原生导出入口分别选择 JSON、text、Markdown，保存 ZIP 目录与 session.json，验证格式一致。

## 历史问题的现役复核，不计入新增数量

**HK01：文件浏览／预览 Holder 在进程恢复时丢失。** 历史队列 #229 已在 [RUNTIME-HISTORY.md:22](/home/jiale/projects/minis-for-android/docs/archive/RUNTIME-HISTORY.md:22) 登记。当前 [AppNavigation.kt:249](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:249) 仍仅在静态对象持有 FileItem／ViewModel；第 1144、1208 行遇到 null 直接返回，没有路由参数重建或恢复失败页面。进程死亡后恢复这些 destination 时，其输入不会随 NavController 保存状态恢复。相同 Activity 的重建未必清空静态对象，不能把普通重建当作充分复现条件。固定上游相同。待 E2E 应真实区分进程重建与 Activity 重建，并保存恢复栈和页面截图。

**HK02：解码重复的具体入口。** 历史队列 #183 曾列编码／加号问题；本轮进一步确认内部 Navigation 路径，而不把历史描述直接当成当前事实。[AppNavigation.kt:160](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:160) 已对技能相对路径编码，但第 1282 行对 back-stack argument 再 URLDecoder.decode。已核对官方 [navigation-common 2.8.5 源码包](https://dl.google.com/dl/android/maven2/androidx/navigation/navigation-common/2.8.5/navigation-common-2.8.5-sources.jar)，NavDeepLink 第 250 行已经 Uri.decode 后才填 argument；`C++指南.md` 进入页面后再解码变为 `C  指南.md`，`100%.md` 会在未捕获的 URLDecoder 中触发非法转义异常。模型详情第 943、945 行的再次 Uri.decode 也会把字面量 `%2F` 改为 `/`。固定上游这两个消费端同样二次解码。待 E2E：技能文件名包含 `+`、`%`、空格及子目录，模型 ID 含字面量 `%2F`，核对选择、读取和实际 ID 保持一致。

此前 F40 的导出缺少快照也扩展到原生 [ChatExporter.kt:173](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt:173) 的分页循环；本轮只作为调用方复核，不重新计数。Markdown 有 400 字工具字段上限且明确标记 truncated，不能把此前 CLI F39 的“200 字且不标截断”原样套在这个格式上。

## 阅读与复核记录

本轮新增 17 个全文文件、5,183 行；累计全文 363 / 1,546 个纳入范围的文件、80,748 行。只搜索或读取片段的大文件没有冒充全文。完整清单和源码 SHA-256 在 [覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。全文文件如下，包含现有测试源码的阅读，不表示执行测试：

- share：PendingShare、ShareCoordinator、ShareHandoffPolicy、ShareReceiverActivity、SharedShareStore、ConversationMarkdownExporter、ChatExporter。
- navigation：AppNavigation、SafeNavigate、StartupLaunchResolver、ChatSplitScaffold。
- 文件／消息：FileBrowserViewModel、ContentPart。
- 现有测试：ShareHandoffPolicyTest、ConversationMarkdownExporterTest、AppNavigationStartupTest、DraftSessionIdTest。

Navigation 2.8.5 官方源码包 SHA-256：common `7889777c81b52d940cb63eb498bd00e56dd1560fcde35f5006f25d84971d385b`；compose `d4a9e92c45df405a22935478eda6e96c23db33fa240c221ea863bd3b475f386e`；runtime `78203078f4b66bb6f2d15fbf64a4cdaf8928aeaa02567b1e59c04d791382c053`。仅解包读取源码，没有安装或运行这些依赖。

以上验收场景均为待执行建议；当前没有设备复现工件，也没有声称整个项目已读完或这些问题已经修复。
