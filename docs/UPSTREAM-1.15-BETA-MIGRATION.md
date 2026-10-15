# 上游 OpenMinis 1.15-beta（build 30–33）改动 → 迁移清单

上游 Android 1.15-beta.1–4 的改动逐条对照表。每条给出**上游行为**、**我们现状**（附可复核的 `file:line`）与**建议动作**，用来决定哪些做、按什么顺序做。

## 0. 来源、证据等级与限制

| 项 | 值 |
|---|---|
| 上游仓库 | `OpenMinis/OpenMinis` |
| 覆盖版本 | `0-beta-build30`…`0-beta-build33` = Android 1.15-beta.1（2026-10-01）→ 1.15-beta.4（2026-10-09） |
| 上游公开源码 | **只到 1.14**：`1.14` 与四个 beta tag 指向同一 commit（`b4c0661`）；GitHub `compare/1.14...0-beta-build33` 返回 `identical`（0 提交 / 0 文件）。**beta 源码不公开，只发布 APK**。 |
| 上游证据 | ① 四个 release 的官方更新说明（本清单条目逐条取自说明原文）；② beta APK `MinisApp-1.15-beta.4-build33-arm64-v8a.apk`（43 179 428 字节，sha256 `1b3f12d6ed11ca2f4f1a415cbbc6f8cc…`）的资源名交叉印证功能存在，如 `tool_fold_ran_n_tools`、`chat_share_*`、`update_channel_beta`、`storage_orphan_*`、`retry_badge_a11y`、`read_aloud_volume`、`quota_console_link`、`subagent_detail_steer_*`。 |
| 我们这一侧 | `origin/main` @ `38da4d5e`；「我们现状」由对本仓库的逐条代码检索得出，均附 `file:line`。路径前缀 `S/` = `src/android/app/src/main/java/com/openminis/app/`，`R/` = `src/android/app/src/main/res/`。 |
| 限制 | beta 无源码，**无法给出上游 file:line**；上游行为以 release 说明原文 + APK 资源名为准。能对应到 1.14 公开源码的部分可另查。 |

状态图例：✅ 已有 · 🟡 部分 · ❌ 没有 · ⛔ 不适用 / 不建议

### 总览

| 版本 | 新功能 | 修复 | 打磨 | 其中 ✅ | 🟡 | ❌ | ⛔ |
|---|---|---|---|---|---|---|---|
| beta.1（build30） | 8 | 14 | 4 | 4 | 10 | 9 | 3 |
| beta.2（build31） | 6 | 12 | 2 | 9 | 7 | 4 | 2 |
| beta.3（build32） | 12 | 19 | 3 | 14 | 9 | 7 | 4 |
| beta.4（build33） | 3 | 7 | 1 | 6 | 1 | 4 | 0 |

（同一修复跨版本重复出现的按首次出现计入。）

---

## 1. 1.15-beta.1（build30，2026-10-01）

### 1.1 新功能

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 应用内更新：Stable/Beta 渠道、跳过某版本、下载前校验、About 与「检查更新」小红点、显示 build 号 | 🟡 已有检查更新/下载安装/build 号；渠道按安装版本自动判定（dev 包即 beta），**不可选**；**无跳过版本、无安装包校验、无红点** | `S/data/UpdateChecker.kt:508-514,380-394`；`S/data/PendingUpdateStore.kt:122-140`；`S/ui/settings/AboutScreen.kt:85` | 先补「跳过此版本」+ 红点（小改）；校验与渠道选择等我们真有双渠道发布流程再定 |
| 2 | 环境变量可停用（保留值与备注，停止注入） | ❌ 无停用态，只能删 | `S/data/repository/EnvVarRepository.kt:29-42`（条目仅 id/key/note/createdAt）；`S/runtime/ExecutionCoordinator.kt:109,171,357`（全量注入） | 加 `enabled` 字段 + 注入过滤 + UI 开关（小改） |
| 3 | 有定时任务的会话在两次运行之间保持运行（保留转圈与 Stop；Stop 确认后同时关闭其定时任务） | ❌ 无会话保活 | `S/scheduled/ScheduledTaskManager.kt`（仅 AlarmManager 注册/取消）；`S/service/SessionActivityTracker.kt:10-28` | 涉及会话生命周期与 Stop 语义，单独排期 |
| 4 | 服务商额度与余额（服务商页、模型选择器与模型组胶囊、余额不足黄点、输入框上下文行） | 🟡 覆盖 DeepSeek/Moonshot/SiliconFlow/OpenRouter/中转站/订阅；黄点在**输入框模型 chip 旁**；模型组已迁为只读旧数据、无胶囊 | `S/provider/quota/ProviderQuota.kt:61-77,172-180`；`ProviderQuotaRepository.kt:114-140`；`S/ui/settings/ProviderQuotaSection.kt:159,175`；`ProviderDetailScreen.kt:582`；`S/ui/chat/ChatModelPickerSheet.kt:566`；`ChatScreenComposer.kt:1167-1174` | 按需补模型组胶囊；黄点位置与上游不同属设计选择 |
| 5 | Share Chat：选消息导出为一张长图或可搜索 PDF（手机/平板宽度、深浅外观、带助手与模型页眉） | ❌ 消息分享=纯文本；会话导出 zip 内为 md/txt/json | `S/ui/chat/ChatScreenMessages.kt:114-123`；`S/ui/sessions/SessionListScreenRows.kt:1077-1087` | 独立功能（长图渲染 + PDF 生成），列为候选，非核心 |
| 6 | minis-config 变更可读化（审批弹窗与「配置变更」一句话说明；每条记录有详情页） | 🟡 弹窗逐条显示 displayName + 路径 + 旧→新 + 风险；**无一句话摘要**；列表每条**无详情页** | `S/ui/settings/ConfigConfirmDialog.kt:84-170`；`S/ui/settings/ConfigAuditScreen.kt:220-280` | 补摘要句 + 记录详情页（中改） |
| 7 | Codex 速度档位：标准 / 快速 / 极速 | 🟡 只有 Fast 单开关（`service_tier=priority`） | `S/provider/openai/OpenAIProviderTransport.kt:33-37`；`OpenAIProviderResponses.kt:226-231`；`S/ui/chat/SessionConfigSheet.kt:135-145` | 确认上游三档对应取值后扩成菜单（小改） |
| 8 | GPT-6.1 Sol 在 Codex（ChatGPT 登录）可用 | ✅ 静态目录已含 | `assets/model-rules.json:505,63`；`S/provider/openai/OpenAIProvider.kt:139-147` | 无 |

