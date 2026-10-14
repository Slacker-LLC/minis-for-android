# HyperOS 手势与助手身份检查静态审查

日期：2026-10-06。本仓固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2**；全文阅读 10 个文件、841 行。只读源码和已有测试，另建报告，旧报告不改。没有修改产品代码、运行测试、Root/设备、模拟器或云任务，没有改变默认助手。

## HA01 / P2：系统助手检查使用子串匹配，把不同包或类名误认为 Minis

[AssistantLaunch.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/AssistantLaunch.kt:93) 的 isConfiguredAssistant 读取 Settings.Secure 的 assistant 值，并在第 96 行使用 contains(ownPackage)。[AssistantLaunch.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/AssistantLaunch.kt:79) 对 MINIS 目标标记 requiresAssistantRole=true，[AssistantLaunch.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/AssistantLaunch.kt:111) 又以该函数作为发起启动的前置条件。

静态反例：ownPackage 为 llc.slacker.minis，而设置值为 llc.slacker.minis.other/.AssistantService，contains 返回 true，实际组件所属包是 llc.slacker.minis.other。另一个包的类名恰好含 llc.slacker.minis 也会被误识别。测试字符串仅是可复核的代码推导，本轮没有安装同名应用或改系统设置。

随后 [HyperOsPowerHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsPowerHooks.kt:121) 的电源键拦截仍会尝试启动实际 llc.slacker.minis，并在启动成功时返回 true，不执行原 ROM 助手路径。因此当用户模块目标为 MINIS、系统助手却是上述不同组件时，本地“必须已配置为系统助手”的约束没有正确判断。这里只确认产品前置判断错误；不声称它能授予 Android 助手角色、绕过系统权限或保证任意 OEM 都允许后台启动。若系统拒绝启动，当前 launch 返回 false，电源键入口仍回到 chain.proceed。

[Android ComponentName 官方说明](https://developer.android.com/reference/android/content/ComponentName#unflattenFromString(java.lang.String))提供组件字符串解析及独立 packageName，明确不能把整个字符串含有包名等同于属于该包。修复方向：复用已有组件解析/助手角色查询方式，准确比较 package identity；无法解析或无法确认时保留 OEM 行为。没有实现修改。

现有 AssistantLaunchTest 验证 targetFor、requiresAssistantRole 标志与 parse 默认值，但没有验证 isConfiguredAssistant 对不同包/类名的判断；未运行测试，也未新增单元测试。后续设备验收应比较 Minis 自身、以 Minis 为前缀的另一包、其它包但类名含该字符串、空值与读失败，并保存角色持有者、实际启动目标和原手势回退证据。本轮未执行。

## 其余全文核对与未计数候选

HyperOS power policy 同时匹配 assistant function 与两个 power 来源，不仅按 function 判断；普通相机、SOS/其他 source 会继续原方法。ScreenSearchRequest 同时要求 action、functionKey、triggerType 与导航来源，VoiceService hook 核对类、参数、返回类型和声明类。成功发送搜索后用 stopSelfResult(startId) 处理当前 start，而不是无条件 stopSelf；清理异常不会再次调用原入口。

LegacyGesture 使用 WeakHashMap 和 LongPressGesture 的弱 View 引用；detach 会取消 pending，移动超过 slop、多指和非 MOVE 会取消等待。长按触发后的下一事件被替换成 CANCEL。其后是否仍应持续吞掉整个余下触摸序列、pending 期间抑制 recents pre-start 后失败是否影响 OEM 动画，需要明确真实 ROM 调用顺序，当前证据不足以新增独立缺陷。没有把历史 legacy 命名自动当成应删除路径。

AssistantLaunch 对已 resolve 的第一 action 若 startActivity 抛错，会直接 false 返回而不尝试下一 action；这与“两个 action 都尝试”的概括有差异，但仍需核对实际客户端组件及预期回退行为，本轮作为候选不新增计数。已检查入口会在 false 时保留 OEM 路径，不称之为吞掉全部手势。

## 固定 OpenMinis 对照

只读已有 `/tmp/openminis-review-20261004` Git tree，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。未找到本轮 AssistantLaunch/HyperOS Xposed 同路径文件，因此不能认定 HA01 是该固定 OpenMinis 的共享缺陷。源码声明 Eta 移植来源，本轮未下载 Eta，未将注释作为上游代码证据。没有 fetch 或运行模块。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [AssistantLaunch.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/AssistantLaunch.kt) | 150 |
| [HyperOsLegacyGesture.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsLegacyGesture.kt) | 102 |
| [HyperOsLongPressGesture.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsLongPressGesture.kt) | 87 |
| [HyperOsPowerHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsPowerHooks.kt) | 134 |
| [HyperOsPowerPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsPowerPolicy.kt) | 19 |
| [HyperOsScreenSearchHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsScreenSearchHooks.kt) | 119 |
| [HyperOsScreenSearchRequest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/hyperos/HyperOsScreenSearchRequest.kt) | 56 |
| [AssistantLaunchTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/AssistantLaunchTest.kt) | 75 |
| [HyperOsPowerPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/hyperos/HyperOsPowerPolicyTest.kt) | 32 |
| [ScreenSearchRequestTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/hyperos/ScreenSearchRequestTest.kt) | 67 |

累计全文 **1042 / 1546 个文件、211209 行**；partial 13，not_registered 491，excluded 124。全仓通读尚未完成。三个已有测试只静态阅读，不能证明 OEM 手势或助手启动在设备运行通过。

完成时核对全部 1670 tracked 路径、SHA-256、字节数与正数文本行数，本仓/固定上游 HEAD、tracked/staged 空 diff，此前 78 份旧报告哈希，本报告文件链接与行号，以及台账全文与批次总计。只写新报告及覆盖台账。
