# Minis for Android — UI 架构与性能缺陷工程议题集

> 规范依据：`docs/contracts/05-ENGINEERING.md`

---

## 历史与现有议题/PR 对照查重表

在正式提交前，对仓库历史提交、在建 PR 及源码中的 `[Trace-Tag]` 进行了全库交叉对照：

| 本次议题 | 关联历史 Trace / PR / Issue | 状态与关系说明 | 审计结论 |
|---|---|---|---|
| **议题 1：`StreamingMarkdownText` 同步阻塞 Broker I/O** | `PR #92` / `docs/minis-seven-step-execution-plan.md` 第 30 行 | 已在 `feat/issue-51-provision-rollback` 审查中标记为阻塞项，目前 master 尚未修复 | 属于已知阻塞回归，需拆出独立 PR 修复 |
| **议题 2：解耦 3600+ 行 `StreamingMarkdownText.kt`** | 无直接对应 PR | 属于历史技术债积累，未曾单独立项 | 独立全新重构议题 |
| **议题 3：重构 `ChatViewModel` 上帝对象** | `UiMessagesSubListCmeTest` / 历史架构演进 | 历史曾修复并发修改异常，但未做领域解耦 | 独立全新架构议题 |
| **议题 4：KaTeX 离屏公式渲染池并发优化** | `GH#206` / `KatexWebViewPool.kt` | `GH#206` 仅解决了单张位图尺寸与内存预算，未解决单 WebView 串行排队延迟 | 属于 `GH#206` 的二次演进议题 |
| **议题 5：修复无 Session 附件 `minis://` 加载失败** | `PR #92` / `minis-seven-step-execution-plan.md` 第 31 行 | 在 `PR #92` 中被发现，因无 Session 传递空字符串被 Broker 拒绝 | 属于已知未闭环缺陷，需随文件 RPC 一同修复 |
| **议题 6：超长思考链虚拟化布局** | `[T-thinking-render-perf-android]` | 源码中存在该 Tag，当时采用了“尾部窗口截断 + 原生 TextView 弹窗”的临时 Workaround | 属于临时补丁的彻底正规化重构议题 |
| **议题 7：`GlassKit` 能效与 API 36+ 精确降级** | `GlassKit.kt:95-100` | 历史为了规避 HyperOS Android 16 RenderThread 崩溃做了全量版本禁用 | 属于粗暴降级的精细化优化议题 |
| **议题 8：`AgentStateBars` 布局收拢与手势仲裁** | `[T-android-table-hscroll-preserve]` | 历史解决了表格横向滚动偏移丢失，但未解决长按选区与横滑手势竞争 | 属于手势交互体验补充议题 |

---

## 议题详情

### Issue 1: [P0 · 性能/稳定性] 消除 `StreamingMarkdownText` 在 Compose 渲染路径中的同步阻塞 Broker I/O

- **Labels**: `bug`, `performance`, `ui`, `critical`
- **Priority**: P0

#### 问题现状
在 `StreamingMarkdownText.kt` 中，`stageGuestMedia()` 通过 `WorkspaceFileClient.readToFileBlocking()` 读取 Linux Guest 端的图片与多媒体文件。其底层使用 `runBlocking(Dispatchers.IO)`，在 Compose 重组或主线程中同步等待 Unix Domain Socket IPC 和最多 50 MiB 的磁盘传输。

#### 影响与风险
1. 聊天中出现大图或媒体时，主线程被强制挂起，导致 120Hz 高刷屏瞬间掉帧卡顿；
2. 在设备负载较高或 Broker 响应延迟时，极易直接触发系统 **ANR（Application Not Responding）** 强制杀死应用。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt`
- `src/android/app/src/main/java/com/openminis/app/runtime/minisd/WorkspaceFileClient.kt`

#### 解决方案
1. 移除 Compose 视图层所有的 `readToFileBlocking` 与 `runBlocking` 调用；
2. 实现基于 Coil 的自定义 `MinisGuestMediaFetcher : Fetcher`，通过后台协程异步请求 Broker 数据流并解码；
3. 引入占位骨架屏（Shimmer/Skeleton）与加载失败重试状态。

#### 验收标准
- 运行 `testDebugUnitTest` 保持全绿；
- 在加载 30MB+ 媒体文件的聊天流中，主线程掉帧（Choreographer skipped frames）降为 0，无任何 ANR 报错。

---

### Issue 2: [P1 · 架构重构] 解耦 3600+ 行 `StreamingMarkdownText.kt` 上帝文件，建立分层渲染流水线

- **Labels**: `refactor`, `tech-debt`, `ui`
- **Priority**: P1

#### 问题现状
`StreamingMarkdownText.kt` 单文件长达 3667 行，将 AST 分词解析、音视频控制器、表格状态池、KaTeX 桥接、代码高亮和手势选区全部塞入单一文件。每次流式接收新 Token 都会触发大量中间临时对象的创建，加剧 GC 停顿。

#### 影响与风险
1. 模块边界混杂，代码修改极易引发难以排查的非预期回归（Regression）；
2. 高频打字机输出时短生命周期对象激增，引发频繁 Minor GC，导致滑动微卡顿。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/markdown/`

