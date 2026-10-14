# 未接线voicecall模块的生命周期静态审查

日期：2026-10-06。固定本仓HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。全文读取voicecall全部13个源码文件及2份测试，共15文件、991行。**本轮不新增已确认生产缺陷计数**，记录未接线模块的明确实现缺口及证据限制。未改产品代码，未执行测试、构建、模拟器、设备、ASR/TTS、Root、云或模型请求；旧报告不改。

## 接线事实：没有找到真实通话入口与端口实现

本仓src全范围检索CallController构造、MinisCallRuntimeAdapter构造、BloubAvatar调用、AssetCache.getSvg和三个Call端口实现，排除voicecall本目录后没有匹配。main/java相关类名引用均在该模块内部；测试构造Controller/Adapter并实现Fake端口。没有生产ASR、PetChatEngine、ReadAloudPlayer端口包装类。

[MinisCallRuntimeAdapter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/runtime/MinisCallRuntimeAdapter.kt:15) 注释称“生产通话Runtime”、后续阶段注入真实实现，不能据此认定当前已经接线。模型CallMode定义VOICE/VIDEO/SHARE仅为状态枚举，没有摄像头/屏幕共享实现；BloubRenderer自行Canvas画角色，不调用AssetCache。不能用这些类的存在或测试名推断当前App已提供完整实时语音、视频或屏幕共享功能。

当前聊天语音输入和SpeechPlayerCapsule等既有入口仍是其它运行链，本轮没有把voicecall孤立状态机缺口套到那些现役实现。固定OpenMinis tree在voicecall目录无匹配同路径，不能提供同模块生产对照。只读本地固定Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。

## 未接线实现缺口A：start没有换代，上一轮回调可污染新启动

[MinisCallRuntimeAdapter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/runtime/MinisCallRuntimeAdapter.kt:33) 注释声称start/stop/interrupt递增generation；实际start第37—40行仅muted=false、startListening，没有递增或取消上一轮。startListening第82行捕获当前generation，也不在每次新的聆听轮增代。

纯控制流反例：同一Adapter连续start两次；两个input.start注册的回调gen相同，第一轮迟到final通过第90行并启动模型请求。CallController.startCall第33行没有active检查，重复start动作会再次调用Runtime.start。即便调用方未来限制重复start，每次播放后startListening也复用gen，前一聆听轮的迟到partial/final仍可能被接受。是否真实ASR实现回调只会单次、调用方是否限制重复启动，需要接线后验证。未把假设端口行为判为当前用户故障。

## 未接线实现缺口B：onError协程开始后不复核代数

input.onError第93行在调用onError前检查gen，但 [MinisCallRuntimeAdapter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/runtime/MinisCallRuntimeAdapter.kt:126) 内部scope.launch开始后直接emit CONFUSED/ERROR及字幕，直到delay之后才检查gen。

反例条件：scope在排队dispatcher，旧ASR error在gen有效时排入协程，随后stop/interrupt先执行令generation递增，排队error协程再运行。它仍覆盖状态/字幕，尽管900ms后的重新聆听会被拒绝。onFinalText第100行有入口gen检查，onError没有同等检查；两者不能互相证明安全。

CallController第95—100行无active过滤，挂断后晚到Runtime事件仍能改ai/muted，但不把active重新设true。报告只称挂断后状态污染，不能称必然重新进入通话。SharedFlow没有replay，额外buffer不等于新订阅者能拿到既往start状态；Controller与Runtime创建时序也需未来接线核对。

## 未接线实现缺口C：静音/打断状态与重启聆听不一致

setMuted(true)第54—56行递增generation、取消input、停止output，但不调用model.cancel；进行中的ask可能继续耗资源，返回后第109行会丢弃结果，不能称它必然仍播放。静音过程若model已经失败、正在900ms delay，第121行只查gen，这一特定delay由哪次代数创建需区分，不能简单说所有失败都会绕过静音。

interrupt第63—67行无muted判断，先取消再无条件startListening；若muted=true，仍重启input.start，第87行partial只检查gen，不检查muted。final第90行因muted被丢弃，但识别已启动，和CallRuntime第23行“静音停止录音”约定不一致。adapter缺active状态，stop后直接sendText/startMuted(false)也可启动流程，需要调用方明确生命周期边界。没有真实输入实现/页面动作证据，不新增生产隐私/录音缺陷。

## Controller、Fake和视觉状态的其它边界

CallController用scope订阅事件、endCall重置state；tick只在active累加，minimize保持active、switchMode保持状态。preMute仅赋值，没有被读，但effectiveBloubState通过muted覆盖ai实现视觉静音，不以未读变量认定正常静音无法恢复。CallUiModel默认DeepSeek/deepseek-v4、字幕包含演示日程与“已修改会议”的固定句子；没有实际页面调用，不把演示文案当真实Agent伪造操作结果。

FakeCallRuntime.stop为空，sendText内部任务仍按延时发状态；dispose才取消scope。它标为演示Fake，生产目录内存在不等于已经被发行入口选用。BloubRenderer状态ERROR/IDLE落同一默认眼形，geometry平滑与动画使用固定参数；未做视觉验证，不称SVG还原错误。AssetCache缓存读取失败空字符串、mutableMap无同步，但只有定义/无调用，不新增用户缓存缺陷。

## 已有测试能证明什么

MinisCallRuntimeAdapterTest以Unconfined Fake端口覆盖成功闭环、interrupt后的旧final、mute/unmute、模型失败后回听、stop后的final拒绝。模型ask、TTS speak都立即返回，不覆盖ask/TTS挂起期间取消；firePartial为空，不能证明partial隔离。静音测试第103行创建的output并未注入Adapter，也没有对其stop进行断言。onError排队窗口、重复start、静音后interrupt均未覆盖。

CallControllerTest通过同步Fake验证start/end、模式、mute视觉、minimize、tick、sendText；没有验证挂断后晚到事件。测试中的等待和Fake初始化全部只是读代码，未执行或新增测试。不能把测试存在当作生产接线或设备音频路径通过的证据。

## 全文覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [AssetCache.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/bloub/AssetCache.kt) | 18 |
| [BloubAvatar.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/bloub/BloubAvatar.kt) | 16 |
| [BloubRenderer.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/bloub/BloubRenderer.kt) | 162 |
| [BloubStateMapper.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/bloub/BloubStateMapper.kt) | 44 |
| [CallController.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/controller/CallController.kt) | 107 |
| [BloubSize.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/model/BloubSize.kt) | 19 |
| [BloubState.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/model/BloubState.kt) | 13 |
| [CallMode.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/model/CallMode.kt) | 8 |
| [CallUiState.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/model/CallUiState.kt) | 39 |
| [CallPorts.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/ports/CallPorts.kt) | 42 |
| [CallRuntime.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/runtime/CallRuntime.kt) | 31 |
| [FakeCallRuntime.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/runtime/FakeCallRuntime.kt) | 53 |
| [MinisCallRuntimeAdapter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voicecall/runtime/MinisCallRuntimeAdapter.kt) | 144 |
| [CallControllerTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/voicecall/CallControllerTest.kt) | 154 |
| [MinisCallRuntimeAdapterTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/voicecall/MinisCallRuntimeAdapterTest.kt) | 141 |

15文件均完整输出，无截断登记。累计全文 **1226 / 1546文件、235336行**；partial13，not_registered307，excluded124。全仓通读尚未完成。

完成时校验全部1670 tracked路径SHA-256、字节数与正数文本行数，本仓/上游HEAD、tracked/staged空diff、此前112份报告哈希、新报告链接行号、全文批次总计。只新建本报告并更新覆盖台账。
