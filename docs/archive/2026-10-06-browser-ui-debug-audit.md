# 浏览器设置、历史、下载界面与调试入口审计

日期：2026-10-06。项目 HEAD：d64e7c06d8159be90737dd9ba03dd9bd5855f339。

本轮新增 **8 项静态确认问题（5 项 P2、3 项 P3）**，全文阅读 6 个此前未登记全文的文件，共 1785 行。只新增这份报告、更新阅读台账；产品代码和旧报告不修改。不运行测试、构建、模拟器、adb、设备操作或云任务，不读取凭据或真实 Cookie。

上游对照固定为 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)。本轮只读取本机已有 Git 对象，没有 fetch。下述输入与时序均为代码推导，未作为实测结果。

## BU01 — P2：按域名删除 Cookie 的按钮没有执行删除

[BrowserSettingsSheet.kt:391](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt:391) 的域名垃圾桶调用 `CookieManager.setCookie(domain, "")`，然后 flush。domain 来自历史记录的裸 host；空字符串既不提供 Cookie 名称，也没有 Domain、Path 或过期属性。

[Android CookieManager 合同](https://developer.android.com/reference/android/webkit/CookieManager#setCookie(java.lang.String,%20java.lang.String))规定 setCookie 设置单个名称／值，替换依赖 host、path 和 name；value 应是 Set-Cookie 格式。它不是“清除这个域名下所有 Cookie”的 API，flush 也只是持久化现有状态。因此这段调用无法按按钮承诺清掉该站点的 Cookie，用户可能以为已经退出登录，实际状态仍保留。

应明确可支持的删除范围，按实际 Cookie 身份完成删除并确认结果；不能用空的 setCookie 冒充域级删除。不同路径、域 Cookie、Secure 和 HttpOnly 的后续验证应使用非敏感 fixture，保留删除前后列表和结果。本轮未执行。固定上游 [第 386 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt#L386) 仍有同一调用，不能直接照搬。

## BU02 — P3：Cookie 域名列表实际是浏览历史列表

[BrowserSettingsSheet.kt:349](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt:349) 只用 hasCookies 获取一个全局布尔值，再由 historyStore.uniqueDomains 构造域名列表。[BrowserHistory.kt:87](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserHistory.kt:87) 取历史 Entry.domain，不查对应站点有没有 Cookie。

**可达场景：** 清空历史只执行 [clear:91](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserHistory.kt:91) 的列表清空和文件保存，没有清 Cookie；再次打开设置时，既有 Cookie 仍在，域名条目却消失，只剩“已存储 Cookie”的笼统提示。反过来，无 Cookie 的已访问域名也会带删除按钮出现；子资源／第三方站点不会因设置 Cookie 自动进入导航历史。

`remember(hasCookies)` 又不会随历史内容变化而失效：只要那个布尔值不变，已经打开的设置页可继续显示旧域名列表。应把可枚举的候选站点与确认存在的 Cookie／网站数据分开呈现，避免把历史当成完整库存。固定上游 [第 344 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt#L344) 同样使用历史来源。本项与 BU01 的删除实现错误分别登记。

## BU03 — P2：设置页不反映会话级视口，“默认”按钮也不能恢复默认

[BrowserSettingsSheet.kt:114](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt:114) 仅订阅 customViewportWidth/Height，即全局值；没有订阅池已提供的 sessionViewportWidth/Height。实际 [resolvedViewportSize:1123](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1123) 优先使用会话覆盖。

**触发：** 全局视口未设置，Agent 执行 set_viewport 1920×1080。重新打开设置时，单选仍为默认，宽高输入为空；[第 646 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt:646) 虽读取有效尺寸，接着却将会话覆盖称为“UA default”。点击默认只调用 [第 246 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt:246) 的 setGlobalViewport(0,0)，会话覆盖依然生效；选择预设也只改变更低优先级的全局值。

这是有效状态与 UI 操作层级不一致，不是上一轮 BL09 的尺寸上限问题。应明确展示会话覆盖及其来源，恢复行为应作用于用户当前看到的有效层级。固定上游 [第 98 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt#L98) 与第 240 行仍是同样结构。未执行 Agent→设置交接验证。

## BU04 — P2：全局浏览器设置只更新当前池，存活池之间发生分歧

[ChatViewModel.kt:1130](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1130) 为各 ViewModel 懒创建独立 BrowserTabPool；应用还有另一个共享池。各池在 [BrowserTabPool.kt:200](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:200) 初始化时读取 browser_prefs，之后没有这份偏好的变更监听或全池传播。

[setGlobalViewport:1142](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1142) 持久化全局宽高后只更新自己并重载自己的 tabs。UA 的 [设置选择:194](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt:194) 也只写偏好、修改传入池；[idle timeout:243](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:243) 同样如此。

**允许时序：** A、B 两个池已经存在且 B 没有会话级视口覆盖；在 A 修改全局视口，B 继续使用旧值，新建 C 则读到新值。UA 更明显：B 重新打开设置时 [第 93 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt:93) 从新偏好显示 Desktop，但 B 的 manager 仍用旧 Mobile UA。池的创建时刻决定“全局设置”是否生效。

应复用已有共享状态／偏好监听方式，使全局值传到存活池，同时保留会话覆盖的优先级；设置页应显示实际池状态。固定上游 setGlobalViewport 第 1771–1779 行仍只更新自身，本轮未确认上游所有外围协调，不宣称其整个产品必然同样失效。未执行多池交错。

## BU05 — P2：历史界面没有可观察更新，缓存仅靠数量失效

[BrowserHistorySheet.kt:74](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserHistorySheet.kt:74) 用 searchQuery 和 getEntries().size 作为 remember 键。Store 的 [entries:39](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserHistory.kt:39) 是普通 mutableList，没有 StateFlow 或 Compose 可观察快照；[BrowserUseManager.kt:370](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:370) 在导航完成时记录，也没有给历史界面发更新。

历史界面打开期间后台页完成导航，record 本身不会触发界面更新。即便其它状态让界面重组，只要数量相同仍复用旧分组：例如新增一条访问并剪掉一条七天前的访问，最新条目进不来、被剪掉的条目留在缓存；跨午夜而数量不变时 Today／Yesterday 分组也可过时。

应订阅真实历史变化，缓存包含内容版本和日期依赖，或避免缓存这份不大的派生结果。清空确认按钮会改变 showClearConfirm，数量通常也变成零，因此不能声称手动清空一定无法刷新。固定上游 [第 59 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserHistorySheet.kt#L59) 同样仅以数量缓存。这与旧 BR09 的读取时不剪过期条目不同。未执行后台记录／跨日验证。

## BU06 — P3：下载面板打开后完成的条目仍显示未读

[BrowserDownloadsSheet.kt:84](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserDownloadsSheet.kt:84) 只在 LaunchedEffect(Unit) 首次进入时 markDownloadsSeen；[BrowserTabPool.kt:358](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:358) 只标记当时已经结束的条目。

用户打开面板查看正在下载的 A，A 随后完成且完成状态已显示；settleDownload 没有设置 seen，Effect 也不重跑。关闭面板后，徽标仍按 [第 354 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:354) 把它当未读，必须再次打开面板才能归零。这是顺序操作也会发生的缺口，不是 BL10 的并发读改写覆盖。

应按面板实际可见的终态条目标记已读，避免仅把“曾打开面板”当成完整的阅读生命周期。固定上游 [第 69 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserDownloadsSheet.kt#L69) 仍有同一 Effect。未执行徽标验证。

## BU07 — P3：卡片分组破坏列表逐项懒加载

下载列表 [BrowserDownloadsSheet.kt:126](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserDownloadsSheet.kt:126) 在单个 item 的 Column 内 forEach 全部下载记录。历史列表 [BrowserHistorySheet.kt:165](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserHistorySheet.kt:165) 把同一天的全部记录放进一个 item；搜索结果也被合成一个 Results 分组，因此搜索匹配项集中到一个 item。

[Compose 官方说明](https://developer.android.com/develop/ui/compose/lists#beware-of-putting-multiple-elements-in-one-item)指出同一 lazy item 的子元素作为整体组合、测量。一行进入视口会使其整组参与工作，外层使用 LazyColumn 不能恢复记录级懒加载。下载记录没有自动数量淘汰，历史七天范围也没有条数上限；大量记录时这条开销随整组增长。本轮确认的是失去逐项懒加载，不宣称某台设备已出现卡顿或 OOM。

应将记录作为带稳定键的独立 lazy item，卡片圆角／分隔线可按首尾行绘制。固定上游 [下载第 106 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserDownloadsSheet.kt#L106)、[历史第 149 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserHistorySheet.kt#L149) 均按记录使用 items(..., key={it.id})。本地改变了这一结构。没有执行性能测试。

## BU08 — P2：浏览器调试 RPC 绕过标签页串行锁和使用权记录

[BrowserDebugMethods.kt:130](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/BrowserDebugMethods.kt:130) 的 executeJS 直接调用 tab.manager.execute；pageInfo、getReadable、getText 和 screenshot 也直接进入 manager，绕过 BrowserTabPool.execute／executeSerialized。没有获取池的 Mutex，也没有设置 inUse 或更新时间。

[DebugServer.kt:116](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt:116) 每个连接独立启动处理，[DebugRPCHandler.kt:125](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt:125) 不另行串行化。这些 RPC 与 CLI 共用应用共享池，两个已授权 RPC，或 RPC 与 CLI 动作，可以同时驱动同一页。池原本已有串行边界，调试入口却绕开；直接调用的长动作也不会阻止空闲淘汰移走它。

manager 只有一个 [asyncJsDeferred:167](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:167)，并行调用会争用这份状态。本项登记调试入口没有遵守执行边界，旧 KM01 的迟到回调无身份问题不再另计。应让调试入口复用现役执行池并明确目标 tab。

**范围限制：** [MinisApp.kt:1116](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1116) 只在 Debug 启动服务；[DebugServer.kt:186](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt:186) 在 dispatch 前校验令牌。这里没有发现或尝试绕过鉴权，也不把它描述为 Release 远程漏洞。固定上游 [BrowserDebugMethods.kt:129](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/debug/BrowserDebugMethods.kt#L129) 仍直接调用 manager。未运行 RPC。

## 其它已读证据，不增加问题编号

- 下载完成文件同时写 guest 和 App cache；[BrowserDownloadsSheet.kt:265](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserDownloadsSheet.kt:265) 打开的是 cache 副本。file_provider_paths 第 20 行已声明 browser_downloads 路径，不能误报“下载文件不在 FileProvider 范围”。缓存被系统清理后 exists=false 会静默返回，且没有回 guest 重新准备文件；作为待验证打开体验记录，未扩成新的确定编号。
- [file_provider_paths.xml:39](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/xml/file_provider_paths.xml:39) 仍有整个 files/cache 和 device_root 等兜底根。文件本身不能证明任何未授权调用方已获得这些 URI，不能仅凭宽根范围声称已发生任意文件泄露；具体 URI 构造／授权链仍需独立排查。
- DebugServer.stop 只关 listener、取消登记的 Job，没有保存并关闭 client socket；阻塞读取与旧 MS03 是同一类缺口在调试服务里的调用点，不重复编号。它另在 [第 253 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt:253) 用未传 parent context 的 runBlocking 执行 RPC。[Kotlin API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/run-blocking.html)表明这种新 Job 不继承外层 connection Job。本轮尚未找到产品内 stop 调用方，故停止服务后动作继续的实际 UI 场景留待后续，不作为新的现役确认问题。
- DebugServer 启动失败后 acceptJob 字段保留已完成 Job，start 的非空检查挡住同实例重试；连接 Job 注册与 finally 删除也有旧 MS04 的先后竞态。未发现产品内同实例重试／停止的触发入口，未增加编号。
- 旧 BL11 的固定共享池归属、BL12 的模型分页字段缺失、BL07 的 UI 接管，以及 BR09 的历史过期检查不重计。Debug 的 getText/getReadable 没有传分页参数，是已登记分页契约问题的另一个调用点。

## 后续验证工件（本轮没有执行）

| 范围 | 应保留的可复核工件 |
|---|---|
| Cookie 删除和候选站点 | 非敏感 fixture 的名称／域／路径、删除前后结果、清历史前后的站点列表 |
| 会话与全局设置 | A/B/C 池的创建时刻、有效 UA/viewport/timeout、设置页截图和每次动作结果 |
| 历史刷新 | 记录版本、分组日期、增删前后内容、保持数量不变时的显示结果 |
| 已读徽标 | 面板可见状态、下载终态、seen 字段与关闭后的徽标 |
| 懒加载 | 固定记录集、可见行及实际组合／测量数量；性能证据需 Release 路径 |
| RPC 串行 | 调用 ID、tab ID、锁获取／释放、UI busy 状态、结果归属及淘汰事件 |

## 阅读覆盖与交付复核

| 本轮新增全文文件 | 行数 |
|---|---:|
| [BrowserSettingsSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSettingsSheet.kt) | 690 |
| [BrowserDownloadsSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserDownloadsSheet.kt) | 280 |
| [BrowserHistorySheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserHistorySheet.kt) | 240 |
| [BrowserDebugMethods.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/BrowserDebugMethods.kt) | 180 |
| [DebugServer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugServer.kt) | 349 |
| [file_provider_paths.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/xml/file_provider_paths.xml) | 46 |
| 合计 | 1785 |

DebugRPCHandler 新增 1–140 行片段，MinisApp 新增 1110–1128 行片段；二者仍未全文。BrowserHistory、BrowserTabPool、BrowserUseManager 与 ChatViewModel 已登记全文／片段，本轮针对相关调用复核，不重复增加全文数。

累计全文 **740/1546 个纳入文件、156554 行**；13 个部分阅读、793 个未登记。目标尚未完成，台账：[2026-10-05-code-review-coverage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

交付前复核 1670 个 tracked 路径及 SHA-256、固定 HEAD、tracked/staged diff、此前 47 份报告原哈希，以及本报告的本地证据文件／行号和固定上游引用。静态证据不能替代设备行为、实际并发频率或性能测量。
