# Eta 能力对照：流式 UI 呈现、权限/健康可视化、GUI 动作、Root 探测与系统入口

| 项 | 值 |
|---|---|
| 分析领域 | 增量 Markdown 与文本显现动画；权限/健康状态可视化；GUI Agent 动作策略（截图、滚动、手势、节点定位）；Root 管理器探测与安装器；系统入口接管与浮层架构 |
| Eta 参考 | 提交 `c15de97`，检出目录 `/tmp/eta-upstream-clone`，许可证 PolyForm Noncommercial License 1.0.0 |
| Minis 基线 | `origin/main` = `69e05e51`，工作树 `/home/jiale/projects/minis-eta-integration`，分支 `codex/eta-capability-integration` |
| 证据口径 | Minis 侧判断全部给出 `文件:行号`，路径以 `src/android/app/src/main/java/com/openminis/app/` 为基准（新建文件与测试文件写仓库相对路径）；Eta 侧路径以 `app/src/main/kotlin/io/github/mangi/eta/` 为基准。结论仅来自静态源码阅读 |
| 法律边界 | Eta 的 PolyForm Noncommercial 1.0.0 允许按非商业目的复制、修改与派生，因此可直接复用其源文件，再按本仓库包结构与测试边界适配；许可条款与 Required Notice 集中放在 `third_party/eta/LICENSE`，归属声明集中在 `PROVENANCE.md` 与 `THIRD_PARTY_LICENSES.md` |
| 分类口径 | 沿用 `docs/analysis/eta-integration-plan.md` 的 A/B/C/D 分类（A 纯算法可单测；B 需本地适配；C 与本项目合同冲突，只吸收问题定义；D 依赖真机/ROM/Root） |

## 1. 结论摘要

按「对 Agent 行为可靠性的影响 / 用户可见质量 / 可测试性」排序，值得移植的共 6 项：

1. **GUI 动作证据与超时语义（最高价值）**
   Eta 把「动作是否真的发生」建模成显式证据枚举与拒绝条件：`agent/device/ScrollEvidenceContract.kt` 用
   `MOVED_BY_EVENT / MOVED_BY_ANCHOR_MOTION / DIRECTION_MISMATCH / AT_BOUNDARY / UNVERIFIED` 描述一次滚动，
   `agent/device/ScrollAxisContract.kt` 在只有相反轴证据时禁止手势兜底，
   `agent/device/GestureFallbackPolicy.kt` 只在 `GESTURE_NOT_DISPATCHED` 时允许换实现重放，
   `agent/accessibility/MainThreadCallGate.kt` 用 `PENDING/RUNNING/FINISHED/CANCELLED` 阻止「同步桥超时返回后动作迟到执行」。
   Minis 现在只有布尔结果：`tools/android/AndroidUiController.kt:218` 的滚动把
   `AccessibilityNodeInfo.performAction` 的返回值直接当作 `success`，
   `accessibility/MinisAccessibilityService.kt:163` 的手势在等待超时后返回 `false`，与「从未派发」不可区分。
   移植后能把「未证明发生」和「已发生但方向不符」分开报告给模型，直接减少误报成功的循环。

2. **截图/观察的窗口一致性与 UNKNOWN 语义**
   Eta 的 `agent/accessibility/ScreenshotWindowPolicy.kt` 用
   `CAPTURE / EXCLUDE / BLOCK_UNKNOWN` 保证「模型看到的」和「真正接收触摸的」一致：排除自身无障碍浮层与配置的排除包，
   当排除集合非空且窗口包名无法解析时改为拒绝；`agent/accessibility/PackageWindowVisibility.kt` 用
   `VISIBLE/GONE/UNKNOWN` 避免把主线程查询超时当成窗口已消失。
   Minis 的 `accessibility/MinisAccessibilityService.kt:121` 会把所有窗口的 root 与 active root 混在一起返回，
   没有排除自身浮层；`tools/android/AndroidUiController.kt:84` 的截图不做任何窗口筛选；
   `tools/android/AndroidUiObservationRegistry.kt:128` 把指纹登记进栅栏时没有带上截断标志，
   而指纹本身在 `tools/android/AndroidUiObservationRegistry.kt:46` 处被限制为 2000 个节点。
   截断快照仍被当作权威指纹，属于 fail-open 风险；以 Eta 的判据收敛即可。

3. **流式 Markdown 的未闭合结构投影**
   Eta 在 `ui/markdown/StreamingGfmParser.kt:82` 定义了一步投影：表格在分隔行确认前不发布候选表头
   （`ambiguousTableStart`）、未闭合围栏用虚拟闭合符补齐（`findOpenFence`）、
   未闭合链接从投影里截掉（`findPendingLinkStart`）、行内强调与代码用虚拟闭合符稳定节点类型（`findInlineClosures`），
   且虚拟字符永不写回消息（`StreamingGfmParser.kt:70` 要求终态内容与原始内容一致才交付）。
   Minis 的块解析器 `ui/markdown/MarkdownParser.kt:97` 没有等价投影：表格必须等分隔行
   （`ui/markdown/MarkdownParser.kt:156`），行内解析 `ui/markdown/MarkdownText.kt:555` 每次从头重扫并把未闭合标记当普通字符输出，
   于是同一个片段会先以纯文本出现、闭合后突然变成富文本。
   Minis 已经有一份可复用的解析状态机：`ui/chat/StreamingMarkdownText.kt:3293` 的 `safeInlineSplitOffset` 已经在跟踪未闭合的
   `**` 与下划线、删除线、标签与链接目标，只是用于缓存切分而不是显示稳定性。这是本领域投入产出比最高的一项。

