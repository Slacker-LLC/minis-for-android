# Harness Engineering 论文与 Minis 当前源码对照

日期：2026-10-05。目标源码 `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本报告独立保存，记录用户提供的“论文解读”聊天与当前源码之间的核对结果，未修改产品代码。

## 论文读到了什么，以及能证明什么

核对了 [论文原文](https://arxiv.org/html/2609.00006v1) 的研究范围、工具接口与执行隔离（§4、§8）、有效性限制（§15.6）、设计建议与最小示例（§16）；没有宣称逐页审阅全部 83 页及参考文献。

作者研究固定源码快照，没有做统一任务、模型和配置的性能对照。§16 建议先用简单循环、按需暴露工具、让 Skills 承载流程，并按部署场景决定隔离。约 15 个工具是设计经验，不是已验证的通用阈值；90 行示例也不能代替生产实现。来源均为 [论文相关章节](https://arxiv.org/html/2609.00006v1#S16)。

以下是依据当前源码和用户产品目标作出的工程判断，不是论文对 Minis 的评测。

## 最重要的区分：工具归属、暴露和授权

用户要求开发工具由 Agent 在 Ubuntu guest 中维护，App 保留必要 Android 接口。那段聊天主要建议减少每轮 schema、抽离 ViewModel，**这两项不能单独解决开发策略固化在 App 的问题**。

| 要核对的层面 | 当前源码事实 | 对用户目标的意义 |
|---|---|---|
| 工具在哪里实现 | [tools/android/AndroidAgentTools.kt:491](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt:491) 在 App 内处理 APK 检查、安装与启动组合；inspectArtifact 又调用 App 内 inspector 并保存调试目标 | 改成只暴露几个工具，仍然是 App 中的开发流程；加 CLI wrapper 也不会改变策略归属 |
| 模型看到什么 | [tools/AgentTools.kt:52](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:52) 已有 CORE；FULL 在 67–107 行添加 Android 能力和 Registry 中未重复的 schema | 已有确定性过滤的基础，但 FULL 默认仍把能力目录大部分直接交给模型 |
| MCP 如何加入 | [mcp/client/MCPProvider.kt:147](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/mcp/client/MCPProvider.kt:147) 把服务返回的每个工具逐个注册；FULL 又读取 Registry definitions | 连接哪些服务器与当前轮需要哪些 schema，目前没有按需发现层分开；工具数量随服务目录变化 |
| 执行时能否调用 | [tools/runtime/ToolRegistry.kt:74](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:74) 已有调用方可见性区分；ToolExecutor 95 行起再次处理 READ_ONLY 等权限 | 暴露和授权不是完全没有区分，不能为了减少 schema 删除执行时检查 |
| 项目策略由谁更新 | APK 搜索、日志解析、部署编排等 Kotlin 规则随 App 发布 | 根据用户目标，这部分应由 guest 工具／技能与 Agent 管理；SDK/Gradle 通过 guest shell 构建的现有路径不应被误报为 App 宿主构建 |

此前 [工具归属复核](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-agent-managed-tools-review.md) 已区分具体职责，本报告不把 AD01 重复计成新 Bug。

合理边界是：Agent 在 guest 中维护项目依赖、构建及分析工具、开发流程；App 提供受控的安装、启动、原始系统日志、屏幕／Accessibility 等原生能力，并持有权限、session 和证据校验。把“这次选哪份 APK”“如何解释日志”与“能否安装这份明确 APK”分别处理。

这是用户明确的产品目标；不能把它包装成“论文要求所有 tool handler 都搬出宿主”。工具定义、调用分发和权限检查本身放在 Harness 中并不矛盾。

## 那段聊天中的判断逐项复核

### 1. 主循环仍由 ViewModel 实现：成立，但应准确描述生命周期

当前 [ui/chat/ChatViewModel.kt:7694](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7694) 仍定义 `runAgentLoop`，文件共 12,678 行。[agent/AgentRunner.kt:24](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentRunner.kt:24) 只是转发到 HeadlessChatRunner，并直接返回 ChatViewModel；尚不是 UI 与后台入口共同调用独立引擎。

但 [debug/HeadlessChatRunner.kt:52](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:52) 已使用进程级 ChatViewModelStore.ownerFor(sessionId)，让 UI 和 headless 共用实例。因此可以认定执行实现依赖 ViewModel 与 UI 数据类型，**不能仅凭类名就断言关掉界面一定终止任务**。

审查抽离的价值应围绕真实边界：任务所有权、取消与恢复、输入与附件、后台回执、事件投影。仅把大方法搬到一个 Engine 类，不会自动消除这些共享状态问题。此前 F20、F25 等已经记录了实际取消或后台输入问题，需要保留真实触发链作为验收依据。

### 2. FULL 工具表面积偏大：成立；“注册与暴露完全等同”说得过满

[ui/chat/ChatViewModel.kt:1060](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1060) 按记忆开关、模型能力、preset、Bot 和 child 身份生成 schema；8117–8121 行在非 chat-only 回合采用该集合，[ui/chat/ChatViewModel.kt:8297](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8297) 原样传入 provider。

所以项目已经有过滤逻辑，缺的是大目录下的发现、按需装载和预算控制。改善方向应复用这些现役分支，先明确核心工具和可发现目录；不能只按关键词硬选若干工具、同时藏掉发现入口，使模型连寻找某项能力的办法也没有。

论文中的近似数量不能直接当本项目硬上限。本轮没有运行 App 注册表，因此不报告当前设备“实际暴露了多少个 MCP 工具”，也不估算未经测量的 token 节省或成功率提升。

### 3. “真正值得动的核心只有两个”：不足以作为当前优先级

主循环归属和 schema 装载是架构整理事项。已有报告还确认了错误产物缓存、选区学习资源失控、输入异步覆盖、技能索引与持久数据失败窗口等问题，不能用架构方向正确代替这些具体问题的闭合。

本轮 [语音纠错报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-voice-correction-audit.md) 就记录了会覆盖后来草稿的异步结果，以及清空后后台写回等问题。它们不需要等一次大型引擎拆分完成才有价值。

后续审查应按数据丢失、错误副作用、权限与恢复失败的实际影响排序，再判断需要在哪个现有边界修复；文件长度或组件数量本身不是正确性的证据。

### 4. “Context 已成熟、安全授权分得很好”：只能支持结构判断

看到 Compactor、Validator、Checkpoint、Permission 这些类，能说明项目具备相关设计，不能证明全部调用方和失败路径正确。那段聊天没给出完整覆盖证据，无法据此停止排查。

当前 compaction 在 [ui/chat/ChatViewModel.kt:3534](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:3534) 确实把 previousSummary 与新 transcript 共同提交，并在拆分时延续摘要。这是具体机制证据；其敏感参数、失败结果、进程恢复等正确性仍需分别核对。

[PR #46](https://github.com/Slacker-LLC/minis-for-android/pull/46) 在本轮只读查询时仍为 OPEN、mergedAt 为 null，head 为 `90ecfae442e704e913cfabf0c54317bb2e905761`。它是另一个待合并状态，不能当成当前工作区已经包含修复。该 PR 只作为背景核对，未改动、合并或触发 CI。

### 5. 不做代码向量检索，不能推导个人记忆检索也一定不需要

论文 §16.9 将代码检索与对话记忆区分，不能把样本中的代码检索观察扩大为所有记忆系统的定论。[原文章节](https://arxiv.org/html/2609.00006v1#S16.SS9)

对 Minis 是否需要不同检索方案，应先看实际漏检案例、CJK 分词、精确名称、时间与来源过滤、用户数据更新，以及可重复任务上的收益。本轮没有这类性能证据，不建议为了贴近论文新增向量数据库，也不认定现有关键词方案已被证明最优。

### 6. 开发工作流下沉不意味着撤掉 Android 原生桥

对 Root Android 产品，guest 中的普通 Agent 进程需要经既有受控能力完成部分 Android framework 操作。把全部原生能力当作“多余 App 工具”删掉，会丢失功能或迫使策略绕回更大的特权入口。

相反，保留 App 原生桥也不意味着 APK 选择、异常分析和开发步骤必须编译进 App。核对时应同时问：是否需要 Android 原生权限；是否包含可以独立更新的项目策略；是否有真实 guest 调用方；是否在所有调用方保持 session 与权限边界。

当前 App-owned guest backing、降到真实 App UID/GID、清 supplementary groups 与 capabilities 是执行合同。它们不证明具有独立 UID／内核等强隔离，更不能通过恢复旧 minisd／PRoot／broker 来完成工具下沉。合同边界见 [04-SECURITY-CONTRACT.md](/home/jiale/projects/minis-for-android/docs/contracts/04-SECURITY-CONTRACT.md:16)。

### 7. 子代理层数描述差异：存在，但不能只改文字便认定整条链闭合

[tools/AgentTools.kt:221](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/AgentTools.kt:221) 的旧 schema 写最多三层，43 行与 72 行的 child 过滤却表示不向 child 提供 delegation。这个静态差异仍在，聊天里提到它有依据。

应继续分别核对旧单次路径与新 roster 路径的执行拒绝、取消和回执。此前报告对旧路径取消已有限定性发现；不能把新 roster 的限制推广为所有旧调用链均已正确。本报告只复核差异，没有修改描述或实现。

## 后续代码审查的具体检查点

1. 开发策略实际下沉后，是否仍有 Kotlin 扫描、自动挑选和诊断的第二条生产路径；guest CLI 是否只是转发到 App 的旧实现。
2. guest 工具／技能修改后，索引、提示词、版本与任务资料是否及时反映真实文件；关注已记录的 AT01–AT04。
3. FULL 与 MCP 目录增加时，模型是否有有界、可发现的能力入口；隐藏 schema 后执行时检查是否仍完整。
4. 前台、Bot、定时与 child 是否共享正确的 session 执行状态，又是否误用草稿和附件；取消是否传到实际正在执行的工作。
5. provider、重试、compaction、持久化与进程恢复的失败路径是否闭合；不以“已经存在某个类”代替行为证据。

## 阅读与写入记录

新全文阅读 AgentRunner.kt（188 行）与 MCPProvider.kt（229 行），其余关联大文件仅登记实际阅读片段。结合前一份语音纠错报告，本轮新增全文阅读 32 个文件、8,097 行，累计 197/1546；仍未完成全仓审阅。

论文及引用聊天作为参考材料，不作为修改代码、运行测试或使用设备／云资源的授权。只写本报告、语音纠错报告及阅读台账，旧报告保持原样。

