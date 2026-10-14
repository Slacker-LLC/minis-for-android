# 浏览器执行、生命周期、会话归属与模型契约审计

日期：2026-10-06。项目 HEAD：d64e7c06d8159be90737dd9ba03dd9bd5855f339。

本轮新增 **16 项静态确认的问题（15 项 P2、1 项 P3）**，全文读完 BrowserUseManager、BrowserTabPool、BrowserSheet 和 ChatViewModelUiStateExt，合计 4168 行。只新增本报告并更新覆盖台账；不改产品代码和旧报告，不运行测试、构建、模拟器、设备操作或云任务。

下述时序、输入和内存数字为源码推导，未复现或测量。对照固定为 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)，只读本机已有 Git 对象，没有 fetch。一般备注不作为已修复证据。

## BL01 — P2：导航错误和超时仍返回成功

[BrowserUseManager.kt:389](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:389) 收到主页面错误时只记录日志，然后完成 navigationDeferred<Unit>；[1349 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1349) 的导航超时也完成同一个 Unit，没有返回错误状态。

[navigate:678](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:678) 随后无条件构造默认 success=true 的结果。go_back、go_forward、reload 也复用这套等待。失败页面可能被称为“已导航／已重新加载”，Agent 的后续操作无法据此区分失败与正常完成。旧 URL 非空时，第 675 行的 ifEmpty 也不会将它换成请求 URL。

应保留完成、失败、超时的不同结果，并把实际目标、错误和可重试性交给调用方。需要后续验证主框架网络错误、慢页面超时以及此前已有旧页面的场景；本轮未执行。

## BL02 — P2：“等待标签页锁”预算覆盖了整个动作

[BrowserTabPool.kt:637](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:637) 将 mutex.withLock 与 runAcquiredAction 一起包进 60 秒 withTimeoutOrNull。超时一概返回 tab busy，哪怕锁已经获取，页面动作已经开始。[协程 API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout-or-null.html) 对整个 block 计时，无法按代码备注自动区分锁与动作。

**允许时序：** 等锁 45 秒后获得锁，执行正常需要 25 秒的导航；获得锁 15 秒后外层就取消它并报告标签页繁忙，导航自己的 30 秒预算尚未耗尽。另一种场景是动作已产生副作用，但未能在剩余时间内返回，模型被引导重试。