4. **Root 拒绝与命令失败的区分、超时状态与管理器身份**
   Eta 的 `agent/device/RootCommandEnvelope.kt:7` 在执行目标命令前先向 stderr 写一个带随机 token 的授权标记，
   于是「命令自身非零退出」不会被误判为「授权被撤销」；`agent/device/RootAccess.kt:17` 的状态枚举含独立的 `TIMED_OUT`，
   `RootAccess.kt:117` 的 `shouldRequestRoot(explicit, attempted, wasGranted)` 让首次发现时才自动申请一次，
   `RootAccess.kt:53` 的 `markDenied()` 只由执行器确认的拒绝调用。
   `systemizer/GoogleAppSystemizerInstaller.kt:23` 先探测管理器家族，`:29` 与 `:35` 用预检枚举与密封结果类型表达
   「缺少前置」「管理器不支持」「需要重启」等可行动结论。
   Minis 的 `tools/android/PrivilegedCommandRunner.kt:129` 只有 5 个状态且没有超时态，
   `runtime/ubuntu/DirectRootRunner.kt:112` 用退出码 124 表达自身超时，但对「su 被拒绝」与「命令执行失败」没有区分手段；
   管理器家族也没有被探测（在 `runtime/` 与 `tools/android/` 内检索根管理器模块目录与命令名无命中）。
   Minis 比 Eta 强的部分要保留：`tools/android/AndroidCapabilityResolver.kt:37` 的四态能力矩阵、
   `tools/android/PrivilegedCommandRunner.kt:177` 的 uid 与能力位判定、`PrivilegedCommandRunner.kt:315` 的 argv 契约。

5. **显现/淡入进度的单调不变式**
   Eta 在 `ui/components/SmoothTextReveal.kt:44` 为整条回答维护一条时钟，用字素边界索引推进
   （`SmoothTextReveal.kt:485`、`SmoothTextReveal.kt:514`），速度取下限 36 字素/秒与「按积压量在 0.20 秒内追平」的较大者
   （`SmoothTextReveal.kt:553`、`:570`、`:571`），单帧间隔上限 0.05 秒（`:569`）。
   关键不变式是进度只能前进：行内标记闭合会让渲染文本变短，此时若进度回退，已显示的字会消失再重新打字。
   页面不可见时用 `pauseAnimationsAndCatchUp`（`:62`）直接追平，避免回到前台补播积压动画。
   Minis 的 `ui/chat/StreamingFade.kt:89` 在文本不再以旧文本为前缀时直接丢弃全部在途淡入区间
   （`ui/chat/StreamingFade.kt:93`、`:94`），没有跨重排的单调进度概念。

6. **健康/接线状态统一账本与聚合卡片**
   Eta 的 `core/HookRegistrar.kt:7` 用 `INSTALLED/MISSING/FAILED/SKIPPED` 记录每一条接线的安装结果，
   `HookRegistrar.kt:31` 输出单行汇总（四个计数），`HookRegistrar.kt:184` 用稳定 id 规则约束条目命名，
   `ModuleMain.kt` 按进程名决定是否安装并在不需要时解除注册。
   UI 侧 `ui/components/PermissionHealthCard.kt:36` 用「非可用条目计数 + 前 3 条 + 点击进入详情」
   把 `ui/model/PermissionHealthUiState.kt` 的四态（Available/Missing/Warning/Disabled）压成一张可扫读的卡片。
   Minis 的权限页本身很强（`ui/settings/SystemPermissionsScreen.kt:70` 每秒刷新无障碍连接、降级、撤销、Shizuku 与悬浮窗状态，
   `ui/settings/SystemPermissionsScreen.kt:126` 提供受限制设置解锁，`:239` 提供 OEM 自启动与电池白名单引导），
   入口在 `ui/settings/SettingsScreen.kt:379`。
   缺的是两件事：运行时前置条件是「首个失败即返回」的单一错误串
   （`runtime/ubuntu/UbuntuKernel.kt:115` 依次短路并只回传 `Status(ready, error)`），
   以及把这些状态汇成一张卡片的能力。

## 2. 逐项对照表

说明：`差距判定` 使用「缺失 / 更弱 / 已相当 / 不该移植」四档；`已相当` 指能力等价或 Minis 更强。