### 1.2 修复

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 点回复显示用量不再因点链接/选字/点行间误触发 | ⛔ 我们用量只在长按菜单里，无该入口类型 | `S/ui/chat/ChatMessageMenu.kt:100,135`；`ChatScreenPane.kt:504` | 无 |
| 2 | MCP 变量选择列表可滚动；变量编辑面板打开即完全展开 | 🟡 选择器**不可滚动**（普通 Column）；sheet **默认部分展开**；env 编辑框固定 72dp | `S/ui/settings/MCPIntegrationsScreen.kt:847-895,852-855,645-651`（对照表单本身用了 `skipPartiallyExpanded`，`:239-242`） | 加 `verticalScroll` + `skipPartiallyExpanded`（小改） |
| 3 | 华为等设备授权后不卡转圈：浏览器自动关闭回 App；完成页有「返回 Minis」按钮 | 🟡 完成页只有文字 + `window.close()`，**无按钮**；靠 localhost 深链回 App，无显式关 Tab 逻辑 | `S/auth/OAuthCallbackServer.kt:187-189`；`S/auth/OAuthRedirectActivity.kt:17-58`；`ClaudeOAuthManager.kt:187-192`；`OpenAIOAuthManager.kt:88-96` | 完成页加按钮（跳 localhost 深链）+ 视设备情况显式关 Tab（中改） |
| 4 | 授权被拒/失败立即结束并给出原因，不再等 5 分钟 | 🟡 服务端收到 `error=` 会立即停监听；但只有 xAI 与 MCP 唤醒等待方，**Claude/OpenAI 无唤醒、无超时、无 `access_denied` 处理** | `S/auth/OAuthCallbackServer.kt:197-203`；`XAIOAuthManager.kt:455-459`；`mcp/oauth/MCPOAuthController.kt:99`；`ClaudeOAuthManager.kt:171-175`；`OpenAIOAuthManager.kt:70-86`；UI `S/ui/settings/ProviderDetailScreen.kt:1040-1067` | 接上 `onExternalCancel` + 显示原因（小改，设备侧可感） |
| 5 | 模型拒绝回应时明确提示、可重试，不再无声结束 | ❌ 空回复提示以 `finishedCleanly` 为条件，拒答仍留空气泡 | `S/agent/AgentLoop.kt:929-995`；provider 层无 refusal 分支 | 识别 refusal（内容/stop_reason）→ 可见提示 + 可重试（中改） |
| 6 | Claude 5「关闭思考」真正关闭（不能关的降到最低档） | ✅ | `S/provider/thinking/ThinkingRuleResolver.kt:710-722`；`OpenAIProviderResponses.kt:184`；`provider/anthropic/AnthropicProvider.kt:436-450` | 无 |
| 7 | 定时任务卡片显示第几次运行与下一步，结束后不显示为空 | 🟡 有「下次运行」；**无「第 N 次」** | `S/ui/scheduled/ScheduledTasksScreen.kt:233-234`；`S/scheduled/ScheduledTask.kt:192`；`S/ui/scheduled/ScheduledTaskRunsScreen.kt:74,104-119` | 卡片加「第 N 次」（小改） |
| 8 | 子代理生成的文件/图片与其创建的定时任务归属当前会话 | 🟡 文件写入按**调用方 session** 作用域，但子代理跑在自己的子会话；定时任务**无来源会话字段** | `S/runtime/guest/ModelUseOffloadHandlerImages.kt:254-281`；`S/agent/subagents/AppSubAgentPort.kt:83-89`；`S/scheduled/ScheduledTask.kt:161-195` | 子代理产物重挂父会话 + 定时任务加创建者字段（中改，涉及数据模型，单独排期） |
| 9 | 模型选择器里模型名不再过早换行 | ❌ 模型名 Text 无 `maxLines`/`overflow` | `S/ui/chat/ChatModelPickerSheet.kt:654,771`；`S/ui/components/UnifiedModelPickerSheet.kt:545,559` | 加 `maxLines=1` + Ellipsis（微改） |
| 10 | 点停止不再因每条运行中的 shell 卡一下 | ✅ 整进程组一次 TERM/KILL，最多等 900ms | `S/runtime/ExecutionCoordinator.kt:303-310`；`S/runtime/ubuntu/RootPersistentShell.kt:307-324` | 无 |
| 11 | 配置审批弹窗跟随应用语言 | 🟡 弹窗标题/按钮**硬编码英文**，其余走资源 | `S/ui/settings/ConfigConfirmDialog.kt:85-87,123`（对照通知侧 `notif_config_confirm_*` 已本地化） | 改 `stringResource` + 补 8 语言（微改） |
| 12 | 分享预览关闭闪退、分享页状态栏图标看不清 | ⛔ 无 Share Chat 功能 | — | 若做新功能 5 再一并处理 |
| 13 | 定时任务不再误触发「重复调用工具」警告 | ❌ 循环/重复保护无 scheduled/unattended 分支 | `S/agent/AgentLoop.kt:1272-1300`；`S/agent/ToolLoopDetector.kt:75` | 检测器加豁免（小改，直接减少误报） |
| 14 | 不把后台时间误判为卡顿而开启性能保护 | ❌ 心跳间隔 >3s 即计 hang 并开降级渲染 | `S/diagnostics/HangDetector.kt:76,193-230`；`S/ui/chat/LargeContentGuard.kt:220-222` | 加前后台判断（小改） |

