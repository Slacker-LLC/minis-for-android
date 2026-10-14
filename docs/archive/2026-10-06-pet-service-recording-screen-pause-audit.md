# 宠物服务的录音归属与熄屏暂停静态审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项确认缺陷：P2、P3 各一项**；全文读取 PetOverlayService 910行。只记录问题，未修改源码，未运行测试、构建、模拟器、设备、Root或云任务；旧报告不改。

## PS01 / P2：销毁宠物服务会停止其它界面发起的录音

[PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:185) onDestroy 无条件调用 stopVoiceCapture。该方法第420行只判断全局 SpeechRecognitionManager 是否非IDLE，随后 stopRecording；没有保存或核对本次宠物采集的所有者。toggleVoice 第306行同样把任何全局非IDLE识别当作自己的录音，再次点击会停止它。

主聊天 [InlineVoiceInputPanel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:338) 也使用同一个 SpeechRecognitionManager.startRecording。该管理器 [SpeechRecognitionManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/SpeechRecognitionManager.kt:384) 在RECORDING/STARTING状态停止当前引擎并进入FINISHING，没有调用方身份参数，也无法判断是不是宠物录音。

静态反例：宠物服务已启用，但没有发起语音；用户在主聊天开始语音输入，管理器处于RECORDING。通过宠物通知的“停止宠物”动作（服务第888—900行）走 ACTION_STOP，第161行 stopSelf，销毁时第191行停止主聊天的采集。宠物完全没有启动过此次录音，却终止了它。这里确认结束采集被错误归属，不断言最终转写必然全丢或特定引擎一定返回哪种错误；引擎stop可能提交已经录到的部分内容。

关闭宠物聊天窗口第439行也调用同一方法；只要当时全局录音属于其它入口，同样存在跨界面停止。它与已记录 AC07 不同：AC07 是停止时重新挑选了错误引擎；本项即使修复AC07、保留正确引擎，宠物仍会错误停止主聊天拥有的正确引擎。两者修复条件不同。

方向：记录/校验采集归属，宠物只能结束自己启动的录音；其它入口正在录音时提示占用或按照明确交接流程处理，不把全局state当作所有权。未实施修改，也未采集音频。

## PS02 / P3：熄屏暂停可被后续状态更新重新启动

[PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:126) setAwake(false) 记录screenAwake=false，暂停sprite和behavior并移除randomMood。PetBehavior.pauseTimers只是stop，保留isTucked，没有持续paused标志。服务中screenAwake仅在首次挂载时决定reset或pause，后续 applyAgentState 不检查它。

静态反例：用户从菜单收起宠物（或关闭巡游后由自动隐藏收起），isTucked=true；屏幕随后关闭，setAwake(false)移除计时器；后台Agent收到RUNNING状态，ACTION_SET_STATE 调用 [PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt:208) applyAgentState，第210行 wakeIfTucked仍执行。已全文读取的 [PetBehavior.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetBehavior.kt:94) 清掉isTucked并启动返回动画，第103行在结束时 rescheduleIdleTimers，即使screenAwake仍为false，也重新排巡游/隐藏任务。忙碌时stepWander虽不移动，仍会重排定时器。

因此熄屏暂停并未保持到SCREEN_ON：后续Agent状态可重新使Handler更新窗口位置并持续触发行为回调。第828行配置变化无条件behavior.reset也存在相同缺少暂停边界的入口。sprite自身有paused检查，状态改变后的tick仍会退出；本项不把它说成所有图集帧在熄屏继续绘制，也不虚构耗电量、CPU时间或设备唤醒次数。手机进入深睡可能推迟uptime/Handler执行，不改变逻辑上计时任务被重新排入的事实。

方向：暂停应是持续状态，异步/状态更新可以记录期望结果，但只有允许恢复时才启动计时器；或在服务各重启动作处统一按screenAwake处理。保持Agent状态记录和醒屏恢复语义。没有运行屏幕或后台任务验证。

## 其它生命周期核验与未计数候选

onCreate先采样PowerManager.isInteractive，注册屏幕广播；异步挂载完成后再按screenAwake处理，已经覆盖初次恢复时漏收SCREEN_OFF的情况。hidePet先递增mountGeneration再取消解码任务；解码完成第600行比较generation。不能把已存在的陈旧挂载防护描述为缺失。

onDestroy取消serviceScope、askJob，移除randomMood与transientReset，移除窗口并取消气泡动画。未挂载但已解码bitmap的取消路径是否需要立即显式回收是资源回收候选，不能只凭缺少detach声称永远内存泄漏；对象仍可能由GC回收。

voiceStarting是共享Boolean，异步配置等待允许取消、重新开始；旧开始任务与新任务之间是否存在代次混淆仍需完整管理器时序，暂不新增确认项。迟到识别回调通过当前chatWindow写入，没有窗体identity比较；不能在尚未证明具体引擎迟到回调时强行判为旧音频自动发送。

聊天窗口设置SOFT_INPUT_ADJUST_RESIZE、NOT_TOUCH_MODAL；服务exported=false，前台类型specialUse。本轮没有运行Android平台权限/前台服务验收，不把静态声明当作全部OEM准入证据，也不新增泛化平台故障。

气泡/菜单使WRAP_CONTENT尺寸变化，moveWindow不每帧clamp是为了允许tuck越出屏幕；不能按“未clamp”直接算越界漏洞。几何变化只夹现有位置并reset行为，聊天窗宽度取当前宽度的较小值，可能不能在再扩屏时恢复理想宽度，暂列展示候选。

此前PI01、PC01/PC02、PB01、PP01仍保持原报告；此次服务全文核验不重复统计它们。

## 固定上游对照与全文覆盖

只读本地 `/tmp/openminis-review-20261004` 固定 HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` Git树，未fetch；上游无本轮pet目录同路径实现。没有用旧minisd/PRoot路径解释Android服务，也未调用宠物插件。

| 本轮全文文件 | 行数 |
|---|---:|
| [PetOverlayService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/pet/PetOverlayService.kt) | 910 |

按1—230、231—460、461—690、691—910逐段读取，全部返回完整，没有用此前片段阅读代替全文。累计全文 **1183 / 1546 个文件、228902行**；partial13，not_registered350，excluded124。全仓通读尚未完成。

完成时校验全部1670 tracked路径SHA-256、字节数及正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前103份报告哈希、新报告文件链接/行号及全文批次总计。只新建本报告并更新覆盖台账，没有访问真实音频、屏幕、密钥或账号。