| # | 能力 | Eta 做法（概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|---|
| 1 | 流式未闭合结构投影 | 解析前做一步纯投影：虚拟补齐围栏与行内标记，截掉未闭合链接，终态必须与原始内容一致（`ui/markdown/StreamingGfmParser.kt:82`、`:70`） | 块解析无投影 `ui/markdown/MarkdownParser.kt:97`；行内每帧全量重扫并降级为字面量 `ui/markdown/MarkdownText.kt:555` | 缺失 | 新增纯函数投影层，流式期间在 `ui/chat/StreamingMarkdownText.kt:558` 的解析入口前调用；复用 `ui/chat/StreamingMarkdownText.kt:3293` 已有的未闭合状态扫描 |
| 2 | 表格候选行延迟发布 | 分隔行确认前，含竖线的疑似表头行不进入渲染源，避免先按段落显示再变表格（`ui/markdown/StreamingGfmParser.kt:104`） | 只有检测到分隔行才建表 `ui/markdown/MarkdownParser.kt:156`，之前一律按段落渲染 | 缺失（同一投影层的一部分） | 与第 1 项同一实现，作为投影的表格分支 |
| 3 | 文本显现时钟 | 一条回答一条时钟，字素级进度、自适应速度、不可见时追赶、进度单调（`ui/components/SmoothTextReveal.kt:44`、`:62`、`:553`） | 仅对最后一块做词级淡入，非追加输入时硬重置丢弃在途区间（`ui/chat/StreamingFade.kt:89`、`:93`） | 更弱 | 先落地单调进度变式（见 §3.5）；字符级显现是否采用由产品决定 |
| 4 | 帧级写状态开销 | 帧间只改普通字段并请求重绘，仅跨行时请求一次测量，不写 Compose 状态（`ui/components/SmoothTextReveal.kt:38`） | 每帧写快照状态映射并重建该块富文本（`ui/chat/StreamingFade.kt:185`） | 更弱（但只在流式尾块，影响面有限） | 若采纳第 5 项，可顺带改为绘制阶段叠加，避免每帧重组 |
| 5 | 流式节流与缓存预算 | 无等价物（每次提交完整解析一次） | 自适应节流分档与字符预算缓存 `ui/chat/StreamingMarkdownText.kt:530`、`ui/chat/StreamingMarkdownText.kt:2996` | 已相当（Minis 更强） | 保持；投影层必须在该节流之内完成，不得新增每 token 解析 |
| 6 | 权限聚合卡片 | 非可用条目计数 + 前 3 条 + 点击进入详情，四态枚举（`ui/components/PermissionHealthCard.kt:36`、`ui/model/PermissionHealthUiState.kt`） | 只有完整权限页，无聚合摘要；入口 `ui/settings/SettingsScreen.kt:379`，页面状态 `ui/settings/SystemPermissionsScreen.kt:70` | 更弱 | 新增只做映射的聚合卡片，数据取现有来源，不改权限语义 |
| 7 | 接线/前置状态账本 | 每条接线记录四种结果并输出一行计数汇总（`core/HookRegistrar.kt:7`、`:31`、`:184`） | 启动前置是首个失败即返回的单一错误串 `runtime/ubuntu/UbuntuKernel.kt:115`；rootfs 有独立健康码 `runtime/ubuntu/RootfsHealth.kt` | 缺失 | 把 `UbuntuKernel.ensureReady` 的短路改写为累积账本，保留原有失败语义与顺序 |
| 8 | 无障碍节点身份与新鲜度 | 指纹加新鲜度策略，截断快照不得绕过内容变化（`agent/accessibility/AccessibilityNodeIdentity.kt`） | 代际加语义定位器加全树指纹栅栏 `tools/android/UiGenerationFence.kt:18`、`tools/android/AndroidUiObservationRegistry.kt:144` | 已相当（更强），但截断未纳入 | 把截断标志传入栅栏；截断代际只允许重新观察，不允许继续用旧引用 |
| 9 | 截图窗口筛选 | 排除自身无障碍浮层与配置排除包；排除集合非空且包名不可解析时拒绝（`agent/accessibility/ScreenshotWindowPolicy.kt`） | 截图取整屏不筛选 `tools/android/AndroidUiController.kt:84`；观察混用全部窗口 root `accessibility/MinisAccessibilityService.kt:121` | 缺失 | 新增窗口决策纯策略，接入截图与观察的根节点收集 |
| 10 | 窗口可见性三态 | 查询超时不等于窗口消失（`agent/accessibility/PackageWindowVisibility.kt`） | `foregroundPackage()` 失败即返回空对，超时与不存在不可区分 `accessibility/MinisAccessibilityService.kt:156` | 缺失 | 在返回结构里区分可见、已消失、未知，并在动作结果里透出 |
| 11 | 滚动证据 | 五态证据枚举，含方向不符、到达边界、未验证（`agent/device/ScrollEvidenceContract.kt`） | 滚动只回传布尔（`tools/android/AndroidUiController.kt:218`、`:239`） | 缺失 | 新增证据枚举；先做「节点滚动前后快照对比」的等价实现 |
| 12 | 滚动轴契约 | 只暴露相反轴时禁止手势兜底（`agent/device/ScrollAxisContract.kt`） | 无轴判断；坐标滚动默认接受任意方向 `tools/android/AndroidUiController.kt:218` | 缺失 | 在坐标兜底前用节点支持的 action 集合做轴判定 |
| 13 | 手势兜底与迟到动作 | 只有确认未派发才换实现；用调用门阻止超时后动作迟到执行（`agent/device/GestureFallbackPolicy.kt`、`agent/accessibility/MainThreadCallGate.kt`） | 手势超时后返回 false，与未派发不可区分，且无取消门 `accessibility/MinisAccessibilityService.kt:163` | 更弱 | 给同步手势桥加显式状态机与取消位，失败码区分未派发与未知 |
| 14 | 滚动事件观测窗口 | 仅在主动滚动后的短窗口内接收对应包与窗口的滚动事件，带 token 与过期时间（`agent/accessibility/ScrollEventObservationGate.kt`） | 事件环形缓冲只存类型、包、类、文本与时间 `accessibility/MinisAccessibilityService.kt:82`、`:33` | 缺失 | 复用事件流做有界观测；不得把任意事件当证据 |
| 15 | 输入文本结果预测 | 按 UTF-16 选区推算一次真实键入后的文本与光标，密码或选区不可信时拒绝（`agent/accessibility/TextEditPlanner.kt`） | 只回传布尔与所用方法，无结果校验 `tools/android/AndroidUiController.kt:184` | 缺失 | 加纯函数计划器用于校验与断言，密码字段一律不读取、不回传 |
| 16 | 截图结果分级 | 四态质量加兜底前置条件（`agent/device/ScreenshotOutcomePolicy.kt`） | 已有错误码与说明字段 `accessibility/MinisAccessibilityService.kt:223`、`tools/android/AndroidUiController.kt:130` | 更弱（差在分级与部分成功） | 在现有错误码之上补四态归类 |
| 17 | Root 状态机与申请策略 | 六态含超时；首次发现申请一次，拒绝后仅显式申请；只在确认拒绝时标记（`agent/device/RootAccess.kt:17`、`:117`、`:53`） | 五态无超时 `tools/android/PrivilegedCommandRunner.kt:129`；授权判定 `tools/android/PrivilegedCommandRunner.kt:177`；能力矩阵更强 `tools/android/AndroidCapabilityResolver.kt:37` | 更弱（局部） | 补超时态，保持被动探测不触发 su 的既有约束 |
| 18 | 拒绝与命令失败区分 | 执行前写授权标记，标记缺失且已结束才算拒绝（`agent/device/RootCommandEnvelope.kt:7`） | 无等价机制；超时用退出码 124 表达 `runtime/ubuntu/DirectRootRunner.kt:112` | 缺失 | 新增标记封装；保持 `DirectRootRunner` 只跑内部构造脚本的边界 |
| 19 | 有界命令执行 | 默认 8 秒、上限 30 秒，输出 256 KiB 默认与 2 MiB 上限，读取即截断但持续排空管道（`agent/device/BoundedRootCommandExecutor.kt:24`、`:158`） | 捕获上限 1 MiB 字符并持续排空 `runtime/ubuntu/DirectRootRunner.kt:20`、`runtime/ubuntu/DirectRootRunner.kt:77` | 已相当 | 无需改动；只在新增标记输出时同步调整捕获上限 |
| 20 | 流式文件复制上限 | 不信任提供方声明的大小，越界即抛错（`agent/device/BoundedFileCopy.kt`） | 图片导入已有等价上限与异常类型（`ui/DisplayBitmapLimits.kt`） | 已相当 | 无需移植 |
| 21 | Root 管理器探测与安装预检 | 探测管理器家族、预检枚举、密封结果类型（`systemizer/GoogleAppSystemizerInstaller.kt:23`、`:29`、`:35`、`:204`） | 未探测管理器家族；rootfs 安装走自有流程并有进度与健康码 `ui/sandbox/RootfsManagementViewModel.kt:17`、`:52` | 缺失（仅探测部分值得移植） | 只移植「管理器身份 + 前置条件」作为诊断事实；模块化安装器本身不移植 |
| 22 | 入口接管决策 | 目标枚举加纯函数决策（不配置、配置为自管、恢复原厂）加幂等校验（`hook/system/AssistantBinding.kt:14`、`:39`、`:48`） | 已有默认助手角色可用性与持有状态、申请入口，回前台即刷新 `ui/settings/SettingsScreen.kt:147`、`:153`、`:180` | 更弱 | 抽出纯决策函数并补「放弃时恢复原助手」的分支与幂等判定 |
| 23 | 浮层可见性策略 | 纯函数按工具名白名单决定是否显示，并为会抢占前台的工具定义退场（`agent/overlay/AgentOverlayVisibilityPolicy.kt:65`、`:89`、`:28`） | 已有前台、开关、权限、占用四项条件与门控，并输出类型化结果 `service/AgentForegroundService.kt:399`、`service/AgentForegroundService.kt:455`、`service/ToolOutcome.kt` | 已相当 | 保持；若要补齐，只需把「前台工具集合」显式化并与 `tools/android/AndroidAgentTools.kt:56` 的动作表对齐 |
| 24 | 浮层完成态文案 | 完成预览截断 320 字符并只保留一句话状态（`agent/overlay/AgentOverlayState.kt:150`、`:30`） | 无模型前预览内容，仅工具标签与完成词 `service/ToolOverlayController.kt:474` | 已相当（口径不同） | 可选：若将来加入回复摘要，沿用 320 字符上限 |
| 25 | 屏幕像素尺寸解析 | 解析 `wm size` 的 override 才与输入坐标系对齐（`agent/device/AndroidDisplaySizeParser.kt`） | 手势与点击坐标来自无障碍节点边界，本就是当前逻辑坐标系 | 不该移植 | 不引入 Root 侧尺寸解析；保持坐标来自节点边界 |

