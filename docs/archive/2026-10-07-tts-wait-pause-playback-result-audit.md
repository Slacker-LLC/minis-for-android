# TTS等待、暂停与播放结果静态审查

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。完整阅读7文件、1635行。旧174份报告与全部台账原样保留，另建本轮报告和台账。未修改代码、编写或执行测试、播放录音、请求Provider、构建或操作模拟器/adb/Root/设备/云资源。

## RAE01 / P2 — Provider播放失败被返回为成功，系统语音回退被跳过

[ReadAloudPlayer.kt:497](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt#L497) playBytes没有播放结果，返回Unit。它的finish闭包对正常completion、onError及setup异常都通过cont.resume(Unit)结束挂起。setDataSource、prepare或start异常由局部runCatching捕获，只写日志并finish；onError也只日志、finish并返回true。

[ReadAloudPlayer.kt:487](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt#L487) speakViaProvider在playBytes正常返回后无条件return true；[ReadAloudPlayer.kt:393](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt#L393) speakOne收到true立即结束，不进入系统语音回退和已有不可用反馈。

静态条件反例：Provider返回非空音频，但当前MediaPlayer不能解析或初始化播放，prepare抛异常。setup捕获后正常resume，speakViaProvider返回true，句子被消费，系统TTS完全未尝试。异步播放错误走同一结果丢失路径。即使系统引擎可用，该句也可能无声丢失。没有声称任意Provider格式都失败，也没有设备复现。

与MP07停止后恢复播放、MP08共享音频文件、VAF部分音频帧失败以及ST10系统引擎异步错误区分：此项是Provider音频进入MediaPlayer后错误被折叠成成功。上游同样存在，不是本地独有回归。

方向：在现有播放链保留完成、失败与用户停止的区别，使播放失败进入既有回退；不能把用户明确stop当成失败后继续朗读。后续验收需覆盖坏音频/setup拒绝、异步onError、正常完成、用户stop和shutdown取消，保存原句、事件/返回结果、fallback是否启动及音频。本轮只记录，不实现或执行。

## 旧ST问题修复边界

| 旧编号 | 当前源码结论 |
|---|---|
| ST01 显式语言被覆盖 | 原路径已修：CLI把Locale传入speak，显式locale分支不走autoDetect；不可用语言返回false |
| ST02 初始化预算/错误分类 | 原路径已修：CLI使用INIT_TIMEOUT_MS=4000及READY/FAILED/NOT_READY，等待未就绪返回tts_not_ready |
| ST03 新voice probe未等待自身 | 原路径已修：probe有自己的callback、latch和有界等待，finally shutdown |
| ST04 参数跨请求残留 | 顺序请求案例已修：每次rate/pitch/volume赋缺省1.0；未由此宣称并发请求配置隔离 |
| ST05 同步拒绝却报成功 | speak CLI分支已修：speak返回false则exitCode1；本轮未复核recognize另一分支，不宣称整项闭环 |
| ST07 暂停放行下一句 | 普通回调路径已修，broken-listener估时分支仍遗漏暂停，见下文 |
| ST08 旧回调污染新请求 | 仍在：onDone/onStart/onError不校验utteranceId，旧main闭包仍可改新句状态 |
| ST10 异步错误当成完成 | 仍在：onError只清isSpeaking，外层等待结束后return true，没有失败结果 |

ST06/09不在本轮完整复核范围，不用相邻改动推断关闭。

## ST07估时分支仍跳过暂停等待

[ReadAloudPlayer.kt:627](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt#L627) progressListenerBroken=true时，只delay(estimatedSpeechMs(text))后return true，没有读取isPausedState，也未调用awaitUtteranceEnd。该flag会在普通等待超时后置true并持续保留。

条件顺序：同一播放器此前一次系统句子无完成回调导致flag置true；后续播放A，队列有B；用户暂停A，Manager.togglePause停止引擎、置paused=true；估时delay到期照常返回，worker提交B，Manager.speak清paused并QUEUE_FLUSH。用户还没恢复，B仍开始。沿用旧ST07，当前是部分修复，不再称普通等待也只看isSpeaking。

[UtteranceWait.kt:11](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/UtteranceWait.kt#L11) 普通等待同时看speaking或paused，并延长暂停期间deadline，避免长暂停吃掉预算。它使用wall clock，时钟跳变是候选，但本轮未证明当前系统发生或量化，不新增编号。

## ST08与ST10仍有明确源码路径

[TextToSpeechManager.kt:430](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt#L430) onDone把pausedAtIndex++投递main，不比对当前UUID；新speak会先清pendingTexts并重置index。旧A闭包晚于新B重置执行，可把B的speaking清false。两个onError也无归属校验直接清状态。线程改为main改善List并发，并不能代替请求身份检查。

[TextToSpeechManager.kt:453](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt#L453) 系统onError没有可交给等待方的失败结果。普通awaitUtteranceEnd在speaking=false且未paused时返回DONE，speakViaSystem随后true，已有notifySpeechUnavailable没有触发。沿用ST08/ST10。

MP07 stop/capture期间在途synthesize仍未取消或废弃，返回后继续playBytes；MP08固定cache/readaloud_tts.audio也仍在。speakSentence仍绕过enqueue静音/捕获门槛，SCAP02未因本轮wait修复而关闭。只复核这些旧路径，不新增重复编号。预初始化replay与speakQueued保留未入队pending的候选没有补足现役触发链，不强行列新问题。

## 现有测试与固定OpenMinis对照

TtsInitWaitTest五例覆盖2.5秒慢绑定、明确失败、无回调、已经就绪及中断；UtteranceWaitTest五例覆盖完成、长暂停、暂停时停止、无结束回调超时与无播放。都是现有helper测试源码，没有播放器真实路由、broken-listener估时暂停、旧UUID污染、MediaPlayer失败回退的集成证据。未运行或新增测试。

OpenMinis固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1完整读取既有ReadAloudPlayer对象，无fetch。上游playBytes也将completion/error/setup异常统一resume(Unit)，speakViaProvider随后true；RAE01共有。上游普通等待只看speaking，当前普通暂停等待已有实质改善；估时分支双方仍只delay。上游阅读不计当前覆盖。

## 全文覆盖与旧文件保留

| 本轮新增全文 | 行数 |
|---|---:|
| [SpeakOffloadHandler.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeakOffloadHandler.kt#L1) | 313 |
| [ReadAloudPlayer.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt#L1) | 691 |
| [TextToSpeechManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/TextToSpeechManager.kt#L1) | 458 |
| [TtsInitWait.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/TtsInitWait.kt#L1) | 30 |
| [UtteranceWait.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/UtteranceWait.kt#L1) | 29 |
| [TtsInitWaitTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/speech/TtsInitWaitTest.kt#L1) | 46 |
| [UtteranceWaitTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/speech/UtteranceWaitTest.kt#L1) | 68 |

全部从首行到末行完整读取；ReadAloudPlayer分1—350、351—691两段且无截断。历史报告搜索仅用于去重，不作为源码全文证据。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-tts-wait.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-tts-wait.json)。累计全文 **1305 / 1655文件、216974行**；partial5、not_registered345、excluded124，tracked1779。全库通读尚未完成。

交付验证旧174份报告与旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines和证据行号。只新增本文与台账。
