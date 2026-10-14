# 录音、分段识别与取消生命周期静态审计

日期：2026-10-06。项目 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮独立报告，只阅读源码、现有测试和固定上游；不修改产品代码、合同、旧报告，不运行构建、测试、模拟器、adb、Root、设备、账号或云任务，不读取凭据、录音或用户数据。

## 结论

新增 10 项：1 项 P1、9 项 P2。最直接的问题是 **Provider 识别取消后没有停止默认的 VAD 采集器**，外层却立即回到 IDLE 并允许恢复朗读。手动停止、自动限制、短语合并、语言重启及管道故障还有多条不能完整结束当前录音的路径。

以下结论区分控制流缺口与设备后果：源文件中的调用和状态变化已核对；具体设备是否串音、持续占麦多久、是否同时允许两路 AudioRecord、识别服务是否自行超时，没有实测。

| 编号 | 级别 | 问题 |
|---|---|---|
| AC01 | P1 | Provider cancel 没有停止 VAD，取消后仍可能采集 |
| AC02 | P2 | Provider 手动停止丢掉开放音频段，可能一直 FINISHING |
| AC03 | P2 | Provider 自动限制只停采集，没有识别终态 |
| AC04 | P2 | 短语合并等待计时器会在重新开口时继续倒计时并停止录音 |
| AC05 | P2 | 系统识别错误码错位，语言包缺失没有正确降级 |
| AC06 | P2 | 切换语言立即重启，旧异步 cancel 可以销毁新会话资源 |
| AC07 | P2 | stop/cancel 重新选择引擎，可能控制另一引擎 |
| AC08 | P2 | AudioRecord 负错误码被当作暂时没数据，持续空转 |
| AC09 | P2 | 系统 VAD 采集失败只停 VAD，未关闭识别音频管道 |
| AC10 | P2 | 采集线程直接阻塞写管道，自动时限可被背压绕过 |

## 已核对的完整链路

[SpeechRecognitionManager.kt:168](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:168) 创建应用级 system/provider 两个引擎。[InlineVoiceInputPanel.kt:328](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:328) 选择引擎并 startRecording；[325 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:325) 负责停止；编辑前在 [612 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:612) 取消。语言菜单 [585 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:585) 可调用 selectLocale。

Provider 默认 useVad=true，经 VoiceActivityDetector 取得闭合 WAV 再上传；System 在 API 33+ 创建管道，由同一个 VAD AudioRecord 向 recognizer 输送 PCM。低于 API 33 或建立管道失败，System 才走平台自身采集。两条识别路径和 VAD 均从首行读到末行。

[SpeechRecognitionEngine.kt:55](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionEngine.kt:55) 明确 stop 仍需交付最终结果或错误；cancel 则应终止采集并不再回调。Manager 的 [54 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:54) 在返回 IDLE 时恢复朗读，因而“状态结束但麦克风没结束”有实际影响。

上一轮 ST06 是 guest CLI SpeechRecognizer 的中断清理缺失，本轮 AC01 是 UI Provider VAD 的取消缺失；ST08 是 TTS 旧完成回调，本轮 AC06 是识别重启时旧清理影响新采集。它们分别走不同实现，不重复计入旧问题。语音纠错、工具归属、注释、重复 helper 与 lint 基线均不重新计数。

## AC01 · P1 · Provider 取消没有停止默认采集器

[ProviderSpeechRecognitionEngine.kt:499](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:499) 的 cancel 只改 cancelled/recording、取消 transcription/hold job、清 pendingSegments，没有调用 detector.stop/cancel，也没有清 detector。recording 是旧采集循环的控制旗标；默认 VAD 路径的循环读取的是 [VoiceActivityDetector.kt:369](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt:369) 自己的 running/cancelled。

UI cancelRecording 在 [Manager:390](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:390) 随即把外层置 IDLE。VAD 后来交付闭合段时，Provider [324 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:324) 因 cancelled=true 直接返回，连正常的 silence stop 也被跳过。采集并没有因取消结束，只能期待采集器自身限制或错误等其他路径。

条件反例：正常开始 Provider 录音后，用户进入编辑模式取消。UI 回到空闲、朗读可恢复，但原 VAD 仍在读取音频。若立即重新录音，start 又把共享 cancelled 清 false，旧 VAD 还可能向旧 listener 交付音频，并通过共享 detector 字段影响新采集。是否录音指示器持续多久需真机证据；缺少取消采集动作本身是明确事实。

方向与后续验收：取消应让当前采集器、其回调及当前识别请求一起失效，完成资源释放再允许复用。保存取消/再次启动时间线、麦克风活动与识别回调来源；取消后不得继续采集或提交旧音频。本轮没有实际录音。