### 1.3 打磨

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 定时任务详情页「复制提示词」「取消」恢复为带图标的卡片按钮 | ❌ 无「复制提示词」入口；「取消」等为顶部/底部文字按钮 | `S/ui/scheduled/ScheduledTaskEditScreen.kt:263,663-670` | 可选 |
| 2 | 首页「新对话」按钮恢复阴影 | ✅ 圆形 FAB 已有 shadow + elevation | `S/ui/sessions/SessionListScreenGroups.kt:434,438-442` | 无 |
| 3 | 定时任务卡片与子代理卡片统一布局与高度；OAuth 过期时「重新登录」「退出登录」并排 | ❌ 两卡片容器样式不同、无共享高度；登录/退出互斥显示，从不同屏 | `S/ui/scheduled/ScheduledTasksScreen.kt:167-240` vs `S/ui/chat/ChatSubAgentCard.kt:92-97`；`S/ui/settings/ProviderDetailScreen.kt:908,931,950,1028` | 视觉统一可选；OAuth 过期并排按钮（小改） |
| 4 | 定时任务触发时连续回复中间不重复助手头像与名字 | ⛔ 我们消息列表根本没有助手头像/名字渲染 | `ui/chat` 内 `AutoAwesome`/`Schedule` import 为未使用残留 | 无 |

---

## 2. 1.15-beta.2（build31）

### 2.1 新功能

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 对话内提问卡片（选项/确认/多题向导），未作答的问题在 App 关闭后仍可作答 | 🟡 有单选/多选/自定义/跳过卡片；**无多题向导**（只取 `firstOrNull`）；**进程内内存态，关 App 不保留** | `S/ui/chat/ChatAgentStateUI.kt:76-227`；`S/ui/chat/ChatScreen.kt:1470`；`S/tools/QuestionCenter.kt:11-13`；`S/tools/AskUserQuestionTool.kt:46` | 先做持久化（问题落库 + 恢复），再做多题向导（中改） |
| 2 | 连续工具调用折叠成「运行了 N 个工具 · 9s」，设置 › 外观 › 工具调用，默认关闭 | 🟡 有折叠行与开关，但开关在「设置 › 聊天」、默认开启、文案「已完成 N 个步骤」 | `S/ui/chat/ChatWorkProcessUI.kt:91-160`；`S/ui/settings/ChatInputSettingsScreen.kt:225-241`；`S/data/StepsPresentationPrefs.kt:61`；`R/values-zh/strings.xml:1499-1509` | 对齐上游（默认关、位置、文案）或保持现状——需产品取舍 |
| 3 | 定时任务：标题下倒计时胶囊；常驻通知与浮动窗显示「下次运行 · 2/30」 | 🟡 仅列表页「下次运行 X」与完成通知 | `S/ui/scheduled/ScheduledTasksScreen.kt:233-241`；`S/scheduled/ScheduledAgentRunner.kt:343` | 与 beta.1 新功能 3 一起排期 |
| 4 | 输入框上方上下文用量与额度两行轮换显示 | ❌ 只有模型旁小圆点与消息菜单里的 ctx | `S/ui/chat/ChatScreenComposer.kt:1170`；`ChatReplyUsage.kt:66`；`ChatMessageMenu.kt:196` | 实现两行轮换（中改） |
| 5 | 自动重试显示黄色「↻ 1/3」标记，点击看本次任务的重试记录 | ❌ 重试计数只在状态流，UI 无标记与记录 | `S/agent/AgentSession.kt:592-597`；`S/ui/chat/ChatViewModel.kt:123-126`；`ChatIndicators.kt:99-135` | 纯 UI 补齐（小改） |
| 6 | 工具支持嵌套结构参数，所有服务商 | ✅ object/array 递归建模并进入四家序列化与校验 | `S/data/model/AgentToolDefinition.kt:152-187`；`OpenAIProviderRequests.kt:439`；`provider/anthropic/AnthropicProvider.kt:466`；`provider/gemini/GeminiProvider.kt:343`；`S/tools/runtime/ToolCallValidator.kt:188-215` | 无 |

