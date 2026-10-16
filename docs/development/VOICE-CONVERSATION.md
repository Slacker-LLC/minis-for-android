# 语音对话开发与验收

> 语音对话仍是现役入口；本文中 Bot 资料页、成员身份及接待成员的路由描述属于已移除功能的历史记录。

目标：电源键/系统助理、语音快捷方式和聊天页入口进入同一语音对话；识别结果直接提交当前会话的完整 Agent 回合，继承会话、工具与权限。使用现有系统/Provider ASR 与 ReadAloudPlayer，不另建模型配置或 Agent Loop。

实现前记录的失败方式：

- 助理 ACTION_ASSIST / ACTION_VOICE_ASSIST 或旧 `action/voice` URI 未解析，唤醒只打开普通首页。
- 无麦克风权限、系统缺少 ASR、Provider 配置错误时无限重试，界面没有退出/重试入口。
- 同一次识别重复 final，旧识别或模型回调污染下一次聆听/新通话。
- 短指令被听写的两秒语音门槛丢弃，或长静音导致对话停顿。
- 播报与采集重叠，自身声音触发下一回合；自动朗读与通话朗读重复播放。
- 静音误取消正在执行的任务；取消静音启动第二个识别或模型回合。
- 打断取消其它入口启动的会话任务；挂断/离开页面后重新开启麦克风。
- 草稿首轮生成真实 session 后后续语音丢失历史；仅拿 isStreaming 判定结果导致读出上轮答案。
- 忙时的语音补充偷走文字输入框附件，或把暂存在内存的补充队列当成崩溃可恢复的收件箱。
- 工具/授权/失败被当成成功；通话页面遮住现有权限确认，或语音绕过它。
- 应用退到后台/锁屏后在用户不知情时继续采集；重新前台没有明确恢复入口。

第一版范围：ASR → 完整 Agent → TTS 的轮流对话，自动结束一段录音并在播报后继续听。用户可提前提交、静音、补充指令、点击打断、返回聊天处理权限。静音只停录音/播报；挂断结束音频与等待，已接受的 Agent 任务继续执行；“打断并说话”只取消该通话发起的当前回合。

通话界面（`voicecall/VoiceConversationHost.kt`）是单屏结构：顶部是标题、当前会话模型与通话计时；中部是角色、状态词与实时字幕；底部是一个主操作、一行可换行的次要操作和始终存在的「查看聊天 / 结束语音」。角色由 `:grokbot` 模块（Mr-XiaoLiang/GrokBot，MIT）绘制，`voicecall/grok/VoiceBotAvatar.kt` 是唯一的“状态 → 表情”映射点；字幕只取 Runtime 回调的真实文本，界面不再内置演示文案，也不再用 `assets/moods` 的 SVG 素材。

进入通话的 pending 动作绑定目标会话（`DeepLinkCoordinator.setPendingChatAction(action, sessionId)` / `pendingChatActionFor(sessionId)`）：deep link 先写入动作、再导航到自己的会话，若动作不带会话 id，正在被替换的那个 ChatScreen 会先消费它并连同刚打开的通话一起销毁，表现为“按了语音键却没有语音界面”。消费还要等 `Lifecycle.State.RESUMED`（`VoiceConversationHost` 用 `lifecycle.currentStateAsState()` 把 pending 动作挂到前台状态上）：冷启动时组合早于 `onResume`，而开麦入口只在 RESUMED 下工作，提前消费会静默停在 Ready 让用户再点一次。运行时可见文案（聆听提示、识别失败、任务未完成）改由 UI 层注入资源字符串，Runtime 里不再写死中文。

2026-10-15 在 `emulator-5556`（API 36）用 `-PminisDevBuild=true` 的 debug 包验证上述 deep link 修复，两条路径都直接进入 Listening：冷启动 `am force-stop llc.slacker.minis.dev; am start -a android.intent.action.VIEW -d "minis://action/voice_chat" llc.slacker.minis.dev` 后计时 00:29 → 00:36 连续推进；应用已在前台时重复同一 intent，系统回 "intent has been delivered to currently running top-most instance"，界面同样进入 Listening（00:03）。验证借用 `settings put global overlay_display_devices "1080x2400/420"` 造出的虚拟屏，避免与同机其它测试抢前台，验证后已清空该设置。这条证据只覆盖“deep link 能打开通话并进入聆听”，实际音质、OEM 麦克风/电源键与锁屏行为仍未验。

