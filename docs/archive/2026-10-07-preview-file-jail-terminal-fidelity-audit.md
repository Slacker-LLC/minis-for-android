# 预览文件校验与终端输出保真静态审计

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文6文件、843行，新增TSF01一项并复核旧问题。仅静态阅读源码及已有测试，没有改代码、编写/执行测试、加载HTML、访问敏感文件、执行shell输入、构建、模拟器、adb、Root、设备或云任务。此前147份报告及全部旧台账保持不动。

## TSF01 · P2 · 两个ST终止OSC之间的普通文字被删除

[TerminalSanitizer.kt:13](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/terminal/TerminalSanitizer.kt#L13) ANSI_REGEX的OSC分支使用\][^\x07]*(?:\x07|\x1B\\)。中间的[^BEL]*贪婪匹配，允许ESC及反斜杠，因此同一行出现多个ST（ESC加反斜杠）终止的OSC时，会从第一个OSC吞到最后一个可作为结束符的ST。

用ESC表示单个U+001B，ST表示ESC加反斜杠。静态输入为“OSC(a) + KEEP + OSC(b) + END”，其中OSC(a)=ESC ] 0;a ST，OSC(b)=ESC ] 0;b ST。两个控制序列都完整且合法；应移除控制部分保留KEEPEND。当前匹配从OSC(a)的ESC开始，贪婪部分跨过第一个ST、KEEP和第二个OSC正文，最后用第二个ST结束。foldLine第93行调用这个Regex，第104行跨过整个匹配，结果只剩END。第二轮replace使用同一Regex，不能恢复已丢的KEEP。