## 3. 可移植规格

以下每项都按「状态机或不变量 → 失败与拒绝路径（fail-closed 否定用例）→ 边界上限 → 落地位置 → 单元测试」给出。
所有条款都要求按本仓库风格重新实现，不照搬 Eta 代码。

### 3.1 GUI 动作证据与超时语义（对照表 #11、#12、#13、#14）

**状态机与不变量**

- 一次滚动动作产出 `ScrollEvidence`：`MOVED_BY_EVENT`、`MOVED_BY_ANCHOR_MOTION`、`DIRECTION_MISMATCH`、`AT_BOUNDARY`、`UNVERIFIED`。
  方向语义沿用 Eta 的定义：方向表示期望出现的新内容所在方向，与手指移动方向相反；实现为带符号的显式枚举，不要用布尔参数表达方向。
- 手势桥状态机为 `PENDING → RUNNING → FINISHED | CANCELLED`；只有从 `PENDING` 成功转入 `RUNNING` 的任务才允许真正派发。
- 不变量：任何「成功」结论都必须携带证据枚举；没有证据时结论必须是 `UNVERIFIED`，而不是成功。

**失败与拒绝路径（否定用例）**

- 手势等待超时 → 结果既不得为「未派发」也不得为「成功」，必须是未知；且后续到达的派发请求被取消位拒绝。
- 用户请求纵向滚动，但目标节点只暴露横向 action → 拒绝坐标兜底并返回轴不符错误，而不是改用手势。
- 事件时间早于观测窗口起点、包名或窗口不匹配、窗口已过期 → 观测被忽略，证据保持未验证。
- 节点声明无法继续滚动且位移为 0 → 记为到达边界，既不报错误也不报成功位移。