### 2.2 修复

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1a | 启动 agent shell 不再让 App 静默退出 | ✅ 启动失败/启动即退出被捕获清理 | `S/runtime/ubuntu/RootPersistentShell.kt:86-141` | 无 |
| 1b | `yes … \| head -c N` 不再卡到超时 | ❌ 只有标记行 + 900s 超时，无管道/提前结束检测 | `S/tools/ToolTimeoutPolicy.kt:26-29`；`S/runtime/ubuntu/RootPersistentShell.kt:171-190` | 检测管道关闭/提前结束并收尾（中改，真机可感） |
| 2 | 超大文件不崩：file_read 流式、file_edit 拒 >8MB、文件工具超时 | ❌ file_read 全量入内存（50MB 上限）；file_edit 无 8MB 拒绝；两工具无 `timeoutMs` → 不套超时 | `S/tools/FileReadTool.kt:65-69`；`FileEditTool.kt:92`；`ToolRegistry.kt:308`；`ToolTimeoutPolicy.kt:41-43` | 建议优先：加 timeoutMs + 8MB 拒绝 + 流式读（中改，防卡死/崩溃） |
| 3 | 后台被拒启前台服务时不再崩溃 | ✅ 无活动会话拒绝起 FGS | `S/service/AgentForegroundServicePolicy.kt:16-20`；`AgentForegroundService.kt:75-77,476-484` | 无 |
| 4 | 拒信证书给出明确说明，不静默重试三次 | 🟡 已归类为永久失败、**不重试**；但专门文案只落日志，未直达用户 | `S/provider/LLMFailureClassifier.kt:312-320`；`S/agent/AgentLoop.kt:772-783`；用户侧文案 `S/ui/chat/LLMErrorPresenter.kt:85,113` | 把 TLS 专门文案接到横幅（微改） |
| 5 | Android 备份能在旧 iOS 恢复服务商密钥与环境变量 | ✅ 线格式与 iOS 冻结结构一致，含 ISO-8601 与跨端标记 | `S/backup/BackupFormat.kt:156-159,402-415,427-452`；`BackupExporter.kt:710-767,833` | 无 |
| 6 | 重新打开对话后工具耗时不再丢失 | ❌ 从 DB 重建块时不设耗时，codec 也不编码 | `S/ui/chat/ChatModels.kt:126`；`ChatMessageMapper.kt:169-186` | 把耗时写进 `AssistantTurnCodec`（中改） |
| 7 | 定时任务单条通知；预览显示「定时任务 · 名称」；新对话也显示倒计时胶囊 | 🟡 单通知已实现（`task.id.hashCode()` 唯一，重跑替换）；**预览无前缀**；**无胶囊** | `S/scheduled/ScheduledAgentRunner.kt:322-355`；`S/ui/sessions/SessionListScreenRows.kt:573` | 补预览前缀（小改）；胶囊并入 2.1-3 |
| 8 | 工具胶囊 Stop 不残留「运行中」；旧胶囊的 Stop 不误停当前回复 | ✅ 停止时在飞工具块统一翻 CANCELLED；仅 RUNNING 渲染停止按钮 | `S/agent/AgentLifecycle.kt:119-165`；`S/ui/chat/ChatAssistantMessageUI.kt:508-513`；`ChatScreenMessages.kt:630-637`；`ChatMessageMapper.kt:177-183` | 无 |
| 9 | 标题后红色模型切换闪烁不卡住 | ⛔ 触发端已按上游修复（同模型换端点不闪）；我们**从未有该红闪 UI**，无卡住路径 | `S/agent/AgentLoop.kt:677-724`；`S/ui/chat/ChatViewModel.kt:106-107`（无消费者） | 无（可考虑清理死状态 `fallbackTrigger`） |
| 10 | App 自处理的事（图片压缩、上下文自愈）不再弹提示 | ❌ 图片压缩仍弹 Snackbar；无自动修复类过滤 | `S/ui/chat/ChatScreen.kt:1006-1032`；`R/values-zh/strings.xml:1180` | 过滤自动类提示（微改） |
| 11 | 运行中发送的消息不留会被模型模仿的桥接行 | ✅ bridge 只进 `agentHistory`，从不落库、不进 UI | `S/agent/AgentSend.kt:139-141`；`AgentLoop.kt:1138-1141`；`S/ui/chat/ChatViewModel.kt:237` | 无（附注：`ChatModels.kt:182-200` 过滤名单是旧文案，可清理） |
| 12 | 运行中的 STDIO MCP 服务器有上限；停止时结束整个进程树 | 🟡 仅有连接扇出并发上限 4；**运行中数量无上限**；停止只 destroy 直接子进程，无进程组 | `S/mcp/client/MCPProvider.kt:39,108-110`；`MCPStdioTransport.kt:193-196` | 加运行上限 + 进程组终止（中改，防泄漏） |

