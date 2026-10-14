# 公式渲染、Markdown 技能预览与技能浏览页静态审计

日期：2026-10-06。项目 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本轮新增 **8 项静态确认的问题（7 项 P2、1 项 P3）**。继续只读审计；没有修改产品代码、运行测试或构建、启动模拟器、使用设备或云资源。所有运行效果、回调时序和资源占用量仍需后续验证，本文不把静态交错当成实测录像。

固定对照 [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)，提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。既有报告不修改。

## 现役入口与范围

- 聊天公式由 [StreamingMarkdownText.kt:2210](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:2210) 和第 2299 行的 produceState 调用共享 KatexWebViewPool；latex、主题和字号变化会重新启动渲染任务。
- 技能详情在 [SkillsManagementScreen.kt:862](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SkillsManagementScreen.kt:862) 使用另一套 MarkdownText，正文前五个非空行可进入公式、音视频等渲染分支。这里不是主聊天的 MarkdownDocument，不能把这套 renderer 的问题直接扩大成所有聊天消息都有同样行为。
- 技能浏览页在 [AppNavigation.kt:1295](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1295) 注册，导入按钮调用 [MinisSkillsBrowserScreen.kt:110](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MinisSkillsBrowserScreen.kt:110)。没有把上一轮关闭的 Web App 菜单当成本轮入口。

## KM01 — P2：共享公式池的迟到回调没有请求身份

[KatexWebViewPool.kt:152](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt:152) 用 Mutex 串行化 Kotlin 渲染，然而 [170–183 的等待入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt:170) 把当前任务放进一个全局 pending，等待超时后清空。协程取消会跳过第 183 行，因为没有 finally。

[JS bridge:221](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt:221) 收到任何结果都完成当前 pending，没有 render ID；[HTML:64](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/katex/katex-render.html:64) 经过字体 Promise 和两次 requestAnimationFrame 才回调，也没有取消或任务编号。

**允许的交错：** A 的 evaluateJavascript 已发出 → A 超时或因 produceState key 变化而取消，释放 Mutex → B 写入新的 pending → A 的迟到 JS 回调完成 B 的 deferred。B 于是把不属于自己的完成信号当成有效结果，开始截图并可能按 B 的 cache key 缓存旧内容或错误尺寸。锁保证了等待任务串行，却没有结束已经发往 renderer 的异步工作。

本轮确认缺失任务身份与取消清理；具体屏幕上出现哪个旧像素仍取决于异步 IPC/绘制时序，未实测。处理方向是让结果、WebView 代次与请求对应，并丢弃超时/取消任务的迟到结果；不能仅增加 Mutex。后续 E2E 交错两个内容明显不同的公式、超时与取消，保存请求 ID、返回尺寸和最终位图；未执行。

