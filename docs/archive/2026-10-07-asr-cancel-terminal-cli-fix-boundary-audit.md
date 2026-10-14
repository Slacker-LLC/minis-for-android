# ASR取消、终态与CLI修复边界复核

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。本轮完整静态阅读2文件、1002行。旧175份报告及全部覆盖台账原样保留，另建本轮文件。未修改代码、编写或运行测试、录音或请求Provider、构建、模拟器/adb/Root/设备/云操作。没有新增足够证据支持的独立问题编号。

## AC01原缺失动作已补，不能继续说Provider cancel不通知VAD

[ProviderSpeechRecognitionEngine.kt:499](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt#L499) cancel现在设置cancelled、清recording，调用detector.cancel后清引用，并取消transcribeJob/holdFlushJob与清pendingSegments。原报告的“只有旧recording旗标变化、没有停止默认VAD”已经不成立。

复核[VoiceActivityDetector.kt:571](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/VoiceActivityDetector.kt#L571) cancel会设置检测器cancelled，再stopInternal清running、取消captureJob，并在vadLock内释放native对象。这里存在真实停止请求与native归属锁，不将它描述成空方法。stopInternal没有join，采集器最终释放与快速重启的设备时序仍未验证；不由一个cancel调用宣称所有旧回调/重启问题都已关闭。

Provider旧非VAD路径仍使用共享recording/cancelled且没有取消captureJob，start会重置旗标；但未找到useVad=false的现役正常配置入口，此分支只记录候选，不升级成默认模式的必现问题。

## AC02、AC03、AC04仍在

**AC02手动停止未提交开放段。** [ProviderSpeechRecognitionEngine.kt:491](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt#L491) stop清recording、调用detector.stop并置null，没有收集开放音频、读取held segments或交付本次终态。当前VAD.stop只停止且不发新段；没有flush方法。[SpeechRecognitionManager.kt:384](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt#L384) 外层先置FINISHING再stop，本次尚无已交付音频时没有最终结果可让它退出等待。沿用AC02。

**AC03自动限制无终态。** [ProviderSpeechRecognitionEngine.kt:395](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt#L395) onSessionLimit仅stopVad，没有listener.onFinal/onError。无闭合段时麦克风停止不等于识别流程结束；外层Listener只在final/error回IDLE。空闲上限与未闭合段的原条件仍成立。沿用AC03。

**AC04短段计时器仍截断再次开口。** [ProviderSpeechRecognitionEngine.kt:310](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt#L310) onVoiceStart只日志；不足2秒的闭合段安排5秒holdFlushJob。重新开口未撤销timer，只有下次onVoiceEnd才取消它。因此第一短段后4秒重新开口、1秒后旧timer到期时，可在第二段说话中stopVad，并按旧heldSeconds判太短。沿用AC04，不新增重复编号。

AC08非VAD read负返回仍统一continue，60秒限制按有效PCM字节推进；这不是默认VAD全路径复核，本轮不声称整个AC08修复或所有设备必定忙循环。AC05/06/09涉及System实现，未全文重读，不推断关闭。

## ST05 CLI失败码、ST06中断清理原路径已修

[SpeechOffloadHandler.kt:156](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt#L156) recognize返回JSON与succeeded，CLI据此选择exitCode0或1。创建失败、onError、等待超时均失败，不再无条件返回0。结合上一轮speak同步拒绝修复，ST05报告列出的两条原失败响应路径均已补结果贯通；这不表示异步TTS播放完成已经确认。

[SpeechOffloadHandler.kt:418](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt#L418) latch等待现在置于try/finally，任何退出设置finished并向main投递destroyRecognizerOnce，包括guest中断抛InterruptedException。[SpeechOffloadHandler.kt:329](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt#L329) queued创建任务先检查finished，取消早于main任务时不会再启动。识别器由AtomicReference持有、destroyed CAS控制清理，正常callback同样destroy。原ST06“中断绕过destroy”的路径已补；没有运行真实识别器，不宣称OEM释放及时性已实测。

main任务在创建器try外调用setRecognitionListener/startListening；若这些调用抛异常，没有局部异常转换，是进一步候选。本轮未核对平台异常条件和真实触发，不新增崩溃编号。回调与timeout并发的finished身份、duration/max未限幅、languages广播失败/超时仍返回空列表，也是候选，不能凭扫描直接变成新的线上故障。

## ST09录音与朗读协调仍未接线

[SpeechOffloadHandler.kt:315](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt#L315) CLI自行创建recognizer并startListening，没有suspendAllForCapture或发布SpeechRecognitionManager的STARTING/RECORDING。权限门控不等于录音与播放协调，因此旧ST09仍在。相比之下，Provider UI经Manager启动的协调属于另一条调用链，不能因为它存在就认定CLI也受保护。

文件source由受控WorkspaceFileClient/ExternalMountAccess检查，只返回exists，未回退任意Android host路径；尚未实现文件转写时明确返回not_supported与非零码。没有把host文案或历史注释当成真实host文件API，也没有恢复PRoot/minisd实现的理由。

## OpenMinis固定对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取既有ProviderSpeechRecognitionEngine第807—923行，无fetch。其stop先detector.flush开放段，再处理pendingSegments与本次终态；本地当前stop没有这些步骤，AC02仍是明确差异。上游cancel调用supersedeTake但未停止detector；不能照搬上游cancel来替代本地已新增的VAD取消。

上游片段不计本地全文覆盖。不以接口同名、历史任务或注释声称两边录音生命周期等价。

## 全文覆盖及旧文件保留

| 本轮新增全文 | 行数 |
|---|---:|
| [SpeechOffloadHandler.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/runtime/guest/SpeechOffloadHandler.kt#L1) | 470 |
| [ProviderSpeechRecognitionEngine.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/speech/ProviderSpeechRecognitionEngine.kt#L1) | 532 |

Provider文件分1—270、271—532完整读取；CLI分1—240、241—470完整读取，均无截断。Manager与VAD仅交叉复核相关片段，不计新增全文。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-asr-cancel.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-asr-cancel.json)。累计全文 **1307 / 1655文件、217976行**；partial5、not_registered343、excluded124，tracked1779。全库通读未完成。

交付核验旧175份报告与旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines与证据行号。仅新建本轮报告和台账。