应把锁获取等待与动作期限分开，并保留动作已启动的事实。固定上游 [第 786 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt#L786) 已用 BrowserActionGuard.lockWithin 单独获取锁，之后才运行动作。本轮未执行交错验证。

## BL03 — P2：三个标签页全忙时，等待结束后强行复用忙页

[acquireTab:792](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:792) 找不到空闲页且无法新建时轮询 20 秒，最终 [804 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:804) 无条件取列表首项，即使它仍 inUse=true。

**触发：** 三个页各有尚未完成、超过 20 秒的动作，第四个无 tab_id 的 navigate 或 fetch 进入这个回退。该调用来自 [executeUnbounded:619](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:619) 的非串行分支，不获取被复用页的 Mutex，两个调用便能同时驱动同一 manager。此前操作可能被新导航打断，共享 Deferred 和页面状态也会互相影响。

超出等待预算应返回可解释的忙碌结果，或通过同一标签页锁获取明确使用权，不能以等待过一段时间当成获得使用权。实际发生频率未测量。

## BL04 — P2：用户已打开真实页面，首次 Agent 操作却加载空白页

[ensureTabForUI:1055](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1055) 通过 createTab 新建页，却不消费 needsInitialBlankPage 标记。[createTab:860](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:860) 在没有初始 URL 时置该标记为 true。

用户随后从 [BrowserSheet.kt:201](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSheet.kt:201) 的地址栏调用 manager.loadURL。loadURL 和 onPageFinished 都不清这个 Tab 标记。下一次 Agent get_text、click 或 screenshot 获取同一页时，[acquireTab:821](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:821) 仍调用 loadBlankPage，覆盖用户刚打开的页面。

打开聊天浏览器的 [ChatViewModelUiStateExt.kt:91](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelUiStateExt.kt:91) 正是这个入口。应在真实页面加载后使待初始化标记失效，或让 UI 创建路径完成初始化。固定上游 ensureTabForUI 第 1504 行同样没有消费标记，不能据此认为已经解决。未运行 UI→Agent 交接验证。

## BL05 — P2：清空聊天后仍保留旧浏览器页和运行状态

[ChatViewModel.kt:5248](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5248) 在 clearChat 中调用 releaseAllTabs，随后删除持久化 JSON。实际 [BrowserTabPool.kt:1090](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1090) 只复制 Tab 并设置 inUse=false，没有清空列表、savedURLs、页面或后台下载。

同一个 ViewModel 的 lazy pool 继续存在，新空聊天打开浏览器、list_tabs 或 get_text 仍能拿到原页面；后续 saveState 又可重新创建刚删除的 snapshot。这是清空行为的可达缺口，与旧 F01 的“删除会话持有者收尾不一致”触发和具体实现不同。

应明确区分交回用户与清除会话浏览器状态。固定上游 [destroyAllTabs:1576](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt#L1576) 清空列表、释放各页并清 savedURLs；本轮只确认其函数实现，未把它所有调用方都视为已核实。清空后再进入浏览器及持久化验证未执行。

## BL06 — P2：关闭和空闲淘汰仅移除列表，没有销毁 WebView

Agent close_tab 的 [899 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:899)、UI 关闭的 [999 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:999)、window.close 的 [962 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:962) 和 [evictIdleTabs:1097](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1097) 都只是删 Tab。本文件没有 WebView.destroy、释放原生资源或删除对应锁条目的路径。

三页上限只约束列表长度，不能证明此前关闭的页面资源已释放。BrowserWebView 包装器从父 View 移除页面，是为池复用服务，并不承担页的最终销毁。[Android destroy 合同](https://developer.android.com/reference/android/webkit/WebView#destroy()) 要求从 View 系统移除后销毁内部状态。这里只确认缺少主动释放，不宣称每个实例永远无法回收，也没有测量泄漏大小。

固定上游 [closeTab:1308](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt#L1308) 和淘汰第 1604 行调用 releaseTabResources；[该 helper:1662](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt#L1662) 包含解除父子关系和 destroy。window.close 的回调重入需要延迟 teardown，不能照搬同步销毁。后续应核对创建／销毁计数和关闭后的回调，未执行。

## BL07 — P2：“接管”只解除 UI 防护，原 Agent 动作继续执行

[BrowserSheet.kt:252](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSheet.kt:252) 的接管按钮只调用 releaseAllTabs。池没有取消当前动作 Job、等待动作停下、结束页面 JS 或建立新的控制权；它立即让 inUse=false，地址栏和触摸遮罩随后可以解除。

**触发：** Agent 已发出异步 JS，用户点接管并导航／填写表单，原异步 JS 仍可继续修改页面。原动作持有旧 Tab，新的无 ID navigate 也可能看到“空闲”页并驱动同一个 WebView。copy 标志不等于停止执行。

应在承诺用户已接管前完成控制权交接，并明确无法停止的页面工作。本轮没有将“销毁用户正在查看的 WebView”当作接管方案。另有桌面站点菜单 [BrowserSheet.kt:432](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSheet.kt:432) 在 busy 时仍可切 UA、重载，说明 UI 防护也存在绕开的入口，归入同一控制权问题。

## BL08 — P2：全页截图的 finally 恢复步骤仍可被取消

[BrowserUseManager.kt:777](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:777) 在 finally 中切到 Main 恢复 viewport，但沿用已经取消的 Job，没有不可取消的清理区域。[Kotlin 清理合同](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-non-cancellable/) 明确区分进入 finally 与保证挂起清理完成。

**允许时序：** viewport 已拉伸，外层工具被取消或超时；恢复用的 withContext(Main) 在进入时抛取消异常，原宽高没有写回，lastAppliedViewport 也保留拉伸尺寸。此外，首次拉伸发生在 try/finally 外，第 767 行切回时的及时取消也可能在 didStretch 设置前中断。

应把修改和恢复纳入同一资源生命周期，并保证恢复在取消时完成。此路径可从 CLI 的 JSON full_page 参数到达；模型 schema 缺字段的问题另见 BL12。固定上游截图第 961 行仍用同样的可取消 finally；其新增 repaint delay 不能证明本问题已修复。未执行取消交错。

## BL09 — P2：自定义 viewport 无尺寸和像素预算，截图按全尺寸分配

[handleSetViewport:1213](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1213) 仅要求宽高为正；setSessionViewport、setGlobalViewport 和恢复设置也没有上限。[applyViewport:1247](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1247) 乘设备 density 后布局，[captureWebViewBitmap:891](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:891) 按宽高直接创建 ARGB_8888 位图。

**大小推导：** 10000×10000 CSS viewport 在 density=2 时对应 20000×20000 像素，仅原始位图就约 1.6 GB，尚未算 WebView、JPEG 与 Base64。该输入可通过检查；没有实际申请这块内存，不宣称特定设备一定在该值崩溃。普通截图也到达此分配，full_page 的 32768 高度 cap 不能替代宽高和总像素预算。

应在实际布局与分配前限制物理像素和内存，并对超限返回明确结果；仅捕获 Exception 也不能覆盖 OutOfMemoryError。未运行超限／边界验证。

## BL10 — P2：并行下载的状态更新会覆盖彼此

下载运行在多线程 IO scope。[registerDownload:317](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:317) 和 [updateDownloadEntry:323](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:323) 都先读 _downloads.value，再计算整份列表并写回，没有 CAS 或串行化。StateFlow 单次读写线程安全不等于这组操作原子；[官方例子](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/) 使用 update 执行并发读改写。

**允许交错：** A 完成与 B 进度更新都读到旧列表；A 写入 COMPLETED，随后 B 从旧列表更新自身并写回，A 又变成 DOWNLOADING。A 已没有 Job 和后续更新，会一直留在下载中。并行注册也可丢掉整条记录，Main 上的已读／清理操作同样可能被旧快照覆盖。

_activeDownload 又在任意一项完成时置 null，即使另一项仍在下载。应复用原子列表更新，并从实际活动集合派生 banner。后续保存两条下载的事件序列、最终列表、Job 与文件摘要，未执行。

## BL11 — P2：CLI 忽略调用方会话，浏览器和本地页面使用固定全局池

[NativeOffload.kt:302](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/NativeOffload.kt:302) 已解析、校验 MINIS_CHAT_SESSION_ID，并传进 request。然而 [BrowserUseOffloadHandler.kt:98](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt:98) 调 app.sharedBrowserTabPool，未按 request.sessionId 选池；[MinisApp.kt:210](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:210) 将该池固定绑定 minis-browser-use。

不同会话的 CLI list_tabs、navigate、get_text 共用选中页。访问 minis://workspace 页面时，[interceptMinisURL:419](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:419) 取池的固定 sessionIdProvider，读取的不是调用方 workspace；页面自动触发的下载也使用固定池归属。只有 CLI encodeData 另按 request.sessionId 保存返回产物，不能修正页面执行身份。

应按现役会话及挂载 workspace 语义解析浏览器归属，缺失／已删除会话应明确失败。固定上游 [OffloadHandler:99](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/offload/BrowserUseOffloadHandler.kt#L99) 已用 BrowserCliPools.resolve 处理会话，另解析 mountedSessionIdFor。本轮不移植其 PRoot／sandbox 层，只比较职责和传入身份。未执行双会话交错。

## BL12 — P2：现役模型 schema 缺少分页与全页截图参数

真正的 browser_use 定义是 [AgentTools.kt:131](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:131)。第 139 行要求模型把 next_offset 作为 offset 继续读，但第 152–178 行的参数表没有 offset、max_chars，也没有实现已接受的 full_page。[AgentToolDefinition.kt:103](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/AgentToolDefinition.kt:103) 按该表生成 properties，不会从描述或解析器自动补字段。

模型未获得完整的分页操作 schema，只能猜未声明参数或另外运行 JS；不能把 parser 接受字段当成正常模型契约已具备。这里没有声称所有 Provider 都禁止额外字段。[BrowserUseTool.kt:81](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BrowserUseTool.kt:81) 虽有分页字段，但全库现役调用搜索只找到它自己的定义，它不是发送给模型的 schema。

这也补充上一轮 BR02 的证据边界：上一轮引用 BrowserUseTool 的 verified selector 描述属于闲置定义；现役 [AgentTools.kt:141](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:141) 有同样承诺，BR02 核心结论仍成立。旧报告不修改。固定上游 [AgentTools.kt:186](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt#L186) 已暴露 full_page；其旧 get_text 没有本地新分页承诺，不把上游缺 offset 当成本地问题已解决。未检查实际 Provider 请求录像。

## BL13 — P2：Cookie 工具的合法过滤参数被丢掉，返回形态也与承诺不符

[AgentTools.kt:168](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:168) 将 keywords 声明为字符串并允许空格分隔；[BrowserActionInput.kt:92](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserActionInput.kt:92) 实际只取 JSONArray。模型发送合法字符串 keywords="sid" 时得到 null，getCookies 于是按“未过滤”处理。

模型说明还承诺默认 fuzzy=true、模糊模式需含全部关键词、只回摘要和 env_cookies 文件路径；实际 parser 默认 false，[getCookies:1688](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1688) 用 any 匹配，第 1696 行将 cookie 值超过 80 字符的部分缩成 77 字符加省略号，其余值直接输出，本函数没有创建 env 文件或返回路径。调用方 ChatViewModel 和 CLI 的结果编码也没有补这个步骤。

**影响：** 过滤范围与调用意图不符，长值无法完整复用，模型按指令查找的环境文件也没有生成。这是暴露给模型的实际动作契约问题，不能作为普通历史注释噪音忽略。应统一字段类型、默认值、匹配关系与实际输出方式。未读取或使用任何真实 cookie。

## BL14 — P2：scroll_and_collect 把无效选择器当成成功的空结果

[BrowserUseManager.kt:1977](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt:1977) 捕获 querySelectorAll 的所有错误并返回空数组。调用方继续滚动、累加，最后构造 success=true 的结果，没有携带错误。

**触发：** item_selector="[" 是无效 CSS；工具按默认次数滚动后报告 matched: 0，Agent 无法区分参数错误与页面确实没有项目。解析批次错误也在第 1986 行静默忽略。

现役 schema 第 166 行另承诺省略 item_selector 会自动发现重复元素，执行器第 1962 行却直接拒绝；amount 参数也没有传给此动作。这些属于同一动作的参数／失败契约缺口，未拆成额外编号。应保留 typed failure，或实现模型已经获知的参数语义。未执行页面收集验证。

## BL15 — P3：按 URL 打开浏览器时忽略端口，可能选错服务

[normalizeUrlForMatch:1035](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1035) 只保留 scheme、host、path，丢弃端口。http://127.0.0.1:8080/report 与 http://127.0.0.1:9090/report 会成为同一匹配键。

[ChatViewModelUiStateExt.kt:110](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelUiStateExt.kt:110) 的工具预览入口会选中已有的另一服务页，既不新建也不加载目标 URL。应保留规范化后的端口身份；这里没有将代码明确选择忽略的 query／fragment 自动当成新问题。未运行双端口页面验证。

## BL16 — P2：原地改变 Tab 忙碌标志，不会可靠发出 StateFlow 更新

Tab 的 [inUse:117](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:117) 是普通 var。[acquireTab:812](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:812) 修改列表中已有对象，再把含相同对象的列表写回；[updateTabs:1289](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:1289) 也只浅复制列表。

[StateFlow 的 equals 合并规则](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-state-flow/#strong-equality-based-conflation) 会抑制相等新值的发射。旧值里的 Tab 已同步被修改，因此新旧列表相等，没有保留旧状态供比较。[BrowserSheet.kt:121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSheet.kt:121) 的 isAgentBusy 只是普通 getter，不是独立订阅。

**触发条件：** 在既有选中页开始 execute_js 等不改变 URL/loading 的动作，且没有其它因素让 BrowserSheet 重组时，忙碌变化本身不会更新 UI 防护；同理结束忙碌也可能不触发更新。其它状态引发重组可以偶然补上，不能据此宣称每次都失效或保证正确。应发布不可变的新 Tab 状态或可观察忙碌流，后续核对动作开始／结束事件和 UI 控制状态，未执行。

## 上游差异与未另计事项

- 已确认上游具体补过 BL02 的锁预算划分、BL06 的页 teardown、BL11 的 CLI 会话选池，以及 BL12 的 full_page schema。本地缺少这些处理；上游测试文件存在不等于本地实现已通过测试。
- 上游 BL04 对应的 ensureTabForUI 以及 BL08 对应的截图恢复仍保留相同结构。其它未完整比对的分支不宣称上游已经解决。
- 本地 Manager 未处理 onRenderProcessGone，属于旧 HP04 已登记的 WebView 缺口在 Agent 浏览器中的新调用点，不增加重复编号。固定上游 Manager 第 413 行处理 renderer 退出，池还有失效页清理；本地没有等价恢复。
- 旧 BR01–BR09、KM01 的无身份迟到 JS 回调、BF05 的文件命名／目录首屏问题不重计。本轮确认普通 URL 下载最终经 WorkspaceFileClient.writeStream 的 50 MiB 限制，不将它误写成完全无界下载；BR07 的 JS fetch 在全量读入之后才写 guest，属于不同路径。
- set_cookies 按 setCookie 调用次数报告成功，没有使用回调确认 native 接受；实际拒绝场景未运行，本轮作为待验证行为记录，未增加确定问题编号。
- 本地没有 BrowserTabPoolRegistry、HeadlessRenderHost 同名文件；上游有这些文件。没有用名字差异证明所有等价能力缺失，也不建议为单一实现机械复制上游整套抽象。

## 后续验证工件（本轮均未执行）

| 验证范围 | 应保留的可复核结果 |
|---|---|
| 导航与两种超时 | 请求目标、锁获得时刻、导航结束／错误、最终 success 和重试提示 |
| 三忙页、接管与 UI 忙碌 | 动作 ID、标签页 ID、控制者、实际页面变化、UI 禁用状态 |
| UI 初始化、清聊天与关闭 | 初始／最终 URL、待初始化标记、持久化内容、WebView 创建／销毁计数 |
| 截图取消及尺寸拒绝 | 取消前后 viewport、物理像素、拒绝结果与最终图像摘要 |
| 并行下载 | 所有状态迁移、终态、Job 和文件摘要，不能仅截图一个完成 banner |
| CLI 会话归属 | 两个会话的独有页面、tab_id、文件归属与交错执行日志 |
| 模型参数与 Cookie／收集 | 实际发送的 schema、合法参数、缺参／无效 selector 的拒绝，以及非敏感 fixture 输出 |

## 全文覆盖与只读复核

| 本轮新增全文文件 | 行数 |
|---|---:|
| [BrowserUseManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt) | 2007 |
| [BrowserTabPool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt) | 1291 |
| [BrowserSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserSheet.kt) | 737 |
| [ChatViewModelUiStateExt.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelUiStateExt.kt) | 133 |
| 合计 | 4168 |

AgentTools、AgentToolDefinition、NativeOffload、WorkspaceFileClient 等已登记全文文件本轮只核对相关调用片段，不重复增加全文数；ChatViewModel 和 MinisApp 增加片段记录，仍未计全文。

累计全文 **734/1546 个纳入文件、154769 行**；另有 14 个部分阅读、798 个尚未登记全文，目标继续有效。台账：[2026-10-05-code-review-coverage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

交付前复核全部 1670 个 tracked 路径及 SHA-256、固定 HEAD、tracked／staged diff、此前 46 份报告原哈希，以及本报告本地证据的文件和行号。只新增本报告、更新台账。没有设备、运行时性能、真实 Cookie 或云资源证据，不把静态推导当成实测。
