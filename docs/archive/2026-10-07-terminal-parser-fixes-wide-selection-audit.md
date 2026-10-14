# 终端解析修复与双宽字符复制复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。完整静态阅读4文件、1034行。旧169份报告和全部台账保留，另建本文及台账。无代码修改、测试编写/执行、PTY输入、构建、模拟器/adb/Root/设备或云任务；下述输入序列仅作静态推导，没有运行。

## TSC01 / P2 — 双宽字符的占位格混入复制文本

[TerminalBuffer.kt:69](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/TerminalBuffer.kt#L69) writeChar为中文/emoji等width=2字符在下一格写char=' '、isWideTrailer=true。绘制端TerminalCanvasView第173行、TerminalNativeView第342行均跳过trailer，正常视觉显示不含这个占位空格。

但[TerminalEmulator.kt:172](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/TerminalEmulator.kt#L172) getSelectedText遍历选择区域，仅按char!=0追加code point，没有跳过isWideTrailer，之后只trim行尾空格。例如80列终端依次写“中文”：格0='中'、格1=trailer空格、格2='文'、格3=trailer空格；选择0—3格得到“中 文”，尾空格被trim但内部空格保留。

[TerminalNativeView.kt:282](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/canvas/TerminalNativeView.kt#L282) 当前原生终端复制路径实际调用getSelectedText，因此不是仅有未接线helper。复制中文路径、文件名或参数再粘贴时可变成不同字符串；emoji也可能受影响。影响为复制文本完整性，未证明任意命令执行或权限绕过。

建议方向：从已存在isWideTrailer标记跳过占位格，保留用户真的输入的普通空格，并明确部分选中双宽字符的行为。未来验收包含相邻中文、中文与ASCII、真实空格、跨行及emoji，从实际选择复制入口比较输入与剪贴板内容；本轮只记录，不修改或执行。

## TR03数值/循环与TR05无效UTF-8原反例已修

[AnsiParser.kt:177](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt#L177) CSI数字仅在currentParam低于65535时继续累加，乘十一次最多约655349，再停止，不会沿无限数字串继续Int溢出。它不是严格饱和到65535，不能将常量名当精确最大值；但数值增长已有固定界限。

TerminalBuffer第99/112行scrollUp/Down把次数限定滚动区高度，Emulator第280行向前tab次数限定cols。旧巨大合法正Int驱动几十亿循环的TR03原反例已修。insert/delete循环也限定行/列范围，不笼统说所有CSI都直接驱动无限循环。

[AnsiParser.kt:262](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt#L262) decodeUtf8检查overlong、surrogate与U+10FFFF上限，无效完整序列映射U+FFFD。原F4 90 80 80产生U+110000并使renderer构造String崩溃的TR05路径已阻断。合法é/€/emoji仍通过。残缺序列被后续非continuation字节打断时未补replacement属于显示语义候选，不与原非法码点崩溃混淆。

## TR04仍在：长度与数量预算未补齐

[AnsiParser.kt:244](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt#L244) oscPayload StringBuilder直到BEL/ESC才dispatch，不限长度，跨feed保留。CSI分号持续csiParams.add，没有参数个数上限。数值有界不等于列表有界。旧TR04这两条仍在。

TerminalScreen第136行把PTY outputBytes送emulator.feed，parser是emulator长期字段；限制每个输入chunk大小不能限制多个chunk累计的未终止状态。不是Root执行漏洞，影响终端处理资源和UI可用性，未运行内存压力。

OSC命令数字仍乘十可能溢出，但未知命令通常忽略，未证明新的权限影响，不新增编号。OSC payload按byte.toChar而不是UTF-8解码，title非ASCII候选；当前title的用户展示链未完整核实，不单独新增确定问题。

## 缓冲区其它边界与验证范围

primary scrollback最多2000，alternate不保存scrollback；resize只保留重叠网格、不reflow。width函数采用简化字符范围，没有combining/ZWJ组合逻辑，属于兼容能力边界，需具体验收定义，不把整个终端实现报成不可用。

private-mode处理仅按intermediate='?'就进入handleDecPrivateMode，以finalByte=='h'决定set；其它private final也可被当reset，未核实应用实际输出，不另新增触发报告。滚动区普通cursor移动夹到margin的语义需标准/实际程序对照，不在本轮强行列确定新问题。

## 现有健壮性测试的范围

[TerminalEmulatorRobustnessTest.kt:17](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/terminal/emulator/TerminalEmulatorRobustnessTest.kt#L17) 六例覆盖巨大scroll、巨大tab、溢出数字不变负、普通scroll仍有效、非法完整UTF-8变replacement及合法多字节保留。使用真实emulator/buffer/parser而非复制实现，这是有意义的源码验证设计。

时间断言仅针对这些短输入，未覆盖未结束OSC/CSI跨批次、双宽选择复制、残缺UTF-8、resize、scroll-region或原生绘制/剪贴板。全部仅阅读，没有执行；不能把存在耗时断言表述为本轮实测通过。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取现有AnsiParser第168—186行，无fetch。上游数字仍直接Int乘十，当前确有数值保护改善，不应为保持上游同形回退。未全文复核上游选择函数，不宣称TSC01在上游必然存在或已修。

## 全文覆盖与旧文件保留

| 新增全文 | 行数 |
|---|---:|
| [AnsiParser.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt#L1) | 283 |
| [TerminalBuffer.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/TerminalBuffer.kt#L1) | 252 |
| [TerminalEmulator.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/TerminalEmulator.kt#L1) | 428 |
| [TerminalEmulatorRobustnessTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/terminal/emulator/TerminalEmulatorRobustnessTest.kt#L1) | 71 |

Parser1—283、Buffer1—252全文，Emulator分1—215/216—428，测试1—71。TerminalScreen、renderer/类型只片段核对，不追加全文。

新台账：[2026-10-07-code-review-coverage-37158d18-terminal-emulator.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-terminal-emulator.json)。累计全文 **1285 / 1652文件、212550行**；partial4、not_registered363、excluded124，tracked1776。全库通读未完成。

交付核验旧169份报告和旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines及证据行号。只新建本文与本轮台账。
