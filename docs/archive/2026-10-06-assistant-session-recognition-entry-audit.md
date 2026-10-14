# 系统助理会话与语音识别入口静态审查

日期：2026-10-06。本仓固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2**；全文阅读 9 个文件、386 行。只读代码与已有测试，未改产品源码，未运行测试/构建/模拟器/设备命令或云任务。独立报告，旧报告不改。

## AS01 / P2：系统助理发送不存在的语音路由

[AssistSession.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistSession.kt:26) 的 VOICE_ACTION_URI 是 minis://action/voice；[AssistSession.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistSession.kt:38) 用显式 MainActivity Intent 携带它。[DeepLinkHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/deeplink/DeepLinkHandler.kt:120) 只识别 new_chat、voice_chat、camera_chat，voice 落入 Unknown。这与会话注释承诺的“新语音聊天、自动打开语音输入”不符。

热启动：[MainActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:742) 的 onNewIntent 把 data 交给 handleDeepLink；[MainActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:754) 解析成 Unknown，末尾 else 不操作。只有 [MainActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:799) 的 NewVoiceChat 分支设置 START_VOICE 并创建新 draft route，助理传来的 URI 无法进入。

冷启动：[MainActivity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MainActivity.kt:509) 同样先解析；Unknown 会使用已有恢复会话或默认启动规则。[AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:469) 只有 NewVoiceChat 才设置 START_VOICE 与新聊天路径。因此冷启动也不从这个助理请求触发语音。设备原有 pending action 或其它同时到来的事件不在本项条件中，不能把它们偶然触发语音当作助理 URI 正常。

影响限定：系统成功显示该助理 session 且 startActivity 成功时，App 可以被带到前台，但新语音会话动作被丢弃；没有断言 ROM 一定授予助理角色，也没有宣称语音识别引擎本身损坏。现有快捷方式使用 voice_chat，进一步说明是助理生产端与解析端不一致；快捷方式 targetPackage 的历史身份问题不在此重复计数。

修复方向：使用已有可解析的语音动作，核对冷启动与热启动都创建预期会话并启动输入。只记录，不修改常量、不启动设备。

## AS02 / P2：助理防抖用墙上时钟，回拨后可能长时间屏蔽启动

[AssistSession.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistSession.kt:31) 使用 System.currentTimeMillis；[AssistSession.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistSession.kt:32) 直接以 now-lastLaunchAt < 1500 结束 session，lastLaunchAt 仅在真正进入启动分支时更新，保存在 companion 中。

静态反例：同一 App 进程先在时间 T 唤起一次，再将设备时间回拨一小时；随后每次 now-T 都为负且小于 1500，全部提前 finish，lastLaunchAt 不变。入口要等墙上时间重新超过 T+1500 才恢复，或依靠进程重启清掉内存值。原定 1.5 秒的防抖因时间变化成为约一小时的抑制。这与旧诊断报告中的 HangDetector 墙钟问题是不同调用点和影响，不重复计数为同一文件问题。

普通时间不变时不存在这个长时间窗口；向前跳变主要解除防抖，不能据此声称必然连续重复启动。没有实际改系统时间或测量设备行为。

修复方向：用适合计算间隔的单调时钟，并明确启动失败是否消耗防抖窗口；本轮只确认回拨条件，未修改代码。

## 识别委托、声明与暂不计数范围

RecognitionDelegateSelector 过滤自身 component，优先采用仍在候选中的当前配置服务，否则只选 system/updated-system app。未配置的任意第三方不会被自动选中；已明确配置的第三方可保留，不能把这个行为说成无条件只用系统服务。已有三条测试覆盖配置保留、自身排除和未配置第三方拒绝，仅阅读未运行。

AssistRecognitionService 在主线程创建 SpeechRecognizer，转发 ready/开始/结束/音量/缓冲/部分/最终结果及 error，最终结果或错误后销毁 delegate。创建失败、没有 delegate 均有错误回执。没有查验真实 OEM recognizer 的行为或获取麦克风音频。

桥接忽略 onEvent、未转发较新识别协议的其它回调，且 activeSession 只有一个；是否会影响多客户端或 segmented session 需要对照项目支持的调用协议和 Android 平台契约，目前不算已确认缺陷。迟到回调是否作用于新会话也缺平台执行证据，未夸大为必然交叉返回。

voice_interaction_service.xml 指向本轮读到的 SessionService/RecognitionService，Manifest 的对应绑定权限与识别 query 已片段核对。minis_accessibility_service.xml 请求全事件、多窗口、手势、截图与触摸探索；是否符合特定用户体验还需真实设备证据，仅登记全文，不能单凭声明判为实现错误。前一轮健康控制实现已完成全文，本轮剩余无障碍声明读完后转向助理链。

固定 OpenMinis `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读 tree 未见 AssistSession/RecognitionDelegateSelector/voice_interaction_service 同路径文件，git show AssistSession 返回 path 不存在后以完整 tree 检索核对；未 fetch。不存在同路径不能证明上游没有任何形式助理功能，故不作共享缺陷判断。

## 全文覆盖与验证

| 本轮全文文件 | 行数 |
|---|---:|
| [AssistRecognitionService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistRecognitionService.kt) | 191 |
| [AssistService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistService.kt) | 14 |
| [AssistSession.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistSession.kt) | 49 |
| [AssistSessionService.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/AssistSessionService.kt) | 11 |
| [RecognitionDelegateSelector.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/voice/RecognitionDelegateSelector.kt) | 35 |
| [assist_recognition_service.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/xml/assist_recognition_service.xml) | 4 |
| [minis_accessibility_service.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/xml/minis_accessibility_service.xml) | 13 |
| [voice_interaction_service.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/xml/voice_interaction_service.xml) | 6 |
| [RecognitionDelegateSelectorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/voice/RecognitionDelegateSelectorTest.kt) | 63 |

累计全文 **1084 / 1546 个文件、216278 行**；partial 13，not_registered 449，excluded 124。全仓通读仍未完成。MainActivity、DeepLinkHandler、AppNavigation 已登记全文，本轮只片段复核不重复计数；片段输出截断处已单独重读。未覆盖清单输出不是源码阅读依据，未把其截断内容登记为全文。

完成时核对全部 1670 tracked 路径、SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 83 份报告哈希、本报告本地链接/行号和批次全文总计。只新建本报告并更新覆盖台账。