**边界上限**

- 观测窗口有效期取秒级短窗口；一次动作只允许一个未结束的观测，新动作必须先结束旧观测。
- 锚点位移推断至少需要 2 个有效锚点，且多数方向与容差范围内一致才给出结论，否则返回无法判定。

**Minis 落地位置**

- 新增纯策略文件：`src/android/app/src/main/java/com/openminis/app/tools/android/AndroidUiActionEvidence.kt`
- 改造：`tools/android/AndroidUiController.kt:218`（节点滚动返回值）、`tools/android/AndroidUiController.kt:239`（坐标滚动）、
  `accessibility/MinisAccessibilityService.kt:163`（手势桥状态机与取消位）。

**单元测试清单**（`src/android/app/src/test/java/com/openminis/app/tools/android/AndroidUiActionEvidenceTest.kt`）

- 方向相反 → 方向不符；位移为 0 且到达边界 → 到达边界；位移为 0 且未到边界 → 未验证。
- 只暴露相反轴 → 拒绝兜底；两轴都有 → 允许。
- 手势桥：超时后状态为非运行，后续派发被拒绝；已完成后的重复派发被拒绝。
- 观测门：过期、窗口不匹配、时间早于起点三种情况均不接受证据。

**必须真机验证**：小米家族与 ColorOS 家族 ROM 上的滚动事件可靠性、WebView 内滚动的位移语义、手势在系统手势冲突下的超时表现。

### 3.2 截图与观察的窗口一致性与未知语义（对照表 #9、#10）

**状态机与不变量**

- 每个窗口给一个决策：捕获、排除、未知阻断。
- 不变量：参与观察或截图的窗口集合，必须与「真正接收触摸」的窗口集合一致；自身浮层永远不进入集合。
- 不变量：查询失败产生未知状态，未知不等于已消失；带排除集合时未知直接拒绝该次观察。
- 不变量：截断快照不得作为权威指纹；截断代际只允许重新观察，不允许继续解析旧引用。

**失败与拒绝路径（否定用例）**

- 排除集合非空且包名解析不出，而窗口可点击或聚焦 → 该窗口阻断，动作不执行。
- 自身包名的无障碍浮层 → 排除，不进入节点枚举。
- 截断代际上调用引用解析 → 拒绝并提示重新观察；指纹只覆盖前 N 个节点时同样拒绝沿用。
- 窗口查询超时 → 结果标记为未知，禁止据此判断应用已退出。

**边界上限**

- 节点数与深度上限沿用现有取值（`tools/android/AndroidUiController.kt:71`、`tools/android/AndroidUiController.kt:72` 的深度 0..30、节点数 1..500）。
- 指纹覆盖节点数上限可保持 2000，但超限必须显式记为截断并传播到栅栏。
- 排除集合大小设上限并去重，不接受任意长度输入。

**Minis 落地位置**

- 新增纯策略文件：`src/android/app/src/main/java/com/openminis/app/accessibility/AgentWindowPolicy.kt`
- 改造：`accessibility/MinisAccessibilityService.kt:121`（根节点收集）、`accessibility/MinisAccessibilityService.kt:156`（三态可见性）、
  `tools/android/AndroidUiObservationRegistry.kt:128`（把截断标志交给栅栏）、`tools/android/AndroidUiController.kt:84`（截图前应用策略）。

**单元测试清单**（`src/android/app/src/test/java/com/openminis/app/accessibility/AgentWindowPolicyTest.kt`）

- 自身浮层 → 排除；配置的排除包 → 排除；普通应用窗口 → 捕获。
- 排除集合非空加包名不可解析加窗口活跃 → 阻断。
- 排除集合为空加包名不可解析 → 保持捕获（避免误伤正常桌面与输入法窗口），此条需要与观测效果一起复核。
- 栅栏侧：截断代际解析失败；完整代际指纹变化解析失败；完整且未变解析成功（沿用 `tools/android/UiGenerationFence.kt:18` 的既有测试风格）。

**必须真机验证**：输入法窗口、系统对话框、其它应用无障碍浮层在真实设备上的包名解析结果，以及截图是否包含这些窗口。

### 3.3 流式 Markdown 未闭合结构投影（对照表 #1、#2）

**不变量**