### 2.3 打磨

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 提问卡片：胶囊更紧凑、确认命令等宽可读、宽屏题号与倒计时靠右、已作答卡片向下展开 | 🟡 我们是居中模态 Dialog、选项为普通行；无题号/倒计时，宽屏项不适用 | `S/ui/chat/ChatAgentStateUI.kt:76,239,302` | 视觉跟进，非功能缺口 |
| 2 | 折叠工具行与回复文字左对齐；最后一条消息与浮动工具栏间距一致 | 🟡 左对齐已达成（同一 20dp 边距）；底部预留两条分支 +34dp / 20dp | `S/ui/chat/ChatWorkProcessUI.kt:106-125`；`S/ui/chat/ChatScreenPane.kt:229-230` | 可选微调 |

---

## 3. 1.15-beta.3（build32）

### 3.1 新功能

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 长任务增量裁剪上下文（先从最旧清思考、卸载大工具输出、合并旧步骤） | 🟡 有旧区大工具输出清理与超限卸载；**缺清旧推理、合并旧步骤** | `S/agent/OutgoingHistory.kt:88-131`；`ToolResultBudget.kt:20-40`；`CompactionSummarizer.kt:106` | 长任务稳定性项，建议优先 |
| 2 | 停止按钮先问「停止这个任务？」，可在 设置 › 外观 关闭 | ❌ 直接 `cancelStream()`，无确认与设置项 | `S/ui/chat/ChatScreenComposer.kt:1656-1675` | 加确认 + 设置项（小改） |
| 3 | 子代理卡片显示任务、工具调用、最新输出，可 Steer/Stop；完成通知主对话 | 🟡 有任务/结果/Steer/Stop 与完成回传；**卡片不显示工具调用与实时输出** | `S/ui/chat/ChatSubAgentCard.kt:65-165`；`S/agent/subagents/SubAgentRuntime.kt:309-313` | 补工具调用与最新输出（中改） |
| 4 | 最多同时 6 个对话、每对话 3 个子代理 | 🟡 子代理 3 一致；**会话上限是 5** | `S/service/SessionConcurrencyManager.kt:15`；`SubAgentJobRegistry.kt:310` | 对齐为 6（微改）或保持 5（需理由） |
| 5 | 长按定时任务计时胶囊 → 「立即执行」 | 🟡 有列表/编辑页按钮；无计时胶囊与长按菜单 | `S/ui/scheduled/ScheduledTasksScreen.kt:241`；`ScheduledTaskEditScreen.kt:664` | 并入 2.1-3 胶囊工作 |
| 6 | 对话内 MCP 面板：搜索、全部启用/停用、服务器详情页 | ❌ 仅逐行 Switch，无搜索/批量/详情 | `S/ui/chat/SessionMcpsSheet.kt:81-101` | 搜索 + 批量开关（小改）；详情页可选 |
| 7 | Skills 面板可看技能详情；长按复制网址安装技能的来源 | 🟡 有搜索与全部启用/停用；**无详情入口、无长按复制** | `S/ui/chat/SessionSkillsSheet.kt:104-113,135-160,177-187`；详情页仅存在于 `S/ui/settings/SkillsManagementScreen.kt:312` | 小改 |
| 8 | 长按工具状态栏切换预览窗；长按思考块切换「流式展开思考」 | 🟡 两项都是设置里的开关，无长按手势 | `S/ui/settings/ChatInputSettingsScreen.kt:194,156`；`S/ui/chat/ChatComposerWidgets.kt:781-975`；`ChatAssistantMessageUI.kt:688-694` | 可选 |
| 9 | 朗读音量滑块 + 朗读时压低其他应用 | 🟡 已有 ducking（`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`）；**无音量滑块** | `S/speech/ReadAloudPlayer.kt:569` | 加滑块（小改） |
| 10 | 设置 › 存储：清理孤立的会话文件 | ❌ 只有针对现存会话的 Clear Session Files | `S/ui/settings/StorageManagementScreen.kt:241-258` | 加 orphan 扫描清理（中改，省空间收益明确） |
| 11 | OpenAI 兼容服务商可单独设置响应等待时间 | ❌ `readTimeout` 硬编码 600s，配置无该字段 | `S/provider/openai/OpenAIProvider.kt:355`；`S/data/model/ProviderConfig.kt` | 加 per-provider 超时字段（小改，本地模型受益） |
| 12 | 显示 Kimi Code 订阅用量；MiniMax、OpenCode 链接官方控制台 | ✅ | `S/provider/quota/ProviderQuota.kt:64,91,138-139,154,181`；`S/ui/settings/ProviderQuotaSection.kt:51` | 无 |