[ExecutionCoordinator.kt:122](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt#L122) 真实executeCommand返回后调用shellOutputForModel，第272行先sanitize再redact/truncate，因此这不是无调用展示helper；模型得到成功命令却少了一段普通结果。只确认该输入形状的静态删除，不声称所有ANSI、BEL终止的普通OSC或每次命令都丢内容。

与RU04不同：旧RU04是当前位置不认识的ESC，find跳到后一个匹配；本项从正确当前位置开始匹配两个完整OSC，起点检查修复无法防止贪婪跨终止符。已有TerminalSanitizerTest第58—61行只覆盖单个ST序列，FidelityTest的新组合是未知ESC/颜色等，没有两个ST OSC夹文字的断言。本轮没有运行模拟输入或编写测试。

方向记录：OSC解析应在第一个合法终止符停止，并核对单个/多个BEL或ST、混合结束符与普通文字保真；未实施。固定OpenMinis `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的sandbox/TerminalSanitizer第13行使用相同贪婪Regex，本轮读取已有Git对象并确认表达式相同；未fetch。属于共同算法遗漏，不据旧sandbox路径认定PRoot恢复。

## 终端旧问题已修的范围

原编号来自[Direct runtime报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-direct-runtime-file-audit.md)。

| 原编号 | 当前静态状态 |
|---|---|
| RU02 删除普通null/nullnull | 不再删普通文本，真实NUL仍由控制字符过滤去掉 |
| RU03 ANSI/CR行4096列截断 | cols改为line.length，原5000列/边界案例不再被固定4096截断 |
| RU04 未知ESC吞后续普通文字 | find结果必须range.first==i，否则只略过未知ESC |

FidelityTest直接调用生产sanitizer，覆盖上述输入与CR覆盖/颜色；未执行。对长行的动态buffer不能等同于输入阶段全量工作有界，现役Coordinator此前的输出收集上限仍需按具体收集器核对，不能简单以“没有4096上限”宣称整体内存问题都已解决。

## 预览旧问题的当前范围

原编号来自[HTML预览报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-html-preview-webapp-audit.md)。

| 原编号 | 固定源码状态 |
|---|---|
| HP01 file脚本可读App私有目录 | 新增file请求拦截与canonical allowlist；不再是完全无目录校验，完整隔离未证实 |
| HP02 Application context导致JS对话框直接取消 | 新增activityFor从rootView context解包，修正原view.context直接判断 |
| HP03 自动外部导航标为USER_INITIATED | 仍无hasGesture/主框架判断，旧路径残留 |
| HP04 renderer退出未恢复 | 当前client仍无onRenderProcessGone/isDead恢复链 |
| HP05 返回移动模式与初始viewport不同 | 初始化两个viewport设置true，返回移动模式false，仍在 |

[WebViewHolder.kt:120](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L120) shouldInterceptRequest仅检查file scheme，调用PreviewFileJail.allows；拒绝返回403。[PreviewFileJail.kt:31](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/preview/PreviewFileJail.kt#L31) canonical target/base加路径分隔符比较，能拒绝普通../、同前缀兄弟与既有逃逸symlink，保留同目录相对资源。

但roots包含整个hostSessions及全局workspace/shared/home/memory/skills，另无条件加initialUrl父目录；它不是仅当前session的边界。canonical检查与WebView稍后的打开也是两个步骤，没有目录句柄持有；不能凭helper成功就声称变动文件系统、所有scheme及全会话隔离均已证明。本轮没有构造竞态，也没有取真实私有数据，未另计路径越界新项。

[WebViewHolder.kt:52](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L52) activityFor优先rootView.context并解包ContextWrapper，正常挂载窗口时有获得宿主的源码路径，原Application context必然cancel的判断已改。三种JS对话框仍show未捕获窗口销毁错误，作为需闭合生命周期的候选，不凭能发生异常就计新缺陷。

startIfNeeded等待attach/正尺寸；已attached但尺寸仍0时进入attach listener是否还有下一次attach事件，需结合AndroidView真实安装时序，暂不计白屏。stop/destroy和延迟resize callback也需要设备生命周期证据，本轮不启动预览。

## 已有测试与真实调用

PreviewFileJailTest直接测试生产helper，覆盖目录内、App-private目录外、../、相同前缀兄弟、symlink（不可用时assume跳过）及opened parent。测试roots时没有预览当前session隔离断言。

InstrumentedTest创建真实WebView，用无敏感canary读取同目录与filesDir目录外，检查title不含private；它直接loadUrl绕过startIfNeeded的attach等待，也没有覆盖不同session、文件系统变化、content scheme、外部导航和renderer恢复。本轮只是阅读测试，没有运行它或创建canary，不能把类名当设备验收结果。

[ChatScreen.kt:2219](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt#L2219) 当前HTML动作仍用App context构建WebViewHolder并在sheet/fullscreen复用；同URL重用由currentUrl比较决定。旧关闭WebApp路径本轮未重读，不因这一预览修复宣布其中Cookie/授权/数据库等风险已处理。

## 全文覆盖与交付

| 新增全文文件 | 行数 |
|---|---:|
| [PreviewFileJailInstrumentedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/ui/preview/PreviewFileJailInstrumentedTest.kt#L1) | 85 |
| [TerminalSanitizer.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/terminal/TerminalSanitizer.kt#L1) | 120 |
| [PreviewFileJail.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/preview/PreviewFileJail.kt#L1) | 39 |
| [WebViewHolder.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/preview/WebViewHolder.kt#L1) | 464 |
| [TerminalSanitizerFidelityTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/runtime/terminal/TerminalSanitizerFidelityTest.kt#L1) | 57 |
| [PreviewFileJailTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/preview/PreviewFileJailTest.kt#L1) | 78 |

Holder按1—235、236—464完整读取，其余五文件完整输出，无截断。旧报告/终端引用混合输出部分截断，旧RU02—04证据、真实Coordinator122/272与单OSC测试随后单独复核；片段不新增全文。

新累计台账：[2026-10-07-code-review-coverage-37158d18-preview-terminal.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-preview-terminal.json)。全文 **1192 / 1652文件、192202行**；partial4、not_registered456、excluded124，tracked1776。全库阅读未完成。

交付检查：旧147份报告及所有旧台账hash，固定本地/远端/上游HEAD、tracked/staged空diff，远端1776个快照Git blob/hash/bytes/lines及报告证据行号。仅新建两文件。
