# Android日志、APK暂存与任务取消审查

日期：2026-10-07。固定远端HEAD `0b96f7aa2a46c313b896a6f66501586e7caba81a`。完整静态阅读4文件、829行。旧179份报告及全部台账原样保留，另建本轮文件。未修改代码、编写或运行测试、读取设备日志/真实APK、启动worker、安装应用、构建、模拟器/adb/Root/设备/云操作。

## ALW01 / P3 — 日志watch以字符数截断字节预算

[AndroidLogManager.kt:125](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt#L125) watch用fresh.toByteArray().size计算bytes及剩余allowed；当bytes超限时却fresh.take(allowed)，take的单位是字符。随后按text.toByteArray().size增加retainedBytes，没有再次收敛到allowed。标题声明MAX_WATCH_BYTES=256*1024并非实际UTF-8正文上限。

明确反例：剩余预算30字节，fresh是20个汉字、UTF-8共60字节。bytes>allowed，take(30)仍返回全部20汉字，追加60字节，超过余量30；计数只阻止后续追加，不能撤回这次超额。若首批主要为多字节文字，也可超过完整256KiB正文预算。另追加换行/capped marker没有计入retainedBytes。

影响限于有界输出的字节预算不准确；不是无限输出或已测OOM，不将root日志权限扩大成泄漏结论。与底层runner单行读取边界的旧问题不同，本项发生在已取得fresh后的保留输出阶段。

方向：复用现有按UTF-8预算截断能力，正确预留换行/提示预算，避免切开码点；未来验证ASCII/CJK/emoji、预算边缘与多批次输出，保存实际UTF-8长度与最终JobRegistry正文。仅记录，未实现或执行。

## AD02只修复跨session键，内容新鲜度仍未闭环

[AndroidApkInspector.kt:57](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt#L57) stagedApkKey现在纳入sessionId、linuxPath、size、modified。两个session相同路径/大小/时间分别落不同digest目录，原跨会话复用案例已修。

[AndroidApkInspector.kt:237](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt#L237) 命中仍只看target是file且长度等于info size。同session源文件内容改变但size/mtime相同，会继续用旧target；digest是元数据摘要而非APK内容摘要。[AndroidApkInspector.kt:146](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt#L146) stageForInstaller也按package/version/source mtime命名并只检查长度。沿用AD02部分修复，不新增同类编号。

StagedApkKeyTest四例只调用key，验证不同session、相同输入、元数据变化与null namespace；没有读两份APK、安装或同元数据内容变化。不是实际部署一致性通过证据。

## AD03外挂Gradle子目录仍被过滤

[AndroidApkInspector.kt:205](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt#L205) 外挂walk后要求APK的直接父目录endsWith/build/outputs/apk，正常debug或flavor子目录仍不符合，可能返回no APK。Guest发现则找到apk输出根再向下walkGuestApks，宿主discover也递归输出根；不能因为其中两分支支持子目录便关闭外挂分支AD03。

显式artifact路径仍可检查，不宣称所有APK部署均失败。源APK由受控Guest/External文件入口暂存；不接受任意Android host绝对路径，也拒绝安装App覆盖自身。

## F22通用取消原缺口已修，淘汰并不取消worker

[AndroidLogManager.kt:151](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt#L151) watch注册JobRegistry.setCanceller；[JobRegistry.kt:127](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/JobRegistry.kt#L127) kill将RUNNING转KILLED并在monitor外调用cancel，worker收到CancellationException后结束。setCanceller还处理先kill后注册的窗口。原“只改状态、watch继续”的普通kill路径已修。

trimOldest仍可移除RUNNING及canceller但不调用取消；旧报告已有候选。watch最多300秒，不把registry淘汰说成无限运行。未制造超过100任务的实际序列，本轮不将其升为新故障。log游标是进程内全局Map，readSince校验session与boot；通用jobs按既有设计进程全局，不能仅因readJob无session校验就改写为guest工作区越界。

## AD04、AD05仍有源码原路径

[AndroidLogManager.kt:231](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt#L231) filterForPackage将任意FATAL EXCEPTION作为crashBlock开端，再保留相关stack形状，不要求目标PID；沿用AD04的此处归属缺口。诊断controller其它分支本轮未全文重读。

[AndroidAgentTools.kt:472](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidAgentTools.kt#L472) logsResult仍对缺success或mark/snapshot/watch/read/stop等动作设顶层成功。CURSOR_NOT_FOUND/SESSION_MISMATCH/BOOT_CHANGED及JOB_NOT_FOUND仍只返回status；AD05未修。

watch capture失败被折叠成raw后仍轮询、以轮询结束墙钟推进since、package/PID变化及cursor集合无生命周期清理均为候选，本轮未补强设备时间或实际失败证据，不强行编号。

## 工具职责与OpenMinis对照

APK发现、按mtime选择产物、日志轮询与摘要仍固化在App Kotlin。用户此前明确要求这类开发策略归沙箱与Agent，AD01职责差异仍存在；底层PackageManager/受控Android调用不能因此一并删除。本轮只排查，不做下沉重构。

上游固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1检查Git树，未找到同路径AndroidApkInspector/AndroidLogManager；无fetch。这不证明上游完全没有等价功能，只表示不可做同名逐行对照。沿用此前上游AgentJobRegistry非本地JobRegistry的边界，不直接搬换registry。

## 全文覆盖及保留

| 本轮新增全文 | 行数 |
|---|---:|
| [JobRegistry.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/JobRegistry.kt#L1) | 157 |
| [AndroidApkInspector.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidApkInspector.kt#L1) | 357 |
| [AndroidLogManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/tools/android/AndroidLogManager.kt#L1) | 283 |
| [StagedApkKeyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/tools/android/StagedApkKeyTest.kt#L1) | 32 |

四文件均首尾完整读取且无截断。AndroidAgentTools、JobTools仅交叉复核片段，不计新增全文。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-android-log-apk.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-android-log-apk.json)。累计全文 **1323 / 1655文件、221209行**；partial5、not_registered327、excluded124，tracked1779。全库通读未完成。

交付校验旧179份报告及全部旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1779快照Git blob/hash/bytes/lines及证据行号。仅新建本文与台账。