端到端验收应覆盖真实 ChatViewModel / Room / 工具分发以及连续两轮、重复回调、静音、挂断、打断、识别失败。脚本化 ASR/TTS 端口只验证编排；实际音质、麦克风、OEM 电源键与锁屏行为需真机另验，不能用模拟器结果代替。验收输出 JSON 工件并记录运行命令。

早期 Bot 融合方案见已停止的历史记录 [Bot 与语音融合](BOT-VOICE-INTEGRATION.md)。当前语音补充沿现有 STEER 队列，不发送文字输入框的待发附件。STEER 目前仍为进程内队列，不能声称崩溃可恢复。

## 后台通话与通知（2026-10-16）

通话进行中由 `service/VoiceCallForegroundService`（`microphone` 类型前台服务）托住进程，切后台/锁屏后麦克风继续工作；通知栏常驻「语音通话进行中」，带计时、「暂停/恢复」「结束」按钮，系统状态栏同时显示麦克风指示。

- 启动：通话页在通话开始（应用在前台，满足 Android 14 的 while-in-use 限制）时启动；通话结束或挂断即停。`START_NOT_STICKY`，进程被杀不会自动重启，也就不会在用户不知情时重新开麦。
- 回退：服务没能启动（无麦克风权限、系统拒绝）时，切后台仍然立即静音，与此前行为一致。
- 通知按钮经 `VoiceCallActionReceiver` → `VoiceCallBridge` 回调到通话页，与页内按钮走同一个静音/挂断逻辑；服务不持有通话状态，也不碰麦克风。
- 服务期间持有 `PARTIAL_WAKE_LOCK`（上限 2 小时），避免锁屏后 CPU 休眠打断识别与朗读。
- 后台通话仍受 300 秒无人说话自动结束、连续识别/任务失败自动挂断的约束。
- 验证：`emulator-5556`（API 36）上，通话中按 Home，服务以 `types=0x80`（microphone）在前台，`dumpsys audio` 显示 VOICE_RECOGNITION 录音在后台持续重启且未被静音，状态栏有麦克风指示；通知上「暂停音频」后录音停止、文案变为已静音，「结束」后服务与通知一并消失。未验证：真机、锁屏、OEM（HyperOS 等）后台策略与省电、通知权限被拒时的体验、蓝牙耳机路由。

## 通话中附图（2026-10-16）

输入栏左侧「＋」打开系统照片选择器（最多 4 张），选中的图片以缩略图排在输入栏上方，可单张移除。图片随下一条真正提交的消息（打字或语音识别结果）一起交给 `AgentRunner.submit`；如果那条话是并入正在运行任务的补充指令，则图片不带走，留给下一轮。挂断时清空。模拟器上验证了选择、缩略图与移除；因无 provider，图片是否被模型正确接收未验证。

**图片给谁看**：通话链路是 语音识别 → Agent 一轮 → 朗读，没有「语音模型」在看图；图片走的是聊天同一条 `submitPrompt` → `UserAttachmentPreparer`。当前模型自带 image 输入就直接带像素；没有但配了 Vision Group，则图片落盘后以占位文字提示模型调 `read_image`，由视觉组里的模型描述（`AgentLoop.visionPlaceholderFor`、`VisionGroupResolver`）；两者都没有时「＋」不显示（`AgentSession.canAcceptImages`，换模型后重新判断）。上游没有语音通话页，无可对照实现。

## 听筒/扬声器（2026-10-16）

