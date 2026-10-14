# 系统 TTS 与语音 CLI 静态审计

日期：2026-10-06。项目提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新建独立报告，只做静态阅读与对照；不修改产品代码、合同或旧报告，不运行构建、测试、模拟器、adb、Root、设备或云任务，不读取凭据、录音或用户数据。

## 本轮结论

新增 10 项 P2。异步引擎的“初始化、入队、完成、暂停、错误、取消”被多处压成布尔状态或成功响应，导致参数失效、假成功、暂停后继续朗读、旧回调污染新请求，以及取消后识别资源未及时释放。设备相关后果均为带条件的源码推导，本轮没有播放或录制声音。

| 编号 | 问题 | 直接影响 |
|---|---|---|
| ST01 | CLI 显式语言被自动检测覆盖 | `--voice fr-FR` 实际仍按英语选择语言 |
| ST02 | CLI 初始化预算短于 Manager，且把超时报成无引擎 | 冷启动中的可用引擎被误判不可用 |
| ST03 | voice 枚举对新实例只睡 200ms | 未绑定成功被返回为成功的空目录 |
| ST04 | 朗读参数跨命令、跨 session 残留 | 上次的静音、语速或音调影响下一次默认请求 |
| ST05 | 语音 CLI 丢失执行失败状态 | 合成拒绝、识别失败或超时仍返回退出码 0 |
| ST06 | 识别等待中断没有释放 recognizer | guest 命令取消后，识别服务可能继续占用资源 |
| ST07 | 系统 TTS 暂停被外层当作完成 | 后续句子继续播放，当前句可能丢失 |
| ST08 | 完成回调不核对 utterance 身份 | 旧句回调可提前结束新句状态 |
| ST09 | CLI 识别绕过 App 的朗读/录音协调 | 正在播放的回复可能被录进识别结果 |
| ST10 | 系统 TTS 异步错误被外层当作完成 | 合成失败后没有触发已有不可用反馈 |

## 实际调用链与去重边界

[MinisApp.kt:874](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:874) 注册单个 SpeakOffloadHandler，[875 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:875) 注册 SpeechOffloadHandler。前者在 [44 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:44) 持有独立的长生命周期 TextToSpeechManager。后者直接创建 SpeechRecognizer，不经过 UI 使用的 SpeechRecognitionManager。

现役 guest bridge 在 [233 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:233) 调用已注册 handler；[344 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:344) 后用断开监视线程中断正在处理请求的 worker。因此，取消/中断是实际调用链已有行为，不能只检查自然完成和超时。

回复与选择文本朗读使用 ReadAloudPlayer，其 [67 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:67) 另建系统 TTS Manager。[上一轮 MP07](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-media-speech-usage-audit.md:84) 已报告 stop 无法阻止在途合成恢复播放；本轮 ST07 是暂停被判完成，ST08 是回调身份失效，ST10 是异步错误丢失，分别具有不同触发链。工具归属、重复基础设施、注释和 lint 基线不重新计数。

## ST01 · P2 · 显式语言选择随即被覆盖

[SpeakOffloadHandler.kt:108](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:108) 把 `--voice` 转为 Locale 并调用 setLanguage；随后 [117 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:117) 调用 speak。Manager 的 [342 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:342) 只立即设置引擎，没有保存显式选择；speak 在 [197 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:197) 重新自动检测，[387 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:387) 只在简体中文与美式英语间选择。CLI 没有设置 preferredVoiceName，不能靠该优先分支保住显式 Locale。

源码反例：帮助文本给出的 `speak --text "Bonjour" --voice fr-FR`，在相应语言均可用时，先设置法语，随后又设置美式英语；响应却回显 voice=`fr-FR`。支持法语不等于法语实际生效。

方向：明确每次请求的显式语言与自动检测优先级，避免跨请求残留。后续验收保存引擎实际接收的 locale/voice、CLI 响应和音频；覆盖显式语言、缺省、不可用语言与不同 session。

## ST02 · P2 · 初始化等待预算不一致，超时被误报无引擎

CLI [90 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:90) 与 [146 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:146) 只等待 2,000ms；Manager 的 [30 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:30) 定义初始化预算为 4,000ms，ReadAloudPlayer 通过 awaitReady 使用这个预算。CLI waitForInit 在 [210 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:210) 只读取 isInitialized，既不区分明确失败和仍在绑定，也不等待失败结果。