## AC02 · P2 · 手动停止不能冲刷开放段或保证终态

Provider [491 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:491) 的 stop 仅清 recording、stop detector 并置 null。VAD 在 [565 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt:565) 明确“停止不再发出新段”，本地没有 flush 入口，也没有开放音频镜像。Provider 只有 onVoiceEnd 才能把该音频转到 transcription；关闭采集器不会替代这个回调。

源码反例：用户正在说话、尚未形成 silence close，就点击停止。Manager 在 [384 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:384) 置 FINISHING；Provider 没收到 WAV，没有启动识别，也没有返回 onFinal/onError，外层持续等待。若已有短段被放入 pendingSegments，手动 stop 同样没有读取并识别它们；残留的 hold job 只能稍后把它判太短或清掉。

这里不能用“没有 MANUAL_FLUSH 是刻意差异”的注释作为完成依据；接口和 UI 都要求停止后完成本次识别。方向与验收：分别处理开放段、已闭合待合并段和无音频，结束时必有一个终态；保存说话中停止、短段等待中停止、静音中停止的音频与 UI 状态。

## AC03 · P2 · 自动限制停了麦克风但没有结束外层状态

VAD [374 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt:374) 后分别触发总时长、空闲、后台限制。它调用 onSessionLimit 后退出循环，不冲刷开放段。Provider 的 [391 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:391) 只 stopVad，没有给 listener 终态。

无需任何模型或网络即可构造反例：Provider 录音开始后完全不说话，30 秒空闲限制停采集，但 Manager 仍是 RECORDING，因为未收到 onFinal/onError。读回复也保持在捕获暂停状态。持续说话到总时长上限则额外可能丢掉尚未闭合的音频；“库已经交付了段”的注释不符合此限制分支的源码。

方向与验收：所有自动结束路径统一收拢本次音频并交付一次终态。分别保存空闲、后台和总时长限制的采集结束、识别结果与状态时间线，不能只观察麦克风是否停止。

## AC04 · P2 · 短语等待计时器没有随着重新开口撤销

Provider 在 [352 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:352) 对不足 2 秒的闭合段安排 5 秒 hold job；[312 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:312) 的 onVoiceStart 只打日志。只有下次 onVoiceEnd 才撤销/替换 timer。System [368 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:368) 同样安排 hold runnable，[316 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:316) 的 onVoiceStart 不撤销它。

条件反例：第一段 1 秒，silence close 后等待合并；4 秒后用户重新开口，第二段仍在说。第一段的 timer 在再过 1 秒时到期，Provider 直接 stopVad，System stopVad/cancel，并按旧的 heldSeconds 判太短。用户的第二段尚未结束，因而还没有机会取消这个 timer。注释所称“5 秒真实静音”实际上是从上次闭合开始的墙钟等待。

方向与验收：新 speech start 应使旧静音到期动作失效，下一次真正静音再安排等待。保存短句→停顿→再次持续说话的段回调、timer 和音频；计时器不得在新句中途截断采集。

## AC05 · P2 · 识别错误码错位，降级条件错误

[SystemSpeechRecognitionEngine.kt:1071](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:1071) 把 11、12 都映射为 LANGUAGE_UNSUPPORTED，[1092 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:1092) 也使用错误说明，13 则走 UNKNOWN。