设置面板里的「使用听筒播放」开关（`VoiceOutputState.callEarpiece`，存在 `voice_prefs`，默认关）。开启后通话期间 `CallAudioRoute` 把系统切到 `MODE_IN_COMMUNICATION` 并选内置听筒（API 31+ `setCommunicationDevice`，更低版本关扬声器），同时 `ReadAloudPlayer`/系统 TTS 改用 `USAGE_VOICE_COMMUNICATION` 播放，否则系统仍按媒体走扬声器。挂断、关闭开关或离开页面都会还原模式；中途切换从下一句起生效。需要 `MODIFY_AUDIO_SETTINGS`（普通权限）。蓝牙/有线耳机在场时系统路由优先。验证：`emulator-5556` 上开启后通话中 `dumpsys audio` 为 `MODE_IN_COMMUNICATION`，挂断后回 `MODE_NORMAL`（缺该权限时 `setMode` 被拒，已补）。未验证：真机听筒实际出声、蓝牙共存、provider 音频路径的实际路由。


## 长任务汇报与任务语音指令（2026-10-16）

参考 OpenAI Codex/ChatGPT Voice 的委托模型：语音层只负责对话，真正的工作仍在原会话的 Agent 里跑；汇报只说已核实的事实。

**长任务期间的口头进度**（`MinisCallRuntimeAdapter`）
- 提问后 4 s 还没结果开始汇报，之后每 30 s 看一次，每轮最多 5 句（`CallLimits.PROGRESS_*`）。
- 内容来自 `CallModelGateway.progress()`：回合在跑时，最新助手消息里正在执行的工具名和已完成步数（`describeProgress`）。没有可核实的状态就不编；此时只念一次「稍等，我在处理」，同一句不重复。
- 正在念的进度句会让位给答案（念完再念答案），授权提问、静音时不念。

**后台任务结果播报**
- `AgentCallTasks.updates` 监听任务表：已知任务进入已完成/需要处理/失败/预算用尽时播报（第一次看到的任务只是基线；取消/暂停是用户自己做的，不播报）。
- 通话在等用户开口时直接收声念出来；正在回答、听授权、念别的播报时排队，回到聆听前再念；静音只写进聊天记录不出声；挂断后不再处理。
- 回答里已经说过的结果（`taskUpdateKey = 任务id:状态`）不重复播报。

**口头查询/切换任务**
- `parseTaskCommand` 只认短句与固定句式：「现在有哪些任务 / 任务进度」「切换到 X 任务 / 切换到第二个任务 / 我想和 X 任务聊聊」「回到上一个任务」及英文对应说法。「帮我新建一个整理照片的任务」这类正常请求不会被截走；任务端口没有回应时照常交给模型。
- 口头清单只念进行中的任务（最多 5 个，已结束的历史任务不念）；序号对应刚念的清单，名字片段则在所有任务里找（可以切到刚完成的任务看结果）。名字必须唯一命中，多个命中或没有命中就直说，不替用户挑。
- 切换成功：念确认 → 结束通话 → 经 `onMoveToSession` 跳到该任务所在会话并在那里自动开通话；「回到上一个任务」用进程内返回栈（`TaskBackStack`）。切换只换会话，不改任务本身。

**提示词**：通话进行中，系统提示末尾追加 `VOICE_CALL_PROMPT`（转写可能有错/不完整/自我修正，以最后说法为准，拿不准或代价大时先问一句；回复要被朗读所以简短、无 Markdown；工具结果确认后才说完成）。放在最后，不通话时提示逐字节不变。

已验证：纯 JVM 单测覆盖指令解析（含误触发的反例）、任务匹配（无命中/多命中/序号越界）、状态变化检测、进度描述（无可核实状态时静默）、播报排队/静音/去重/挂断后忽略/不压过授权提问、切换回调。**未验证**：真实设备上多会话间的口头切换（目标会话的通话是否稳定接管）、后台任务真实完成时的播报、长任务期间 TTS 与工具输出同时发生的听感。

## 回复只念结论（对齐 Codex，2026-10-16）

依据：openai/codex（`e790ce2`）`codex-rs/core/src/realtime_conversation.rs:103,1657-1662`（后端输出按 1000 token 预算截断后才交给语音模型）、`prompts/templates/realtime/backend_prompt.md:45-52`（只说结论/状态/下一步；默认不念表格、diff、代码块、结构化数据；用户明确要求才详细）、`:54-58`（更新频率/详细程度偏好整个任务内有效）、`prompts/templates/realtime/realtime_start.md:3-9`（执行方知道输出会被转述）。