条件反例：请求开始时引擎仍需 2.5 秒才能完成初始化。CLI 在第 2 秒返回 `tts_unavailable`，并在 [95 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:95) 告诉用户没有可用引擎、应安装引擎；实际引擎稍后初始化成功。仅延长等待仍不足以把超时、绑定失败、未安装区分清楚。

方向与验收：复用已存在的初始化结果与预算，保留正确状态。分别保存慢初始化、明确 ERROR 和无回调场景的时间线、安装引擎列表及响应；不把未验证的 OEM 时间当作固定保证。

## ST03 · P2 · voice 枚举未等待被查询实例就绪

cmdVoices 先等长期 Manager，然后在 [158 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:158) 另建一个 `TextToSpeech(..., null)`；没有该实例的初始化回调。它在 [165 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:165) 睡 200ms 后读取 probe.voices，null 被跳过，最后在 [193 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:193) 返回退出码 0、count=0。

长期 Manager 已就绪不能证明新 probe 已完成自己的连接。[Android TextToSpeech 官方文档](https://developer.android.com/reference/android/speech/tts/TextToSpeech) 将实例初始化完成交给 OnInitListener 通知；只读核对的 [AOSP TextToSpeech.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/speech/tts/TextToSpeech.java) 中，getVoices 通过 runAction 查询服务，未绑定时返回它传入的 null 错误值。由此可构造“新实例 200ms 内未绑定”的空目录反例。

方向：查询已就绪实例，或有界等待 probe 自身初始化并区分失败；保留现有 finally shutdown。后续用已安装 voices 的慢连接场景验收，保存初始化回调时间与实际枚举结果。

## ST04 · P2 · 缺省朗读参数继承上一条命令

[SpeakOffloadHandler.kt:113](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:113) 只在选项存在时更新 rate/pitch/volume。Manager 属性会保留至下次请求，stop 也不重置。帮助的 [255 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:255) 却把三项缺省值都定义为 1.0。同一个 handler 服务不同 session，没有按 session 区分配置。

源码反例：A 执行一次 `--volume 0`，B 随后执行不带 volume 的普通 speak。第二次仍传音量 0；第一条命令的静音会成为另一会话的无声故障。rate/pitch 同样残留，即使这些请求顺序执行也会触发，不依赖并发。

方向与验收：按每次请求解析完整有效配置。保存两条命令的参数、session 与引擎接收参数，覆盖显式→缺省、stop→缺省，以及两个 session 顺序调用。

## ST05 · P2 · CLI 响应把执行失败包装为成功

两个明确分支：

- 朗读：Manager 在 [207 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:207) 检查同步入队结果，失败只清状态并从 Unit 函数返回。调用方随后在 [125 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt:125) 无条件输出 `status=speaking` 与退出码 0。引擎拒绝入队时，模型得到与成功相同的响应。
- 识别：SpeechOffloadHandler 的 recognize 把创建失败、onError 和超时写成错误 JSON；[158 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:158) 的调用方无论内容是什么都返回退出码 0。创建失败 [331 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:331)、错误回调 [392 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:392) 和超时 [413 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:413) 都可到达这条返回路径。

[官方 speak API](https://developer.android.com/reference/android/speech/tts/TextToSpeech#speak(java.lang.CharSequence,int,android.os.Bundle,java.lang.String)) 的同步返回值表示入队成功或失败，不能等同实际开始或完成播放。这里的问题也不要求 CLI 必须阻塞等待整段音频；最低要求是同步拒绝不能报告 speaking，识别失败不能报告成功退出。

方向与验收：保留已有引擎/识别结果，贯通到 CLI exitCode 与明确状态。保存拒绝入队、识别错误、超时和正常结果的输出、退出码；失败不得成为普通 shell 成功链的条件。

## ST06 · P2 · guest 取消没有走 recognizer 清理

[SpeechOffloadHandler.kt:408](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:408) 直接 latch.await；只有返回 false 的正常超时分支才投递 destroy。自然回调也 destroy，但等待没有 finally。guest 断开在 [GuestCommandBridge.kt:349](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:349) 中断 worker，await 抛出 InterruptedException，外层 [81 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:81) 转成 internal 错误后退出，未安排 destroy。

条件反例：识别已 startListening，guest 命令被终止，而系统服务没有立即给最终结果或 onError。worker 已返回，recognizer 仍绑定并可能继续监听；只有后来的服务回调可能补清理。创建任务在 mainHandler 中排队时被取消，也没有取消令牌阻止迟到的创建任务继续 startListening。

[SpeechRecognizer 官方文档](https://developer.android.com/reference/android/speech/SpeechRecognizer) 要求对象不再需要时调用 destroy。方向：请求生命周期结束统一安排主线程清理，并使尚未执行的创建任务能识别取消；不能只在成功回调加清理。后续验收分别取消“创建前排队”和“已开始录音”的请求，保存 guest 退出、服务/麦克风停止与对象释放时间线。

## ST07 · P2 · 暂停系统朗读会放行下一句

[ReadAloudPlayer.kt:362](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:362) 把暂停交给 Manager。Manager 在 [333 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:333) 设置 isPaused、停止引擎并把 isSpeaking 置 false。外层 speakViaSystem 在 [634 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:634) 只轮询 isSpeaking，false 即走到 [658 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:658) 返回 true。worker 在 [145 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:145) 随即消费下一句，没有等待恢复的状态。

源码反例：系统 TTS 正播放 A，外层队列还有 B。用户暂停 A，worker 把 A 判完成并开始 B；Manager.speak 在 [191 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:191) 又把 isPaused 清 false。若没有 B，worker 会结束当前句的等待；之后恢复 A 也不再由原句的完成等待负责。此项不依赖上轮在途 Provider 合成问题。

方向与验收：暂停必须保留当前句及外层队列的等待关系，恢复后才允许下一句。通过系统语音路径播放多句并暂停足够久，保存音频、句子开始/结束/暂停/恢复记录与 UI 状态；暂停期间 B 不得启动。

## ST08 · P2 · 旧 utterance 回调可以改变新请求状态

Manager 为每次请求生成 UUID，但 [406 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:406) 后的 onStart/onDone/onError 不核对其归属。onDone 在 [418 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:418) 把递增 pausedAtIndex 的工作排到 main；新 speak 会清 pendingTexts、重置 index。清队列和 stop 没有让已投递的回调失效。

可达时序：A 的 onDone 已投递 main 但尚未执行；guest worker 开始 B，index=0、pendingTexts 仅含 B；A 的主线程闭包随后执行，index=1，错误地把 B 的 isSpeaking 置 false。迟到的旧 onError 也会直接清新句状态。CLI status 因此可能在 B 尚播放时报告不在朗读；依赖该状态的播放器遇到同类迟到回调会提前推进。

这也说明“部分回调 post 到 main”不能保证整个 Manager 只有一个线程所有者：CLI 的 speak/stop 仍由 guest worker 调用。方向：核对当前有效请求/代次及 utterance 身份，再变更队列；统一现有状态的线程归属。后续验收保存 A/B 的 ID 与延迟回调时间线，验证旧回调不能完成、停止或重启 B。

## ST09 · P2 · CLI 录音没有进入现有音频协调

UI 的 [SpeechRecognitionManager.kt:338](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:338) 在打开麦克风前调用 suspendAllForCapture，并发布 STARTING；[58 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:58) 回到 IDLE 时恢复。CLI 在 [SpeechOffloadHandler.kt:315](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:315) 自己创建 recognizer，[405 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt:405) 直接 startListening，既不暂停已有 ReadAloudPlayer，也不发布这条识别链的捕获状态。

条件反例：回复正在通过扬声器播放时，Agent 调用 android-speech transcribe。CLI 不触发 App 已有的防回授步骤；与此同时 UI 仍可表现为空闲并开始另一识别。是否实际串音、是否由 OEM 拒绝第二次录音需设备证据，但两条路径绕过同一个协调点已由源码确认。CLI 自有 android-speak 引擎也未登记为 ReadAloudPlayer，不能推断 suspendAllForCapture 已覆盖它。

方向与验收：让 CLI 与 UI 使用既有录音/播放生命周期协调；不为这项额外造第二套全局状态。保存朗读中启动 CLI 识别、UI/CLI 重叠、取消和错误恢复的音频及状态时间线，验证录音期间没有 App 自身朗读进入输入。

## ST10 · P2 · 异步合成错误没有传到已有不可用反馈

Manager 的 [428 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:428) 与 [433 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt:433) 只记录错误并清 isSpeaking，没有保存完成结果。ReadAloudPlayer 只有 speak 后的即时 [612 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:612) 和轮询超时会返回 false；异步 onError 到来时，轮询退出后 [658 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:658) 返回 true。

条件反例：引擎先接受当前句，随后在合成期间报 ERROR_SYNTHESIS/ERROR_OUTPUT。外层把它当完成，跳过 [407 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:407) 的不可用反馈并继续下一句。与 ST05 同步 CLI 入队失败不同，本项发生在已成功入队后的 UI 播放结果链。

方向与验收：保留当前 utterance 的成功/失败结果，区分暂停、停止和错误，交给现有上层反馈。验收工件应包括“入队 SUCCESS→异步 onError”的 ID/事件记录、UI 提示与实际音频，不只验证同步拒绝。

## OpenMinis 固定版本对照

仅只读本地参考 checkout `/tmp/openminis-review-20261004` 的 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，没有 fetch、运行或修改上游。以下是已读取相关片段的差异，不代表全文审过上游。

| 本轮问题 | 固定上游事实 | 对本项目的含义 |
|---|---|---|
| ST01 | [上游 Manager](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt#L396) 的 setLanguage 保存 explicitLocale；自动检测前优先使用该值，并引入 TtsLocaleResolver | 对应缺口在参考版本已修；本项目仍是固定中文/英语选择 |
| ST02 | [上游 SpeakOffloadHandler](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/sandbox/offload/SpeakOffloadHandler.kt#L93) 使用 Manager.INIT_TIMEOUT_MS，voices 同步调整 | 本项目保留旧 2 秒预算；上游修复预算不等于消除了所有错误分类问题 |
| ST03、ST04、ST05 | 上游 Speak 仍创建新 probe 后睡 200ms、仅对存在的 rate/pitch/volume 赋值、无条件返回 speaking；Speech 的 recognize 调用后仍返回退出码 0 | 参考版本也存在；不能把“matching iOS”注释当正确性保证 |
| ST06、ST08 | 上游 Speech 同样只在正常超时与回调路径 destroy；Manager 同样不核对 onDone 的 utteranceId | 根因在参考版本仍可见，现役 guest bridge 的取消方式需按本项目单独审计 |

本轮未对上游播放器及录音协调全链路复核，因此不替 ST07、ST09、ST10 给出“上游已修/未修”结论，也不建议整体移植上游运行时。

## 阅读覆盖与未计项

新增 7 个文件全文阅读，共 1,786 行，全部从首行读至末行；已有播放器、录音 Manager、全局状态、注册与 bridge 只重查相关片段，不增加全文数量。

| 本轮新增全文文件 | 行数 |
|---|---:|
| [SpeakOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt) | 272 |
| [SpeechOffloadHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt) | 455 |
| [TextToSpeechManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt) | 438 |
| [SpeechSentenceSplitter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechSentenceSplitter.kt) | 144 |
| [VoiceTextSanitizer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceTextSanitizer.kt) | 168 |
| [SpeechSentenceSplitterTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/speech/SpeechSentenceSplitterTest.kt) | 135 |
| [VoiceTextSanitizerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/speech/VoiceTextSanitizerTest.kt) | 174 |

两份现有测试只作源码阅读，没有创建、修改或执行测试。它们主要覆盖数字句界、文本清理与完整代码块；不提供上述引擎初始化、跨请求状态、取消与暂停的端到端证据。分句器的数字前瞻与播放器 terminal extraction 已有实质防护，没有按旧注释直接判它们无效。

Manager 的预初始化 replay 在复制队列后没有 stop 代次保护，speakQueued 拒绝后也保留 pendingTexts；但当前主要调用方先 awaitReady/waitForInit，本轮没有证明这些分支可由现役正常入口稳定触发，暂不新增编号。语言目录广播超时与空目录混淆、duration 未限幅等也保留为线索，不把每条观察自动升级为独立问题。

更新后的 [覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)：纳入 1,546 个文件，已全文 642、片段 13、尚无全文记录 891；排除 124 项但仍保留在 1,670 项 tracked 清单中。全文累计 132,540 行，约 41.5% 文件完成全文阅读，目标仍未完成。

本轮收尾核验：tracked 清单与 1,670 项 SHA-256 不变，HEAD 不变，tracked diff 为空；39 份既有 Markdown 报告哈希不变。本轮仅新增本报告、更新覆盖台账。上述验收场景均为后续方案，未执行。
