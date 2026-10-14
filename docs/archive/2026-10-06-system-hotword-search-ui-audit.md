# 系统唤醒词恢复、搜索调用与 SystemUI 全文审查

日期：2026-10-06。本仓固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮全文阅读 12 个文件、1084 行，**未新增已确认独立缺陷**。只读源码及已有测试，另建报告，旧报告保持不变。没有运行测试、模拟器、Root、设备或云任务，没有加载 Xposed、启动搜索或录音，没有修改产品代码。

## 核对结果

HotwordSelfHealHooks 只在主显示器熄屏、开关开启且设备非交互时安排恢复。每次 retry 都复查 generation、开关与交互状态；主显示器亮屏会增加 generation 并清除待运行 callback。最多三次、首次 1200 ms、后续 1400 ms，采用 uptime 冷却，而不是无限重试。Handler.postDelayed 的返回值未检查，若 Handler 队列退出可能未实际安排；当前 handler 来自 PhoneWindowManager 或 main looper，尚无确认的退出/恢复故障链，不把这点升级为新缺陷。

AssistantHotword 从 VoiceInteractionManagerService 的 boot phase 捕获现有 stub，恢复时同步 stub，解析当前 assistant 的 ComponentName.packageName，仅接触 Google assistant 的现有 software detector session。没有重新创建任意助手或强行替换 role。alreadyRunning 返回 false 会让恢复调度再尝试，但次数有界；此处不等于无限重复开启麦克风。厂商字段、内部方法参数、实际 detector 状态仍待设备确认，安装日志不能证明声音唤醒生效。

SystemUiHooks 依次从 getContext 和声明字段取 Context；关开关、无上下文、Google contextual entry/service 不可用、trigger=false 时继续原 OCR 方法。成功触发后尝试厂商振动 helper；振动反射失败返回 false 并记录，未在该分支故意再调用原 OCR 造成双启动。onLongPressed 的返回类型未显式核对，需要对目标 ROM 的实际声明继续验证；当前没有不同返回类型的源码证据，不凭猜测新增设备崩溃项。

CircleToSearchInvoker 先检查 Google 安装、声明的 contextual search Activity 能解析及 Binder alive；触发时重新获取 binder，并匹配带 config 或不带 config 的服务接口。反射调用成功返回 true 只证明调用提交没有在这里抛错，**不证明 Google 页面已经显示**。现阶段未核对服务异步处理与目标 Activity 状态，不能把这个布尔值解释成完整端到端成功。

并发方面，generation 检查与后续取消/清理并非整体锁定；若 screen on/off callbacks 真在不同线程交错，仍需核对旧任务是否会清理新任务的状态。当前注释声称使用 PWM 自有 handler，实际 callback 所在线程需要平台证据，故仅列候选，不提前认定竞争已可达。

本轮日志 helper 测试只验证类型/token 转换及节流规则；CircleToSearchInvoker 的异常日志仍包含 failure.message，不能据 safeLogType 存在宣称所有 Xposed 日志都不含异常 message。是否有真实敏感内容来源尚未确定，本轮没有读取 logcat 或账号资料。

## 测试与上游边界

六个已有测试只静态阅读：显示器/冷却/尝试次数、SystemUI lookup 顺序、安装状态记账、继承链反射、log token 与节流窗口。没有执行。纯规则测试不覆盖 Android Handler 调度、服务 stub/内部字段、Binder 远端行为、OEM haptic 或 Google 页面最终显示。

固定 OpenMinis `/tmp/openminis-review-20261004`，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读 Git tree 未找到同路径 AssistantHotword/HotwordSelfHeal/SystemUI Xposed 实现，未作同实现比较。未 fetch；Eta 来源只是本源码的移植声明，本轮不把它作为独立验证证据。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [AssistantHotword.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/AssistantHotword.kt) | 136 |
| [CircleToSearchInvoker.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/CircleToSearchInvoker.kt) | 148 |
| [HotwordSelfHealHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/HotwordSelfHealHooks.kt) | 216 |
| [HotwordSelfHealPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/HotwordSelfHealPolicy.kt) | 43 |
| [SystemUiHooks.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/SystemUiHooks.kt) | 162 |
| [SystemUiOcrPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/xposed/system/SystemUiOcrPolicy.kt) | 22 |
| [HookInstallJournalTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/HookInstallJournalTest.kt) | 82 |
| [HookSupportTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/HookSupportTest.kt) | 86 |
| [LogSafetyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/LogSafetyTest.kt) | 36 |
| [LogThrottleTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/LogThrottleTest.kt) | 56 |
| [HotwordSelfHealPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/system/HotwordSelfHealPolicyTest.kt) | 65 |
| [SystemUiOcrPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/xposed/system/SystemUiOcrPolicyTest.kt) | 32 |

累计全文 **1063 / 1546 个文件、213547 行**；partial 13，not_registered 470，excluded 124。全仓通读尚未完成。其它系统 caller、无障碍保护与电源键 hook 仍待继续全文审查，本轮不提前登记。

完成时核对全部 1670 tracked 路径、SHA-256、字节数和正数文本行数，本仓/固定上游 HEAD、tracked/staged 空 diff，此前 80 份旧报告哈希，本报告文件链接，以及台账全文与批次总计。只写新报告与覆盖台账。