做法：完整回复照旧写进聊天记录；`CallModelGateway.speakable()` 决定实际念什么（`runtime/SpokenBrief.kt`、`AgentCallGateway.speakable`）：
- 短（≤200 字、≤3 行）且无代码块/表格的回复：去掉格式后原样念，不多一次模型调用。
- 用户明确要求详细（「详细说说」「念出来」等）：去格式后按 1000 字预算念。
- 其余：一次轻量概括调用（同一个 provider，不带工具，8 秒超时），提示词照搬 Codex 的呈现规则；失败、超时或结果为空/过长时退化为「去格式后的第一段，在句子边界截断」，绝不整篇念。
- 通话提示词补了「更新频率/详细程度偏好整个任务有效」。

与 Codex 的差异：Codex 有独立的语音模型，我们只有一个干活的模型，用概括调用代替。未验证：真机上概括调用的延迟与各 provider 的兼容性（只有单测和 fake 覆盖）。

## 全双工实时通话（对齐 Codex，2026-10-16，第二阶段：运行时）

与原 ASR→Agent→TTS 流水线并列、二选一，**原流水线代码保留**。代码在 `voicecall/realtime/`。

- 结构对齐 Codex：实时语音模型只当对话界面，Agent 是后端执行者（`codex-rs/core/src/realtime_conversation.rs`）。
  - 后端输出加 `[BACKEND] ` 前缀并按预算截断（同文件 :103、:115、:1657-1668；我们按 2000 字符）。
  - 交给后端的请求上限 4 KB（`core/src/context/realtime_delegation.rs`）。
  - 进行中的进度作为额外条目注入（对应 Codex 的 Commentary 阶段，同文件 :960-1030），只报已核实的进度。
  - 语音模型提示参考 `prompts/templates/realtime/backend_prompt.md`（只说结论、不念表格/代码、偏好整个任务有效），见 `RealtimePrompt.kt`。
- 两种交接：工具模式（模型调用 `delegate_to_agent`）与条目模式（`codex_responses_as_items`：模型不自动回复，用户说完把转写交 Agent，结果以条目注入）。
- 复用现有端口：`CallModelGateway`、`CallApprovals`（权限请求由实时模型口头问、转写解析回答，不交给 Agent）、`CallTasks`、空闲 300 s 挂断、静音、打断。
- 失败关闭：无麦克风权限直接结束；连接失败/15 s 未就绪结束（新增 `CallEndReason.CONNECTION_FAILED`，8 种语言文案）；就绪后单次服务端错误不致命，连续 3 次结束；只接受 `wss://`。

**未验证（没有真实 API key 和真机）**
- 所有厂商的真实连接、事件顺序、字段细节只对照官方文档写，未联网验证。
- 回声消除（`AndroidRealtimeAudio` 用 VOICE_COMMUNICATION 音源 + 系统 AEC/降噪/增益）各机型效果未测；扬声器外放最可能出现自我打断。
- 打断后遗留的未应答函数调用，服务端是否容忍未验证；用户插话时的音频截断（OpenAI `conversation.item.truncate`）未做。
- 工具模式下口头任务指令与模型自动回复可能抢跑（转写晚于回复开始时）。
- 千问工具调用字段尚未对照文档；xAI/智谱/阶跃/豆包见下节。

### 第三阶段：设置与接线

- **不另存 API key**：实时模型直接使用「管理提供商」里已有的提供商和它的 key（`loadApiKey`）。通话设置只存非敏感选择（提供商、协议档案、模型/音色/业务空间/自定义地址），见 `RealtimeSelection.kt`。
- key 只发给它所属的厂商：OpenAI/Gemini 档案只接受官方端点的提供商（自定义 base URL、Azure 的 key 不会被发往官方地址），xAI 档案只接受官方 xAI 提供商（无自定义 base URL）；千问、智谱、阶跃、豆包和自定义档案接受任意 API key 提供商（由用户自己对应）；OAuth、已停用、`unsupported` 一律拒绝（`providerFits`，有否定用例）。
- `EngineSwitchingCallRuntime`：默认经典流水线；选了实时模型且设置完整才用实时运行时，否则安静回退到经典流水线。
- `CallSettingsSheet` 新增「通话引擎」区（8 种语言）。