- 投影只用于渲染：虚拟补齐的字符不得写回消息，也不得进入最终快照。
- 终态交付必须校验「投影输入等于原始内容」，否则放弃投影结果并重新解析真实内容。
- 同一段文本的节点类型在流式期间只能稳定或细化，不能从富文本退回字面量。
- 已确认开始的围栏、行内代码与强调从第一次可判定起就保持同一类型。

**失败与拒绝路径（否定用例）**

- 投影结果长度为零或比原文更短 → 放弃投影并返回原文解析结果（宁可不补齐也不能丢字符）。
- 非追加式输入（文本被上游改写）→ 重建基线，不沿用旧状态。
- 疑似表头行但分隔行不合法 → 不发布候选表头，也不把它当作正式表格。
- 未闭合链接 → 从投影中截掉，避免把半截 URL 显示给用户。

**边界上限**

- 投影是单次线性扫描，不得引入二次复杂度；尾部分割余量沿用现有 256 字符常量（`ui/chat/StreamingMarkdownText.kt:3292`）。
- 投影必须在现有节流分档之内完成（`ui/chat/StreamingMarkdownText.kt:530`），不得随 token 频率增长。
- 投影不得改变最终解析缓存键，避免污染已冻结块的缓存。

**Minis 落地位置**

- 新增纯函数：`src/android/app/src/main/java/com/openminis/app/ui/markdown/StreamingMarkdownProjection.kt`
- 接入：`ui/chat/StreamingMarkdownText.kt:558`（流式解析入口）；未闭合状态扫描复用 `ui/chat/StreamingMarkdownText.kt:3293`。

**单元测试清单**（`src/android/app/src/test/java/com/openminis/app/ui/markdown/StreamingMarkdownProjectionTest.kt`）

- 未闭合围栏补齐后再补上真实闭合符，节点类型不变；终态内容与原文一致。
- 疑似表头行加合法分隔行 → 建表；疑似表头行加非法分隔行 → 不建表。
- 未闭合链接与未闭合图片语法 → 不显示半截目标；闭合后显示链接。
- 未闭合粗体、下划线、删除线与行内反引号 → 类型稳定；已转义标记不参与配对。
- 非追加式输入（前缀被改写）→ 投影基线与进度重置，不产生残留虚拟字符。
- 性能断言：投影为单次线性扫描，可用字符数增长时的调用计数断言，参照 `src/android/app/src/test/java/com/openminis/app/ui/chat/SafeInlineSplitOffsetTest.kt` 的写法。

**必须真机验证**：真实模型流式输出（含中英混排、超长表格、围栏代码）在设备上的视觉连续性与滚动跟随。

### 3.4 Root 拒绝与超时语义及管理器身份（对照表 #17、#18、#21）

**状态机与不变量**

- 状态机至少把「探测超时」与「授权失败」拆开：找不到 su、需要授权、探测中、已授权、授权失败、授权被拒绝、探测超时。
- 不变量：被动探测不启动 su；只有显式申请会触发授权弹窗；首次自动申请只发生一次。
- 不变量：只有「脚本已结束且授权标记缺失」才允许标记为拒绝；命令自身非零退出不得改变授权状态。
- 不变量：uid 0 不等于拥有全部能力位（延续 `tools/android/PrivilegedCommandRunner.kt:177` 与
  `tools/android/AndroidCapabilityResolver.kt:37` 的既有判定）。

**失败与拒绝路径（否定用例）**

- 标记缺失但进程仍在运行（超时）→ 结果必须是超时，不得标记拒绝。
- 标记存在但命令退出码非零 → 结果是执行失败，授权保持有效。
- 超时且无法确认是否已执行 → 结果必须表达未知，不得表达「未执行」。
- 未探测到管理器家族 → 返回未知身份，不猜测，也不影响其余能力判定。

**边界上限**

- 探测超时上限沿用现有 15 秒探测与 30 秒默认动作上限（`tools/android/PrivilegedCommandRunner.kt:355`、`runtime/ubuntu/DirectRootRunner.kt:54`）。
- 标记输出计入现有捕获上限（1 MiB 字符），新增标记不得挤掉命令本身输出。
- argv 契约保持不变：32 个参数、单参数 4096 字节（`tools/android/PrivilegedCommandRunner.kt:315`、`:316`）。

**Minis 落地位置**

- 新增纯逻辑文件：`src/android/app/src/main/java/com/openminis/app/tools/android/RootCommandEnvelope.kt`（仅构造与判定，不执行）
- 改造：`runtime/ubuntu/DirectRootRunner.kt:59`（封装执行入口）、`tools/android/PrivilegedCommandRunner.kt:129`（状态枚举）、
  `tools/android/AndroidCapabilityResolver.kt:37`（暴露管理器身份与超时事实）。

**单元测试清单**（`src/android/app/src/test/java/com/openminis/app/tools/android/RootCommandEnvelopeTest.kt`）

- 有标记且退出码 0 → 成功；有标记且退出码非零 → 失败且授权不变。
- 无标记且进程已结束 → 拒绝；无标记且超时 → 超时。
- 标记文本出现在命令自身输出中 → 只按 token 判定，不误判。
- 状态机：首次自动申请一次；拒绝后自动路径不再申请；显式申请仍可触发；探测超时进入超时态。
- 管理器身份解析：可识别家族；无法识别时返回未知且不抛异常（参照既有 `src/android/app/src/test/java/com/openminis/app/tools/android/RootProbeParserTest.kt` 风格）。