### 3.2 修复

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | DeepSeek 工具调用误写进思考文本→自动重试；长任务后期空回复先重试再「继续」；输出上限截断明确提示并可继续 | 🟡 空回复重试与 Resume 已有；**思考文本内工具调用无检测**；**无截断提示** | `S/agent/AgentLoop.kt:957-984,565-591,1235`；`S/provider/LLMProvider.kt:167`；`R/values/strings.xml:2464`；思考解析仅 `S/provider/openai/ThinkPrefixStreamParser.kt` | 补截断提示（微改）；思考内工具调用检测→重试（中改） |
| 2 | 中转站模型获得真实上下文窗口、输出上限、思考档，不再被猜成 128K | ✅ | `S/provider/rules/ModelRulesProvider.kt:71`；`S/provider/openai/OpenAIModelsApi.kt:224`；`S/provider/ThinkingLevelCatalog.kt:12`；`S/data/model/LLMModel.kt:126-176` | 无（无 catalog 条目时仍回落 128K，可观察） |
| 3 | OpenCode Go：Quick Test 可用，多类请求不再被拒 | ⛔ 我们没有 OpenCode 服务商；同类能力由通用机制各自实现 | `S/data/model/ProviderConfig.kt:8-70`；`S/provider/bridge/ProviderBridge.kt:54,134-151`；`S/provider/quota/ProviderQuota.kt:154` | 不适用 |
| 4 | 刷新模型列表显示结果或失败原因 | ✅ 标题随刷新态切换；失败或未替换时 Toast 说明并保留原列表 | `S/ui/settings/ProviderDetailScreen.kt:595,614-620`；`R/values/strings.xml:1936-1938` | 无 |
| 5 | Codex（ChatGPT 登录）请求带账号 ID 并跨轮复用提示缓存 | ✅ | `S/provider/openai/OpenAIProviderRequests.kt:886`；`S/provider/ProviderFactory.kt:72`；`OpenAIProviderResponses.kt:108,624,665` | 无 |
| 6 | 任务中途删除或停用服务商时回退到组内下一个模型 | ✅ 运行中检测条目停用即重解析、按组顺序回退 | `S/agent/AgentSessionOperations.kt:953,980,1006`；`S/agent/AgentSession.kt:1253,1272`；`S/data/repository/ProviderRepositoryConfig.kt:944` | 无 |
| 7 | 工具结果始终紧跟对应调用（含压缩后），修复 Anthropic/Gemini 报错 | ✅ 切片后修复孤儿对并留 in-flight 豁免 | `S/agent/OutgoingHistory.kt:252`；`ToolBatchRepair.kt:1-25`；`AgentLoop.kt:53` | 无 |
| 8 | shell 输出中文等多字节不乱码；沙盒 git 恢复可用 | ✅ LANG/LC_ALL=C.UTF-8；guest 同 locale；UTF-8 解码；git 已安装并探测 | `S/runtime/ubuntu/UbuntuKernel.kt:623-624`；`UbuntuProvisioner.kt:31,158,228`；`S/runtime/guest/GuestCommandBridge.kt:372-392` | 无 |
| 9 | 发送前被缩小的图片不再从请求里丢失 | ✅ 压缩字节进 ImagePart；超预算改可回读文本占位 | `S/ui/chat/UserAttachmentPreparer.kt:219-225`；`S/provider/ImageBudget.kt:30-46`；`S/ui/chat/RequestImageBudget.kt:14-75` | 无 |
| 10 | 浏览器 UA 设置对所有新标签生效；Cookie 全部清除与逐站删除恢复 | ✅ UA 对新标签生效；逐站删除仅写空 Cookie，**可能不彻底** | `S/browser/BrowserTabPool.kt:833-836,1074-1079`；`S/ui/browser/BrowserSettingsSheet.kt:390-392,427-428` | 可加固逐站删除（小改） |
| 11 | 录音中点击发送等识别完成、只发一次 | ❌ 直接 sendMessage，发送键只看 hasText/附件 | `S/ui/chat/ChatScreenHandlers.kt:14-48`；`ChatScreenComposer.kt:1654-1692` | 发送前 await 识别结果（中改） |
| 12 | 记忆/人设/技能编辑器里键盘不遮挡正在编辑的行 | ❌ 这些编辑器未接焦点滚动与 imePadding | `S/ui/util/BringIntoViewOnFocus.kt:38`（仅两处使用）；`S/ui/chat/MemoryDetailScreens.kt:67`；`S/ui/settings/SoulSettingsScreen.kt:650` | 接入 bringIntoView/imePadding（小改） |
| 13 | 对话标题跟随重命名/重新生成；任务结束后「正在思考」行不残留 | ✅ | `S/agent/AgentSend.kt:283`；`AgentLoop.kt:1219-1240`；`AgentSession.kt:277-284`；`S/ui/chat/ChatScreen.kt:182` | 无 |
| 14 | 复制的对话标题递增（「Foo (Copy)」「Foo (Copy 2)」） | ❌ 只有 `(Copy)`，再复制得 `(Copy) (Copy)` | `S/data/SessionForkManager.kt:90`；`R/values/strings.xml:1954` | 微改 |
| 15 | 备份：保存某服务器时其他 SMB 不再用错凭据列出；恢复进度本地化 | 🟡 恢复进度已全本地化；SMB **结构上不串**（每远程取自己名字的密钥、同名被拒），未见专门修复 | `S/ui/settings/backup/BackupAndRestoreScreenSections.kt:573-581`；`S/backup/remote/RcloneRemoteStore.kt:178,196,210,309`；`RcloneDestinationsViewModel.kt:190,211,247,255` | 无（保存时全量重同步所有远程，可留意） |
| 16 | 提问卡片被截断的问题总能展开；「移动到…」只在有共享内容时显示 | ✅ 问题文本可滚动全览；`Move to…` 由 `hasInjectedShareContent` 驱动，发送/关面板即清 | `S/ui/chat/ChatAgentStateUI.kt:150-160`；`S/ui/chat/ChatScreenComposer.kt:326`；`ChatScreenBottom.kt:226-227,315` | 无 |
| 17 | 悬浮胶囊拖到靠近左/上边缘时位置不丢失 | 🟡 恢复与旋转会夹回屏内；**拖拽松手保存不夹取**，负坐标存档后读端判无效 → 位置回默认 | `S/service/ToolOverlayController.kt:249-256,659-660,758-765,928-932`；`S/data/repository/BackgroundSettingsRepository.kt:81-85` | 保存前 clamp（微改） |
| 18 | 启动反复崩溃能自恢复；崩溃提示点「暂不」不再关闭崩溃保护 | ✅ 连续崩溃进安全模式仍可启动；暂不分享只 24h 抑制提示，force-home 保护保留 | `S/crash/CrashFrequencyDetector.kt:57,263-265,606-616`；`S/MinisApp.kt:84,117-118,142,378` | 无 |
| 19 | 用量：零余额币种不盖过仍有余额的另一种；点回复下方仅靠末尾才弹用量胶囊 | 🟡 币种显示已正确（全零才 EMPTY，headline 取最大）；**用量胶囊触发状态是死路径**，点空白弹胶囊的交互不存在 | `S/provider/quota/ProviderQuota.kt:38-50`；`S/ui/settings/ProviderQuotaSection.kt:141-145`；`S/ui/chat/ChatScreen.kt:390-391`；`ChatMessageMenu.kt:135,195` | 要么接上交互，要么删死代码（微改） |