**上游：** [固定版本第 170 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt#L170) 与第 221 行同样使用全局无身份回调槽。

## KM02 — P3：重建 WebView 时复用上一实例已完成的 ready Deferred

[KatexWebViewPool.kt:121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt:121) 的 releaseWebView 销毁实例、清空 webView 并将 isReady 置 false，却没有清空 [readyDeferred:297](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt:297)。第一次加载完成后，这个 Deferred 已由 [onPageFinished:228](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt:228) 完成。

之后新建实例时，[awaitReady:302](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt:302) 直接取旧 Deferred，等待会立即结束，即使新 HTML 还未加载并定义 renderMath。第 179 行于是可能提前执行不存在的函数，收不到 bridge 回调，等满四秒后返回 fallback。

**现役触发：** [MinisApp.kt:1277](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1277) 清公式缓存，在第 1284 行的严重内存回收分支释放池的 WebView。恢复界面后的首次未缓存公式就可能走上述重建路径。平台是否会在特定系统版本发送该 trim 级别未实测，不宣称每次退后台都发生。

**上游：** [固定版本 releaseWebView:121](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt#L121) 和 awaitReady 第 323 行相同。应将 ready 信号绑定实例生命周期，后续验证旧实例加载完成→释放→新实例慢加载时不会提前渲染；未执行。

## KM03 — P2：单公式 AndroidView 只在 factory 捕获首次参数

[KaTeXView.kt:150](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt:150) 的 cache key 会随 latex/displayMode/主题变化，第 171–176 行也创建新状态。但 [AndroidView:207](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt:207) 没有 key 边界或 update；其 bridge 和 [onPageFinished:309](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt:309) 都在 factory 中捕获最初的参数、cache key 和状态对象。

[AndroidX 的 API 合同](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/viewinterop/AndroidView.android.kt#L55) 说明 factory 对一个 View 只调用一次，重组更新应在 update 中执行。改变 remember 的 key 不会自行重建这个 AndroidView。

**触发：** A 尚未完成截图，主题或同一位置的公式变成 B，且 B 没缓存。组件仍留在“等待渲染”的同一 AndroidView 分支，旧实例继续渲染 A，回调写入旧状态；新 B 状态没有渲染任务，可能一直显示占位框。即使 A 随后成功，也不会给 B 提供正确结果。

**边界：** 已命中 B 缓存或 View 真正移出再进入 composition 的路径另作判断，不宣称所有参数变化必然失败。固定上游也把参数捕获在 factory，新增的 onRelease 没有解决参数更新。后续 E2E 在渲染完成前切换主题/latex，并对比最终 cache key 与位图；未执行。

## KM04 — P2：单公式 WebView 移出界面没有销毁

[KaTeXView.kt:207](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt:207) 为每个未缓存公式创建 WebView，整段 AndroidView 只有 factory 和 modifier，没有 onRelease/DisposableEffect。成功后 [178 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt:178) 改为 Image，旧 AndroidView 已退出 composition；返回技能列表也会退出。

[平台 destroy 合同](https://developer.android.com/reference/android/webkit/WebView#destroy()) 要求 View 从界面移除后销毁其内部状态。当前没有主动结束每个公式的页面/renderer 资源，离开页面后也没有取消第 246 行排队的截图回调。不能仅以位图缓存有字节上限证明 WebView 生命周期已受约束。

这是明确缺少释放，不等于断言每个对象永远无法被 GC/finalization 回收。实际 native 内存增长、回收速度与 OOM 阈值未测量。

**上游已补：** [固定版本第 358 行](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt#L358) 的 onRelease 标记实例失效、stopLoading、destroy，第 260 行拒绝失效实例的迟到截图。本地缺少这些处理。后续 E2E 多次打开/退出不同公式内容，核对创建与销毁计数、迟到回调和资源趋势；未执行。

## KM05 — P2：技能预览的标题与列表直接删除公式

[MarkdownParser.kt:90](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt:90) 先提取公式，用占位符替换原文。[restoreBlock:721](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt:721) 保留 Heading、BulletList 和 NumberedList 中的占位符。

[MarkdownText.kt:145](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt:145)、[334](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt:334)、[362](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt:362) 把标题和列表直接交给 InlineContent，没有传 mathSpans。[InlineContent:515](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt:515) 把所有剩余公式占位符替换为空串。

**静态示意：** 技能正文的 `# 参数 $x$`、`- 使用 $x^2$`、`1. 求 $x^2$` 会保留其它文字，但公式消失。普通段落与表格有专门的公式还原路径，不能用它们证明标题/列表正常。原始技能文件没有被删除，是预览输出丢内容；主聊天走另一套渲染器，不扩大此结论。

**上游：** 固定版本的 MarkdownParser 第 721 行和 MarkdownText 第 511 行保留同样链路。应复用已有 math span 渲染并覆盖标题、无序/有序/任务列表及引用中的相同节点；后续 E2E 保存源文与实际预览内容，未执行。

## KM06 — P2：四反引号代码块无法按自身围栏结束

[MarkdownParser.kt:107](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt:107) 用 startsWith 三反引号接受四反引号开头，却只移除三个字符作为语言；[115 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt:115) 要求结束行严格等于三个反引号。

**静态示意：** 一个用四反引号开始和结束的代码块，若中间没有单独三个反引号，结束后的空行与“后续正文”仍会被读入 codeLines，直到文件末尾。语言还会多出一个反引号。若内层出现三个反引号，又可能提前结束外层代码块。

与此不同，[公式提取:366](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt:366) 和 buildCodeMask 第 573 行记录实际 fenceLen，并认识波浪线围栏。前后两层语法不一致，公式 mask 的成功不能证明块解析正确。

固定上游第 107、115 行相同。处理方向是让围栏识别/结束规则保持一致；后续 E2E 验证三/四反引号、内部短围栏及结束后的正文，未执行。四反引号问题计入本项，波浪线的完整显示与缩进规则作为同一语法范围继续核对，不另造编号。

## KM07 — P2：技能导入在配套文件完成前宣布成功

[MinisSkillsBrowserScreen.kt:257](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/MinisSkillsBrowserScreen.kt:257) 调 importFromGitHub，返回非空即在第 259 行显示 success。

但 [SkillRepository.kt:761](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:761) 把 scripts/references 等下载分离到后台 scope，第 785 行已经返回 skill。下载不完整只在 [782 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:782) 写日志；事务拒绝也只有日志。UI 没有收到完整/部分完成状态。

**后果：** SKILL.md 已登记，资源下载仍慢或被拒绝，用户却看到完整导入成功并可开始使用；要求调用 bundled script 的技能可能在“导入成功”后无法工作。后台任务脱离页面可以防止退出页面中断下载，但不能代替完成回执。

项目已经有 [updateFromURL:975](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:975) 的 PartialSuccess 机制，可参考其真实结果语义，不需要再建立一套形式化抽象。后续 E2E 控制 SKILL.md 成功、配套文件延迟/失败，验证 UI 最终状态与实际资源一致；未执行。

**上游：** [固定版本 importFromGitHub:619](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt#L619) 也后台下载并提前返回；技能浏览页第 281 行同样立即显示成功。

## KM08 — P2：技能预览音频同步 prepare，失败对象没有释放

[MarkdownText.kt:885](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt:885) 在 composable 的 remember 中创建 MediaPlayer，并同步 setDataSource/prepare。文件 staging 放在 IO 不会把随后 remember 中的播放器准备一起移到 IO。

[Android 官方媒体说明](https://developer.android.com/media/platform/mediaplayer/basics#asynchronous-preparation) 明确要求避免在 UI 线程调用耗时的 prepare。技能前五个非空行若包含指向可解析本地文件的音频节点，进入详情就会准备，尚未点击播放也会阻塞 UI。大文件、损坏格式的实际等待时间和 ANR 未测量。

同一 try 里若 setDataSource 或 prepare 抛异常，catch 返回 null；刚创建的 MediaPlayer 没有交给 [DisposableEffect:891](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt:891)，也没有在失败处 release。成功路径的释放不能覆盖初始化失败路径。固定上游第 870 行也同步 prepare 并返回 null，属于共有问题。

处理方向是使准备过程不阻塞 composition，并将失败/取消对象也纳入生命周期。后续 E2E 对成功与损坏媒体分别核对 UI 响应、准备结果、播放器创建/释放计数；未执行。

## 复核、排除和待继续核对

1. **不重复计数 HP03/HP04。** 技能浏览页第 170 行使用默认 USER_INITIATED，同样没有手势判断；KatexWebViewPool、KaTeXView 和技能浏览页也没有 onRenderProcessGone。这些是上一轮导航来源与 renderer 故障处理问题的新调用点，另列新编号会重复。固定上游已为这些 WebView 增加 renderer 退出处理。
2. **不把旧注释当成仓库现状。** UI 回退代码声称 importFromGitHub 的 null 可代表重复技能；当前 importFromContent 第 533 行读取现有记录并提交更新，不是简单拒绝重复。没有据此断言每次重复导入只更新正文。回退分支的非 GitHub URL、手写 frontmatter 解析与无界读取仍需独立核对。
3. **KaTeX trust 是进一步核对项。** [katex-render.html:59](/home/jiale/projects/minis-for-android/src/android/app/src/main/assets/katex/katex-render.html:59) 显式 trust=true；本地压缩库片段标明 v0.16.21。[该固定版本 includegraphics 实现](https://github.com/KaTeX/KaTeX/blob/v0.16.21/src/functions/includegraphics.js#L82) 在信任通过后生成 Img；[官方配置说明](https://katex.org/docs/options.html#trust) 区分可按命令/URL/protocol 授权的能力。两套 WebView 都开启 file access，也没子资源过滤。必须继续核对公式嵌入资源的产品策略、可达文件范围与实际 WebView 加载限制；本轮不宣称有任意代码执行、秘密被读取或数据外发，未发送测试资源请求。只搜索版本/函数片段，不把整份 minified 库算成全文阅读。
4. 单公式 renderer 还缺页面加载失败的结束状态与等待期限，而普通 KaTeX 解析错误确实由 HTML catch 回调。没有将所有“不支持的公式”都写成永远等待。
5. BrowserWebView 只负责挂载池持有的 View，onRelease 移除 parent 而不销毁是其所有权设计；不能直接套 KM04。InAppBrowserHost 目前没有生产调用方，其默认 no-op 与文档不一致未算现役缺陷。SyntaxHighlighter 也未发现生产调用，不因文件存在就声称语法高亮已接通。

## 阅读记录和验证边界

新增全文阅读 **15 个文件、3,663 行**：

| 文件（相对仓库） | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt | 344 |
| src/android/app/src/main/java/com/openminis/app/ui/markdown/KaTeXView.kt | 327 |
| src/android/app/src/main/assets/katex/katex-render.html | 85 |
| src/android/app/src/main/java/com/openminis/app/ui/browser/BrowserWebView.kt | 74 |
| src/android/app/src/main/java/com/openminis/app/ui/components/InAppBrowserLauncher.kt | 83 |
| src/android/app/src/main/java/com/openminis/app/ui/settings/MinisSkillsBrowserScreen.kt | 386 |
| src/android/app/src/test/java/com/openminis/app/ui/KatexBitmapClampTest.kt | 113 |
| src/android/app/src/test/java/com/openminis/app/ui/markdown/KaTeXCacheKeyThemeTest.kt | 33 |
| src/android/app/src/main/java/com/openminis/app/ui/markdown/SyntaxHighlighter.kt | 94 |
| src/android/app/src/main/java/com/openminis/app/ui/components/UrlPreviewSheet.kt | 31 |
| src/android/app/src/main/java/com/openminis/app/browser/GoogleAuthRouter.kt | 69 |
| src/android/app/src/test/java/com/openminis/app/ui/browser/ExternalSchemeOriginTest.kt | 141 |
| src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownText.kt | 991 |
| src/android/app/src/main/java/com/openminis/app/ui/markdown/MarkdownParser.kt | 775 |
| src/android/app/src/test/java/com/openminis/app/ui/markdown/LatexCodeMaskTest.kt | 117 |

现有测试源码只覆盖 bitmap 缩放算术、theme cache key、部分公式/code mask 和 scheme 路由函数；没有覆盖请求身份、异步任务取消、composition 参数变更/释放、技能下载完成回执以及完整标题/列表渲染。不把测试文件存在当成这些路径已验证；本轮也没有执行、修改或新增测试。

MinisApp、StreamingMarkdownText、SkillRepository、SkillsManagementScreen 和导航仅作相关片段复核，不计全文。平台/上游代码不计本地覆盖。

累计全文 **706 / 1,546 个纳入文件（45.7%）、146,681 行**；部分阅读 13 个，尚无全文登记 827 个。全库 tracked 1,670 个，排除 124 个。具体 SHA-256、行数与完整读取范围见 [覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。仍有大量文件未读完，目标保持进行中。

交付核验包括所有 tracked 路径/哈希、HEAD、空 tracked/staged diff、44 份既有报告哈希和本文本地链接行号。仅新增本文与更新覆盖台账，未改产品与既有报告。