#### 解决方案
1. **解析层独立**：将 `parseMarkdownBlocks` 抽取为纯 Kotlin 领域的增量 AST Parser，输出只读不可变节点树；
2. **组件分拆**：
   - `MarkdownTableBlock.kt`（表格渲染、横向滚动与图片快照）；
   - `MarkdownMediaBlock.kt`（音频波形、视频预览与图片异步加载）；
   - `MarkdownCodeBlock.kt`（代码语法高亮与一键复制）；
3. **Diff 优化**：引入增量 Token Diff 机制，仅对末尾追加的 Block 触发重组，冻结前方已完成的内容。

#### 验收标准
- 单文件代码行数控制在 400 行以内；
- 50 Token/s 高速流式生成过程中，JVM GC 频次降低 60% 以上。

---

### Issue 3: [P1 · 架构重构] 重构 `ChatViewModel` 上帝对象，按业务领域拆分独立 UseCase 与 StateHolder

- **Labels**: `refactor`, `architecture`
- **Priority**: P1

#### 问题现状
`ChatViewModel.kt` 职责过载（管理消息状态、工具权限审批、MCP Server 通信、语音录音播放、Slash 指令与会话分支），通过 4 个 Extension 文件横向拼凑，共享内部可变 StateFlow。

#### 影响与风险
1. 状态修改路径复杂，容易引发竞态条件（如历史上的 `UiMessagesSubListCmeTest` 并发修改异常）；
2. 单一状态变更可能引发不必要的全局 UI 级联重组。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`
- `ChatViewModelMentionExt.kt`, `ChatViewModelSlashExt.kt`, `ChatViewModelUiStateExt.kt`, `ChatViewModelStore.kt`

#### 解决方案
1. 将非 UI 核心逻辑下沉为独立的领域管理器：
   - `ToolApprovalCoordinator`（专门处理工具二次确认与危险操作弹窗）；
   - `SessionBranchController`（处理会话 Fork 与上下文迁移）；
   - `VoiceInputCoordinator`（管理 ASR/TTS 与录音状态）；
2. 将 `UiState` 精细化拆分为 `MessageListState`、`ComposerState` 和 `OverlayState`，实现局部定向重组。

#### 验收标准
- 核心 ViewModel 精简为纯编排层；
- 并发压力测试下无 `ConcurrentModificationException`。

---

### Issue 4: [P1 · 渲染并发] KaTeX 离屏公式渲染池支持多实例并发与自适应超时

- **Labels**: `performance`, `ui`, `math`
- **Priority**: P1

#### 问题现状
`KatexWebViewPool.kt` 采用单例离屏 WebView 与单个 `Mutex` 互斥锁，所有 LaTeX 和化学式渲染请求均串行排队。历史 Issue `GH#206` 解决了内存预算问题，但尚未解决排队并发问题。

#### 影响与风险
1. 在输出长篇公式论文或多道数学题时，公式逐个渲染，排在后面的公式出现长时间空白；
2. 在 4 秒超时限制（`RENDER_TIMEOUT_MS`）下，密集公式容易发生排队超时导致渲染中断。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/chat/KatexWebViewPool.kt`

#### 解决方案
1. 将单实例改造为 2~3 个实例的轻量 Worker 池（`KatexWorkerPool`）；
2. 针对高频简单公式（如单个变量 `$x$`、简单分式），引入快速通道（Fast Path）；
3. 根据队列长度动态自适应调整单任务超时窗口。

#### 验收标准
- 包含 30+ 独立公式的长文本渲染总耗时缩短 50% 以上；
- 极端排队下公式渲染超时失败率为 0。

---

### Issue 5: [P2 · 缺陷修复] 修复无 Session 上下文时 `minis://attachments/*` 无法加载的 Broker 拒绝缺陷

