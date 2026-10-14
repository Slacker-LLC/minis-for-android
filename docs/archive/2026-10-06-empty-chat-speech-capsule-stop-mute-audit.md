# 聊天空白页、欢迎语与语音胶囊停止/静音静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文4文件、960行，确认2项P2语音控制问题。只读源码与已有测试，不改产品代码、不运行测试/构建/模拟器/adb/Root/设备/云/模型、不读取凭据。独立报告，旧报告不改。

## SCAP01 / P2：自动朗读已关闭时，手动朗读胶囊的关闭按钮无效

[SpeechPlayerCapsule.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/SpeechPlayerCapsule.kt:126) 的显示条件是enabled || speaking || synthesizing，故自动朗读关闭但手动播放时仍出现胶囊。第222行的关闭回调只调用VoiceOutputState.setEnabled(false)。[VoiceOutputState.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceOutputState.kt:164) 在当前值已经等于false时立即return，根本到不了第172行activePlayer.stop。

真实手动入口 [ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3225) 的回复朗读按钮调用toggleReplySpeech；[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5663) 获取实际player并逐句speakSentence。[ReadAloudPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:196) speakSentence直接清理文字、入队、设置isSpeaking，不检查isEnabled，也不把它设为true；player在第142行初始化注册为胶囊owner。这是实际可播放而enabled保持false的生产路径。

静态场景：保持“自动朗读回复”关闭，点击已有回复朗读，在播放中展开胶囊点×。开关仍为false、setter直接返回，当前播放器没有收到stop，播放继续。回复下方ReplySpeechControlBar自己的关闭调用stopReplySpeech，可以停止，本项不声称所有停止入口都失效。

与MP07不同：MP07讨论已经执行stop后在途合成恢复播放；本项即使正在普通播放、没有网络合成竞争，也完全没有调用stop。方向：关闭可见播放器与设置自动朗读偏好需要正确衔接，关闭还应终止对应逐句生产任务；仅取消setter早退并停止一条句子不能证明整条回复不再提交后续句子。本轮未改代码。

## SCAP02 / P2：逐句手动朗读绕过静音门槛，停止当前句子后仍提交下一句

[VoiceOutputState.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/VoiceOutputState.kt:177) setMuted(true)设置静音并调用activePlayer.stop。[ReadAloudPlayer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/ReadAloudPlayer.kt:324) stop清空队列、停止system/player并将isSpeaking=false。普通enqueue第245—249行会检查静音/canPlay，但speakSentence第196—204行直接写队列，不走这个门槛。

真实调用 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5664) 的逐句loop只检查activeMessageId；第5673行等待条件为player.isSpeaking或PAUSED，第5680行前后没有isMuted检查。静音没有取消replySpeechJob或清activeMessageId。于是用户在第一句播放中静音后，isSpeaking转false，等待结束，loop进入第二句并重新speakSentence。worker第144—153行直接speakOne，后者第381行以后也没有静音拒绝。因此后一条句子可在isMuted仍true时播放。

该反例不依赖停止前已在途的Provider请求：选用普通系统引擎、已开始播放的第一句，停止释放后由loop新提交第二句即可。实际OEM引擎回调与时间间隔未做设备验证，确认的是新句子入队/执行不检查静音的源码链。此前MP07的代次保护不能自动解决这个新提交入口的门槛遗漏。

方向：保留手动朗读可绕过自动朗读开关的语义，但尊重静音和捕获抑制，且生产loop要明确静音/关闭后的继续规则。不应简单用canPlay禁止所有手动朗读，因为那会错误要求先开启自动朗读。未运行或新增测试。

## 聊天空白页与建议卡核对

ChatEmptyState全文430行。12个卡片按2个常用+随机补齐显示4个，种子在当前composition保存，parseUses过滤非法计数；负variant用floorMod安全选择资源。当前count只用默认4，没有用不存在的负count调用制造take负数崩溃。recordUse在点击时更新偏好，同屏uses仍固定是保持当前卡片稳定的设计，未将它误报“点击不记忆”。

欢迎语slot第184行remember没有时间键，长期停留跨时段可能过时；与TI01同属基于当前时间的缓存缺刷新，在本轮记录额外位置，不新增重复问题编号。建议文本仍有/workspace命令示例，必须核对真实session路径别名/提示词后再判断隔离，不仅凭文案恢复旧runtime或认定绕过。

ChatScreen第2590—2610行真实接入空白页：400ms等待、messages为空且非streaming才显示。setupConfig与activeEntry为可观察状态，完成配置后切换常规建议卡；FirstRunCard第三步onClick空，但providerDone&&modelDone时父层setup=null，第三步不会作为配置完成后的真实聊天按钮。不能据空回调误报“完成配置仍无法聊天”。providerDone/modelDone对enabled实例和slot存在性的验证不等于实际模型可用性，是否应沿用统一readiness仍需调用链，暂不增加缺陷。

空白页与首轮卡均verticalScroll，不能把小屏/键盘时长内容直接断言按钮不可达。两个已有测试全文42/50行只验证slot映射、随机选择和偏好字符串，不覆盖时间刷新或真实设置可用性；没有运行。

## 胶囊其它路径与OpenMinis对照

SpeechPlayerCapsule全文438行。实际ChatScreen第4055行接入工具条高度与FAB高度用于避让；模型picker使用现有VoiceOutputPickerSheet，非未接线页面。10秒idle collapse、speaking触发收起、mute双击入口均已核对。小按钮触摸/模型标签截断与屏幕阅读器表现未实测，不自动增加可访问性编号。

只读固定 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其ChatEmptyState同路径不存在，不能说上游无其它空白页。其SpeechPlayerCapsule第155行相同enabled||speaking||synthesizing、第361行关闭setEnabled(false)，VoiceOutputState第204—214行同样setter早退；SCAP01是同源控制缺口，但上游手动入口未在本轮完整确认。上游ReadAloudPlayer没有speakSentence匹配，SCAP02当前逐句入口不能据此称上游也有完全相同故障。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [ChatEmptyState.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatEmptyState.kt:1) | 430 |
| [SpeechPlayerCapsule.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/SpeechPlayerCapsule.kt:1) | 438 |
| [GreetingSlotTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/GreetingSlotTest.kt:1) | 42 |
| [SuggestionWindowTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/SuggestionWindowTest.kt:1) | 50 |

新增全文输出完整。一次合并相关查询输出截断，ReadAloudPlayer门槛、worker、stop、speakSentence与ChatViewModel生产loop已独立重读；不重复登记已有full或升级ChatScreen/ChatViewModel partial。

累计全文 **1295 / 1546文件、248423行**；partial13，not_registered238，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前124份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