**必须真机验证**：不同 Root 方案在拒绝、超时、授权缓存下的实际 stderr 行为，以及 su 被包装器替换时的标记完整性。

### 3.5 显现与淡入进度的单调不变式（对照表 #3、#4）

**不变量**

- 进度必须单调不减。当渲染文本因行内标记闭合而变短或错位时，进度只做钳制，绝不回退。
- 字素边界索引在只追加输入下做增量更新；只有最后一个旧字素与新增后缀需要重算。
- 一条回答使用一条时钟；同一帧的推进量按源码顺序分配，短块不占用整帧。
- 页面不可见或节点未挂载时直接追平到目标，不补播历史动画。
- 高度增长只在显现跨入新行时触发一次测量，字符级推进不触发重组。

**失败与拒绝路径（否定用例）**

- 输入非追加式（上游改写、消息切换）→ 丢弃在途区间并重建索引，不得产生负向进度。
- 文本长度回退 → 进度钳制到新目标，旧字符不重新显现。
- 目标文本为空或边界为空 → 进度为 0，绘制回退为直接绘制内容。
- 帧间隔异常大 → 单帧推进仍受上限约束，不产生突跳。

**边界上限**

- 单帧时间上限沿用 0.05 秒；速度下限 36 字素/秒，追赶目标 0.20 秒。
- 在途单词数上限沿用现有 160（`ui/chat/StreamingFade.kt:64`），超限直接追平。
- 中间态渲染不得新增分配：优先复用单块绘制路径，不重建整段富文本。

**Minis 落地位置**

- 新增纯算法文件：`src/android/app/src/main/java/com/openminis/app/ui/chat/RevealProgress.kt`（字素边界索引与推进函数）
- 改造：`ui/chat/StreamingFade.kt:89`（把硬重置改为钳制并重建索引）。

**单元测试清单**（`src/android/app/src/test/java/com/openminis/app/ui/chat/RevealProgressTest.kt`）

- 只追加输入：增量边界索引结果等于全量重算结果。
- 组合音标、ZWJ emoji、旗帜、CRLF 追加时，最后一个字素被延长后边界仍然正确。
- 文本因标记闭合变短：进度不回退，不出现负推进。
- 非追加式输入：返回重建结果，不沿用旧边界。
- 速度函数：积压量增长时速度单调不减；单帧推进不超过上限；积压清空后回到下限速度。

**必须真机验证**：中低端设备上的帧率与滚动跟随、长回答末尾的追赶观感、后台转前台后是否出现补播。

**纯 UI 部分的验证方式**：本项的进度与索引是纯算法，可在 JVM 单测覆盖；绘制与测量属于 Compose 行为，
需要界面测试或录屏对比（见 §6），不能用单测替代。

### 3.6 健康与接线状态统一账本及聚合卡片（对照表 #6、#7）

**状态机与不变量**

- 账本条目状态：通过、缺失、失败、跳过，每条必须带稳定 id 与原因文本。
- 不变量：账本必须遍历全部前置条件，不得在首个失败处短路；首个失败仍要作为整体结论保留。
- 不变量：id 规则固定（小写字母数字与点号、下划线、连字符），重复 id 视为错误并显式记录。
- 聚合卡片只做映射：非通过条目计数加前若干条加点击进入既有权限页，不引入第二套权限真源。

**失败与拒绝路径（否定用例）**

- 单个前置探测抛异常 → 该条记为失败并继续检查其余条目，整体结论仍为不可用。
- 前置条件顺序被破坏（在未通过项之后出现依赖项）→ 记为跳过并说明原因，不得记为通过。
- 聚合卡片数据源不可用 → 显示未知状态，不得默认显示「全部正常」。

**边界上限**

- 卡片只展示前 3 条非通过项（沿用 `ui/components/PermissionHealthCard.kt:66` 的口径）。
- 账本条目数量设上限；单条原因文本截断，避免长错误串撑爆界面与日志。
- 账本生成频率不得高于现有权限页刷新节奏（`ui/settings/SystemPermissionsScreen.kt:70` 的每秒轮询）。

**Minis 落地位置**

- 新增：`src/android/app/src/main/java/com/openminis/app/diagnostics/HealthLedger.kt`（纯数据与判定）
- 新增：`src/android/app/src/main/java/com/openminis/app/ui/components/HealthSummaryCard.kt`
- 改造：`runtime/ubuntu/UbuntuKernel.kt:115`（短路改为累积账本，保留原有失败优先级）、
  `ui/settings/SettingsScreen.kt:379`（挂载聚合卡片入口）。

**单元测试清单**（`src/android/app/src/test/java/com/openminis/app/diagnostics/HealthLedgerTest.kt`）

- 全部通过 → 整体结论可用且计数为 0。
- 中间一项失败 → 后续项仍被评估，整体结论不可用，失败原因保留。
- 依赖项未通过 → 后继项记为跳过而非通过。
- 重复 id → 记录为错误条目。
- 异常路径 → 记为失败且不抛出。
- 聚合映射：非通过条目多于 3 条时只取前 3 条；无数据时显示未知。

**必须真机验证**：真实设备上前置条件集合是否完整（尤其 SELinux 状态、命名空间能力、OEM 权限限制），以及卡片文案是否与实际阻塞原因一致。

## 4. 必须真机验证的点（不得据此声称已验证）

以下全部为未验证项，本文件只做静态源码对照：