### 3.3 打磨

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 子代理统计小标签更紧凑、高度统一 | ⛔ 我们卡片没有「统计标签」实体（只有状态药丸与耗时文本） | `S/ui/chat/ChatSubAgentCard.kt:107,114,190-210`；`S/agent/subagents/SubAgentJobRegistry.kt:39-77` | 不做 #3.1-3 时无动作 |
| 2 | 菜单阴影完整并与屏幕边缘留间距；定时任务角标不被裁切 | 🟡 阴影已有（Launcher 风格光晕）；**弹层可贴边、光晕可能顶到屏边**；角标存在且未见裁切 | `S/ui/components/MinisMenu.kt:64,78,140-193`；`S/ui/sessions/SessionListScreen.kt:343,397-398` | 给弹层留 inset（微改，可选） |
| 3 | 「显示最后 8K / 共 10K 字符」提示与思考文字对齐；服务商列表分隔线；胶囊加载动画改为环绕圆环 | 🟡 8K 提示多了 12dp 内边距、**与正文未左对齐**；分隔线 ✅；加载环 ✅ | `S/ui/chat/ChatAssistantMessageUI.kt:787-788,791-796`；`S/ui/settings/ProviderListScreen.kt:274`；`S/service/ToolOverlayController.kt:615-619,799-820` | 去掉多余内边距（微改） |

---

## 4. 1.15-beta.4（build33）

### 4.1 新功能

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 后台时模型提问以通知出现，可直接在通知里作答（选项按钮、允许/取消、「其他…」输入） | ❌ 只有 MCP 批准/拒绝与完成通知，无提问通知 | `S/notification/McpConfirmNotifier.kt:69-70`；`BackgroundTaskNotifier.kt:75-85`；`AgentForegroundService.kt:753-766` | 依赖 2.1-1 的问题持久化，先做那步 |
| 2 | 有问题等待时，常驻通知（与 Android 16 状态栏胶囊）显示「等待你的回答」+ 倒计时 | ❌ | 同上 | 同上 |
| 3 | 工具状态栏里的提问预览改为提问卡片缩略版（标题、进度、问题、带当前选择的选项） | ❌ 无该预览 | 同上；对话框 `S/ui/chat/ChatAgentStateUI.kt:76` | 同上 |