- **Labels**: `bug`, `storage`, `ui`
- **Priority**: P2

#### 问题现状
在全局通知、搜索或无会话预览中解析附件时，UI 层传入空字符串 `sessionId.orEmpty()`。底层的 `workspace_file.rs` 对 `/workspace/...` 路径强制要求有效的 `session_id`，直接返回 `BAD_PARAMS`，造成图片加载失败。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt`
- `src/native/minisd/src/workspace_file.rs`

#### 解决方案
1. 在 URL Scheme 解析规范中明确要求携带关联的 `session_id`（如 `minis://attachments/<session_id>/<file>`）；
2. 对于跨会话共享的公共媒体，统一定义并重定向到 Global Scope（如 `/shared/attachments/`）。

#### 验收标准
- 独立预览与非活跃会话中的 Markdown 附件均能正常加载，不出现 `BAD_PARAMS` 错误。

---

### Issue 6: [P2 · 性能/体验] 大模型超长思考链（Deep Thinking）采用分页虚拟化布局替换裁剪滑动窗口

- **Labels**: `enhancement`, `performance`, `ui`
- **Priority**: P2

#### 问题现状
在 `ChatAssistantMessageUI.kt` 中，历史补丁 `[T-thinking-render-perf-android]` 对思考内容做了截断（仅内联渲染尾部 8000 字，超过 10 万字强行弹窗）。

#### 影响与风险
1. 用户在聊天流中无法直接完整回溯模型完整的推导思考过程，打断了阅读流。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAssistantMessageUI.kt`

#### 解决方案
1. 在展开的思考链内部引入轻量级的 `LazyColumn` 虚拟化分段文本列表（按段落或每 2000 字作为一个 Item）；
2. 仅对当前可见区域内的分段执行测量与布局，实现无需弹窗的原生无界流畅滑动。

#### 验收标准
- 20 万字超长思考链在展开状态下滑动满帧，无内存暴涨与 ANR。

---

### Issue 7: [P2 · 适配/功耗] 优化 `GlassKit` 液态玻璃能效，细化 API 36+ 与低端 GPU 的精确降级策略

- **Labels**: `performance`, `compatibility`, `ui`
- **Priority**: P2

#### 问题现状
`GlassKit.kt` 在 Android 16 (API 36+) 上全量一刀切禁用了 Native 模糊；而在支持的机型上，高频滑动时的多层 RenderEffect 计算功耗偏高。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/glass/GlassKit.kt`

#### 解决方案
1. 将 API 36 的粗暴全量禁用细化为针对特定厂商/ROM 指纹（如 HyperOS 特定版本号）的精确黑名单，恢复其他正常 Android 16 设备的液态玻璃质感；
2. 在列表处于快速惯性滑动（Fling）状态时，动态降低背景模糊采样分辨率或暂停实时采样，滑动停止后再恢复高质感渲染。

#### 验收标准
- 快速滑动聊天列表时，GPU 能耗降低 35% 以上，设备发热显著改善。

---

### Issue 8: [P3 · 交互/UX] 重构输入框上方 `AgentStateBars` 布局密度，解决多层状态挤压聊天视口与手势冲突

- **Labels**: `ux`, `enhancement`
- **Priority**: P3

#### 问题现状
`ChatAgentStateUI.kt` 中的 Goal、Todo、Plan、Deliverables 多层状态条全部平铺在输入框上方，在移动端竖屏下大量挤占对话可视空间；同时自研 `MinisTextKit` 的文本长按手势与表格横向滚动存在偶发性冲突。

#### 涉及文件
- `src/android/app/src/main/java/com/openminis/app/ui/chat/ChatAgentStateUI.kt`
- `src/android/app/src/main/java/com/openminis/app/ui/chat/MinisTextKitSelection.kt`

#### 解决方案
1. 将多层任务条收拢为**单行自适应微型胶囊（Pill）**，支持一键向上展开抽屉式面板；
2. 增强 `MinisTextKit` 的手势仲裁器（Gesture Arbiter），当检测到用户在表格区域横向拖动像素距离超过判定阈值（Slop Threshold）时，主动让渡手势给横向滚动，避免误触发选区。

#### 验收标准
- 默认状态下状态胶囊高度减少 60% 以上；
- 表格横向滑动时误触长按选区的概率降为 0。