1. 无障碍服务的连接、断开与被撤销时序，以及 OEM 在绑定失败时的表现（对应 §3.1、§3.2）。
2. 手势派发在系统手势冲突、输入法弹出、屏幕旋转期间是否会出现「超时后仍执行」（对应 §3.1 的取消门设计）。
3. 滚动事件在不同 ROM、不同应用（尤其 WebView 与自定义滚动容器）中的可靠性与方向语义（对应 §3.1）。
4. 截图与节点观察在存在输入法、系统对话框、其它应用无障碍浮层时实际包含哪些窗口（对应 §3.2）。
5. 指纹栅栏在超长节点树上的截断行为与误判率（对应 §3.2）。
6. Root 授权拒绝、超时、授权被回收时各主流管理器的实际 stderr 输出与本文件的标记判定是否一致（对应 §3.4）。
7. Root 管理器身份探测在真实设备上能否稳定识别，以及无法识别时的降级表现（对应 §3.4）。
8. 流式 Markdown 投影在真实模型输出上的观感与滚动跟随，以及是否引入额外掉帧（对应 §3.3）。
9. 显现与淡入在低端设备上的帧率、长回答末尾追赶、后台回前台是否补播（对应 §3.5）。
10. 启动前置账本在真实设备上的条目完整性，以及 SELinux、命名空间、OEM 限制导致的失败是否都能被归类（对应 §3.6）。

宿主机（JVM）单元测试只能覆盖纯算法与纯策略部分；上述任何一条都不能用单测结果替代真机结论。

## 5. 「不建议移植」清单与理由

| 对象 | 位置 | 不移植理由 |
|---|---|---|
| 系统级 Hook 框架与进程注入机制 | `hook/` 目录整体、`ModuleMain.kt` | 依赖自有 Hook 框架与厂商进程注入，本仓库不引入该机制；只吸收它的「接线状态账本」语义（§3.6） |
| 厂商定制入口的内部实现 | `hook/hyperos/`、`hook/breeno/`、`hook/xiaoai/`、`hook/colordirect/`、`hook/aimemory/` | 属于 OEM 内部类与私有方法，跨版本不稳定且无法在本仓库复现验证；只借鉴「找不到目标就跳过并记录原因」的失败语义 |
| 第三方应用模块化安装器 | `systemizer/GoogleAppSystemizerInstaller.kt` 的安装命令分支 | 本项目的 Root 只用于 App 自有的运行时基础设施；把任意模块安装纳入产品路径会扩大 Root 面，与本仓库安全合同冲突。只保留管理器身份探测 |
| 通过特权系统组件写安全设置的无障碍保护协议 | `agent/accessibility/AccessibilityProtectionProtocol.kt`、`agent/accessibility/AccessibilityProtectionClient.kt` | 需要一个随系统镜像分发的特权组件；本仓库没有该载体。现有 Shizuku 路径带显式用户确认（`accessibility/AccessibilityRecoveryManager.kt:341`），在同意模型上更清晰 |
| Root 侧屏幕位移推断契约 | `agent/device/RootScrollMotionContract.kt` | 依赖 Root 截屏连续采样与根侧输入重放，超出本仓库 Root 只做受控基础设施动作的边界（§3.1 只保留证据枚举本身） |
| 屏幕像素尺寸解析与前台窗口文本解析 | `agent/device/AndroidDisplaySizeParser.kt`、`agent/device/FocusedWindowParser.kt` | 本仓库坐标来自无障碍节点边界，不混用 Root 截图像素；引入 Root 侧解析只会增加 Root 面。前台包判定沿用无障碍 API 并补三态语义即可 |
| 显现动画的绘制实现（自定义布局节点加路径裁剪加逐帧图层合成） | `ui/components/SmoothTextReveal.kt:305` 起的绘制部分 | 与本仓库的文本分片、按块缓存与列表结构冲突，移植成本高于收益；只移植 §3.5 的算法与不变式 |
| 浮层内容与手势指示器 | `agent/overlay/AgentOverlayContent.kt`、`agent/overlay/GestureIndicator.kt`、`agent/overlay/AgentHapticFeedback.kt` | 本仓库已有基于窗口管理器的胶囊浮层与类型化结果（`service/ToolOverlayController.kt`、`service/ToolOutcome.kt`），重复实现会造成两套状态源 |
| 终端模块的发行版安装器 | `agent/terminal/` 目录中的发行版安装脚本 | 不引入 PRoot 方案，也不引入 Alpine 方案；本项目 guest 固定为 Ubuntu 24.04 direct chroot，多发行版安装路径与合同冲突 |

上表列出的条目都是**架构、合同或验证条件**上的取舍，不是许可限制：PolyForm Noncommercial 1.0.0 允许
复制与派生，本仓库也确实直接复用了其中一部分实现。不搬运的原因只有两类——重复实现会造成两套状态源，
或者超出本仓库 Root/存储/安全合同允许的范围。

## 6. 验证方式

- 文档一致性：`python3 scripts/check_docs_provenance.py`。
- 纯算法与策略单测：`cd src/android && ./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1`。
- 界面相关：`src/android/app/src/androidTest/` 下的现有界面测试与录屏对比；纯 UI 变更不得只用 JVM 单测宣称通过。
- 真机部分：按 §4 清单在真实设备上单独留存证据；Root、SELinux 与 OEM 生命周期相关结论不使用宿主测试替代。