### 第四阶段：xAI / 智谱 / 阶跃星辰 / 豆包（2026-10-16）

档案内置了地址、默认模型、音色、采样率，用户只需在「管理提供商」里有对应的 key。前三家是 OpenAI 风格协议的不同会话形态（`OpenAiStyleCodec.SessionShape`），豆包是独立编解码器（`DoubaoDuplexCodec`）。

| 厂商 | 地址 / 鉴权 | 默认 | 与 OpenAI 的主要差异 | 来源 |
|---|---|---|---|---|
| xAI | `wss://api.x.ai/v1/realtime?model=` / Bearer | `grok-voice-latest`、`eve`、24k/24k | 音色和 `turn_detection` 在 session 根上；`audio.input.transcription.model=grok-transcribe`；工具是扁平 function | https://docs.x.ai/developers/model-capabilities/audio/voice-agent |
| 智谱 | `wss://open.bigmodel.cn/api/paas/v4/realtime` / Bearer | `glm-realtime`、`tongtong`、16k 入/24k 出 | `input_audio_format=pcm16`、`output_audio_format=pcm`；`beta_fields.chat_mode=audio`；**函数调用事件没有 call_id**（用 `response_id:output_index` 本地配对），结果回传项也不带 call_id | https://docs.bigmodel.cn/cn/api/realtime/ |
| 阶跃星辰 | `wss://api.stepfun.com/v1/realtime?model=` / Bearer | `stepaudio-3-realtime-preview`、`elegantgentle-female`、24k | `pcm16` 入出；工具嵌套在 `function` 下；`turn_detection` 无 `create_response` | https://platform.stepfun.com/docs/zh/api-reference/realtime 与 `/guides/audio/realtime` |
| 豆包 3.0（Seeduplex） | `wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue` / `X-Api-Key` 头 | `model=1.2.6.1`、`zh_female_vv_uranus_bigtts`（Vivi 2.0）、16k 入/24k `pcm_s16le` 出 | 纯 JSON 文本帧；`session.create`；函数调用在 `items[]`，结果以 `role=tool` 条目回传且不需要 `response.create`；**麦克风停送必须发 `input_audio_mute.commit`/`unmute`**；结束前先 `session.close`；没有注入条目的事件，权限询问/任务播报改用 `speech_text_buffer.commit` 直接合成 | https://www.volcengine.com/docs/6561/2549732 、https://www.volcengine.com/docs/6561/2549778 、音色 https://docs.volcengine.com/docs/6561/1257544 |

豆包旧版二进制协议（文档 1594356）是另一套，**不实现**，只支持新版 JSON 协议。

为此运行时新增三个可选编解码钩子：`speak`（让模型直接念一句话）、`muteSignal`（静音保活）、`closeSession`（挂断收尾）；其余协议默认不用。

**未验证（没有真实 key 和连接）**：以下均只对照文档，档案在界面上标「未验证」（`docsChecked=false`）：
- 智谱：Bearer 鉴权是否接受直接 API Key（文档写 JWT 或 API Key）；工具调用是否要求 client VAD（文档只在 client VAD 流程图里画了 Function call）；回传结果后是否需要 `response.create`。
- 阶跃：输入采样率（文档未写，假设 24k）；转写事件是否默认开启；`stepaudio-3-realtime-preview` 限免期结束后会下线。
- xAI：`create_response` 在 server VAD 下的默认行为；转写 `.completed` 是否在未配置转写模型时也发出（我们配了 `grok-transcribe`）。
- 豆包：就绪事件取 `session.created`；`transcription.completed` 的文本字段（我们依次试 `transcript`/`text`）；`response.output_text.done` 字段；下行 `error` 的结构；`session.close` 回复未等待就断开（文档要求等回复，否则服务端记 `ContextCanceled`）；`speech_text_buffer.commit` 当作口播的效果与是否触发 `response.done`。