### 4.2 修复

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 提问卡片内容滚到边缘时不再来回弹 | ❌ 普通 `verticalScroll`，无 overscroll 抑制（自研回弹只用于聊天列表与文件预览） | `S/ui/chat/ChatAgentStateUI.kt:153`；对照 `ChatScreenPane.kt:484`、`FilePreviewScreen.kt:869-889` | 套用既有 overscroll 方案（微改） |
| 2 | 问题缺 type 或 type 未知时，只要字段能看出类型就正常显示 | ✅ 实现从不读 `type`，缺/未知都不影响 | `S/tools/AskUserQuestionTool.kt:28-63,83-94`；schema `S/tools/AgentTools.kt:235-264` | 无 |
| 3 | Android 8–10 首次启动 shell 不再崩溃 | 🟡 PTY/session 启动本体无版本分支；启动链上的终端前台服务已有完整兼容保护（Q+ 三参 startForeground、N+ STOP_FOREGROUND_REMOVE、O+ channel）。若上游崩溃点在 PTY 层则我们未覆盖 | `S/service/TerminalForegroundService.kt:64-69,73-74,105`；`S/runtime/terminal/TerminalSessionManager.kt:281`；`S/sandbox/TerminalSession.kt:55-62` | 我们有 Android 8–10 设备时再加验证 |
| 4 | 某个加密存储读不出来时不再清空所有已存凭据 | ✅ 单店失败只删本店 XML，重建失败给只读内存实现、绝不写明文，不删共享 keyset | `S/util/EncryptedPrefsFactory.kt:43-77,95`；调用方 `ProviderRepository.kt:121`、`EnvVarRepository.kt:53`、`auth/OAuthManager.kt:64`、`RcloneRemoteStore.kt:115`、`MCPOAuthStore.kt:34` | 无 |
| 5 | 常驻通知不再「0 sessions \| Idle」；仅打开一个对话不出状态栏胶囊与计时 | ✅ | `S/service/AgentForegroundServicePolicy.kt:16-20`；`AgentForegroundService.kt:75-77,476-484` | 无 |
| 6 | 列表项下面嵌套的表格渲染为表格而非原始文本 | ✅ 表格 re-parent 到容器父级，任意深度按 TABLE 渲染 | `S/ui/chat/md/MdBlockParser.kt:623-654,130,311,335`；`MarkdownBlockAdapter.kt:119-127` | 无 |
| 7 | 语音输入编辑转写时双击某个词可选中 | ✅ 编辑态为原生 BasicTextField，无手势拦截 | `S/ui/chat/voice/InlineVoiceInputPanel.kt:861,886-890` | 无 |

### 4.3 打磨

| # | 上游条目 | 我们现状 | 证据 | 建议动作 |
|---|---|---|---|---|
| 1 | 人设编辑器「English」语言按钮不折成两行 | ✅ 分段控件标签强制单行 + 省略 | `S/ui/settings/SettingsComponents.kt:387-395`；`SoulSettingsScreen.kt:710-724` | 无 |

---

## 5. 不建议迁移 / 不适用

| 条目 | 原因 |
|---|---|
| Share Chat 长图/PDF（beta.1 新功能 5）及其相关修复 | 与我们当前分享路径（纯文本、导出 zip）定位不同，工作量大，除非有明确需求 |
| OpenCode Go 相关（beta.3 修复 3） | 我们没有该服务商 |
| 模型组额度胶囊、聊天顶栏黄点位置 | 我们的额度黄点位置与上游不同是既有设计选择，非缺口 |
| 红色模型切换闪烁（beta.2 修复 9）、助手头像重复（beta.1 打磨 4）、点回复弹用量（beta.1 修复 1）、分享页图标（beta.1 修复 12） | 我们根本没有对应 UI，无卡住/误触路径 |
| 定时任务会话保活（beta.1 新功能 3）、子代理产物归属（beta.1 修复 8）、beta.4 通知作答三连 | 涉及会话生命周期/数据模型改动，需单独立项评估，不在小改动批次内 |

## 6. 建议批次

**第 1 批（小改、低风险、设备可感，建议先做）**
- `yes | head -c N` 管道收尾（2.2-1b）；超大文件三件套 file_read 流式 / file_edit 8MB / 文件工具超时（2.2-2）
- 定时任务不再误触「重复调用工具」警告（1.2-13）；后台时间不再误判卡顿（1.2-14）
- Claude/OpenAI OAuth 被拒立即失败并显示原因（1.2-4）；MCP 变量列表滚动 + 面板全展开（1.2-2）
- 配置审批弹窗本地化（1.2-11）；模型名不换行（1.2-9）；复制会话标题递增（3.2-14）
- 悬浮胶囊保存前 clamp（3.2-17）；提问卡片滚动不弹（4.2-1）
- 自动重试「↻ 1/3」标记（2.1-5）；停止前确认 + 设置项（3.1-2）；App 自处理提示过滤（2.2-10）

**第 2 批（中等改动，收益明确）**
- 环境变量可停用（1.1-2）；更新「跳过此版本」+ 红点（1.1-1）
- 提问卡片持久化（关 App 仍可作答）（2.1-1）→ 之后才有 beta.4 的通知作答
- 过期工具耗时落库（2.2-6）；STDIO MCP 上限 + 进程树（2.2-12）
- 编辑器键盘遮挡（3.2-12）；录音中点发送（3.2-11）；OpenAI 兼容独立超时（3.1-11）
- 上下文用量/额度两行轮换（2.1-4）；朗读音量滑块（3.1-9）；MCP/Skills 面板搜索与批量（3.1-6/7）
- 增量裁剪补充清旧推理与合并旧步骤（3.1-1）

**第 3 批（需设计或产品决策）**
- 定时任务会话保活 + 计时胶囊 + 立即执行（1.1-3、2.1-3、3.1-5）
- 子代理产物与定时任务归属（1.1-8）；子代理卡片补工具调用与输出（3.1-3）
- 覆盖 3 会话并发上限 5→6（3.1-4）；工具折叠行默认值与位置对齐（2.1-2）
- 存储孤立文件清理（3.1-10）；配置变更摘要与详情页（1.1-6）；Codex 速度三档（1.1-7）

**不做（除非需求变化）**：见第 5 节。