已核对 [Android SpeechRecognizer 官方常量](https://developer.android.com/reference/android/speech/SpeechRecognizer)：11 是 SERVER_DISCONNECTED，12 是 LANGUAGE_NOT_SUPPORTED，13 是 LANGUAGE_UNAVAILABLE。缺少已支持语言的数据包应是 13。

这不只是错误文案：[946 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:946) 仅在 LANGUAGE_UNSUPPORTED 且 usingOnDevice 时重试普通 recognizer。因此真实 13 不会进入语言包回退，而服务断开 11 反而会按语言问题重试。usingOnDevice 还按“想创建离线引擎”赋值，而不是实际成功创建的实现；构造离线失败后 fallback 到普通引擎时也可能带错此标记。

方向与验收：使用正确常量/映射并按实际引擎记录状态；保存 11、12、13 三种回调的分类、文案、是否及如何重试，不能只覆盖注释给出的“错误 12”。没有执行在线或离线识别。

## AC06 · P2 · 语言重启会让旧取消动作清理新资源

Manager 的 [272 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:272) 在录音中切换语言时，调用 engine.cancel、置 IDLE、立即 startRecording。System cancel 在 [618 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:618) 只是 post 一个稍后执行的 cancel/tearDown；新 start 却在 [168 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:168) 同步创建新的 VAD/管道，再 post recognizer 创建。

在新 VAD 成功启动的条件下，主线程队列中的旧 cancel 先执行：tearDown 通过共享 vad/audioPipe 字段清理已经被新 start 覆盖的资源；随后新 startInternal 才执行，此时新管道已经没了、continuousSession 也被清掉。若设备不允许同时启动两个采集器，则也可能先在重启窗口失败。旧 listener/回调同样没有会话身份隔离。

方向与验收：重启必须等待旧会话完整结束，清理动作按旧对象归属，迟到回调不能作用于新会话。通过录音中语言菜单切换、STARTING 中快速切换和取消后马上重开，保存每代采集器/管道/recognizer 身份及创建释放顺序。

## AC07 · P2 · 结束录音时重新选择“当前可用”引擎

startRecording 在 [323 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:323) 选出 engine，但没有把它保存为当前采集的所有者。stopRecording [387 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:387)、cancelRecording [391 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:391) 和语言重启都重新 currentEngine；它在 [302 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:302) 依据现在的选择与 isAvailable 执行 fallback。

Provider isAvailable 每次读取当前仓库的 Voice Input 选择，而 start 已把本次 candidates 捕获到局部变量。条件反例：Provider 正在采集，用户通过模型选择或配置变化使 Provider 不再是可用入口，而 System 仍可用。停止时 currentEngine 返回 System，实际 Provider 没收到 stop；取消时外层先回 IDLE，实际 Provider 更没有收到 cancel。开始时可用、结束时可用和正在持有麦克风，是三个不同事实。

UI 的 [InlineVoiceInputPanel.kt:620](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:620) 提供模型选择入口；仓库 [1851 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1851) 动态解析当前 Provider 选择。具体配置改变后的界面顺序未执行，本项保留明确触发前提。

方向与验收：stop/cancel 控制本次已启动的引擎，下一次 start 才重新选择。保存采集中改变可用性/选择后的 active engine、收到 stop/cancel 的对象、麦克风活动与最终状态。

## AC08 · P2 · AudioRecord 错误返回值被吞掉

[VoiceActivityDetector.kt:392](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt:392) 只把抛异常当错误，返回 n<=0 一律 continue；Provider 保留的非 VAD 循环 [209 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt:209) 也如此。[官方 AudioRecord API](https://developer.android.com/reference/android/media/AudioRecord) 明确 read 可返回负错误码，DEAD_OBJECT 表示对象失效、需重建。

条件反例：采集中 read 持续返回 -6。默认 VAD 路径反复读取失效对象，无等待、无 AUDIO_ERROR，直到其他墙钟限制才可能结束；非 VAD 路径的“60 秒上限”按成功读取的字节数计算，负返回永远不推进上限。这条旧路径当前没有找到 useVad=false 的正常设置入口，因此不把其无限等待单独增加编号，但它确实同样缺错误处理。

方向与验收：区分零数据与负错误，终止或按实际支持恢复失效采集，确保终态/释放。保存连续负返回的读次数、结束耗时、错误与释放；不把正常读到 0 的场景误报为设备失效。

## AC09 · P2 · 采集失败没有结束注入音频的识别会话

System 的 [399 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:399) 在 VAD capture error 时只 stopVad。stopVad [521 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:521) 清 raw sink、停止采集并清 vad，既不 closeAudioPipe，也不 stopListening 或给 listener 失败。注释说会退回平台计时，但已经使用 EXTRA_AUDIO_SOURCE 的 recognizer 不会仅因 App 清了 sink 就改为直接采集麦克风。

条件反例：API 33+ 的管道识别已开始，采集抛出 read/processAudio 错误；App 停了自己的音源，管道写端仍开着，服务得到一条没有后续数据且没有 EOF 的流。Manager 没终态，朗读也可能一直保持捕获暂停。是否有 OEM 自行超时需实测。

[RecognizerIntent 官方合同](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE) 要求调用方关闭音频；支持该模式的 segmented session 以音频关闭结束。方向与验收：当前音源错误必须关闭其管道并结束当前识别，再决定是否另开平台采集。保存采集错误、FD 关闭、识别终态与状态恢复顺序。

## AC10 · P2 · 管道背压能卡住采集与自动时限检查

System 在 [410 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt:410) 的 rawAudioSink 直接 `out.write(bytes, 0, len)`。VAD 的 [403 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt:403) 在采集线程同步调用 sink；接口 [235 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt:235) 明确要求不能阻塞。所有空闲、后台、总时长检查都在该线程下一轮读取之前。

管道通过 createPipe 建立，没有非阻塞写入或写入 deadline；App 的 audioPipeRead 也留到 closeAudioPipe 才关闭。[Linux pipe 官方手册](https://man7.org/linux/man-pages/man7/pipe.7.html) 说明有限容量的阻塞管道写满后会等待读端消费。

条件反例：服务仍持有读端但停止消费 PCM，管道缓冲填满，write 卡住；捕获线程不再检查 15/30/300 秒限制。捕获 job 的普通协程 cancel 也不等于正在进行的阻塞 I/O 已结束。不能凭“每次 read 前都有时间判断”声称整体采集受这些时限约束。

方向与验收：让输出背压受控且可终止，在识别服务停止消费时结束本次采集；避免为此再建无界 PCM 队列。保存 stalled reader 的管道写入、超时、采集停止和 FD 释放时间线，确认后台与总时限在背压下仍有效。

## 固定 OpenMinis 对照

参考 `/tmp/openminis-review-20261004`，HEAD=`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只用本地 Git 读取已有对象，没有 fetch、构建或运行；相关片段不计本项目全文覆盖。

| 本轮问题 | 已核实的上游片段 |
|---|---|
| AC02 | [Provider 上游 stop](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt#L814) 先调用 detector.flush 收开放音频，再处理 held segments，交付终态；[上游 VAD](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt#L731) 已有开放段镜像与 flush。对应能力本项目缺失。 |
| AC03 | 上游 onSessionLimit 增加 flushVadOnManualStop，并明确不能只停麦克风；不据此认定上游所有开放音频和错误分支都已经正确。 |
| AC01 | 上游 Provider.cancel 增加 take generation，仍未停止 detector；supersedeTake 只取消 System replay fallback。本轮没有发现它补上 VAD 取消。 |
| AC04 | 上游两条 onVoiceStart 仍没有撤销短语 hold timer。 |
| AC05 | 上游 System.mapError 仍把 11/12 写成语言错误且漏 13。 |
| AC06 | 上游 Manager 仍 cancel 后立即 start；System.cancel 仍投递旧清理，新 start 仍先创建采集资源。 |
| AC07 | 上游 stopRecording 仍重新 currentEngine；cancel 加了 retryEngine 处理，但这不构成开始录音时的完整采集所有者记录。 |
| AC08—AC10 | 上游 VAD 仍忽略 n<=0；System.capture error 仍只 stopVad，raw sink 仍同步 write 管道。上游新增 watchdog 等机制未全文核验，不推断其整体故障持续时间与本项目相同。 |

因此应按实际功能挑选参考，不能整体移植上游录音实现，也不能用注释中的 iOS parity、flush 计划或旧故障日志证明当前分支已具备能力。

## 阅读覆盖与验证边界

本轮新增 10 个文件全文阅读，共 3,299 行；所有来源输出均完整，搜索、UI/仓库片段及上游对照不额外计全文。

| 文件 | 行数 |
|---|---:|
| [ProviderSpeechRecognitionEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt) | 528 |
| [SpeechRecognitionEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionEngine.kt) | 156 |
| [SpeechRecognitionManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt) | 395 |
| [SystemSpeechRecognitionEngine.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SystemSpeechRecognitionEngine.kt) | 1,099 |
| [VoiceActivityDetector.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt) | 596 |
| [WavSegmentMerger.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/WavSegmentMerger.kt) | 74 |
| [WavSegmentMergerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/speech/WavSegmentMergerTest.kt) | 97 |
| [EngineDegradationIsolationTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/speech/EngineDegradationIsolationTest.kt) | 100 |
| [CapturePauseLogicTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/speech/CapturePauseLogicTest.kt) | 148 |
| [ReplySpeechStateTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/speech/ReplySpeechStateTest.kt) | 106 |

测试只作阅读，没有新写或运行。WAV 测试覆盖已闭合、同格式段的拼接与长度重写；降级测试使用 FakeEngine，捕获暂停测试另写 Player/shouldResume 镜像，回复状态测试主要操作数据对象。它们没有调用真实引擎的 stop/cancel，也没有验证定时器、管道背压或语言重启；不能把这些断言当作录音端到端通过。

WAV 合并固定 44 字节头在现有 VAD 产生同格式段的边界内是明确约定，本轮没有把它直接判为任意文件解析漏洞。VAD 达到 maxSegmentSeconds 仅标记 pendingReason、不立即切段；首次启动的异步采集错误与 ready 发布竞态、locale 枚举晚到覆盖当前选择、native 回调内释放时机均保留为后续线索，不增加本轮编号。

[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json) 更新为：tracked 1,670 项，纳入 1,546 个文件；全文 652、片段 13、尚无全文记录 881，排除 124。全文累计 135,839 行，约 42.2% 文件完成全文阅读。全库目标仍未完成。

收尾核验：1,670 项 tracked 清单与 SHA-256、HEAD、tracked diff 保持不变，40 份既有 Markdown 报告哈希不变。本轮仅新增本报告、更新覆盖台账。所有设备验收均为后续方案，未实际执行。
