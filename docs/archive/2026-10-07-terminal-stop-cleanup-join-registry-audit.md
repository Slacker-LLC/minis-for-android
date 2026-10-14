# 终端停止、清理等待与registry归属复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。完整静态阅读2文件、528行。旧170份报告及全部台账原样保留，另建本文及本轮台账。没有修改代码、编写/执行测试、PTY/JNI加载或输入、构建、模拟器/adb/Root/设备/云操作。时序反例来自源码，不是设备事故报告。

## TCL01 / P2 — 单终端停止后，全局join看不到仍在退出的旧run

[TerminalSession.kt:318](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt#L318) stop先stopActiveRun：清空activeRun、设置STOPPED、取消job；随后立即从liveSessions移除该TerminalSession。Job.cancel并不等待runTerminal finally执行，fd关闭、进程组清理和child reaping仍在旧job中完成。

[TerminalSession.kt:135](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt#L135) stopAllAndJoin只遍历liveSessions，再取activeJob()。单个stop已移除registry且清空activeRun，因此它无法取得该旧job，jobs列表可能为空，NonCancellable join立即返回。

明确静态顺序：PTY正在read或尚未进入finally；用户关闭终端调用stop并注销；runtime维护随后调用stopAllAndJoin，没有可join句柄；维护继续，旧job之后才完成DirectRootRunner.cleanupProcessGroup、backend.close和terminateAndWait。当前[UbuntuRuntime.kt:135](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuRuntime.kt#L135) 的stopOwnedProcessesLocked以及[UbuntuKernel.kt:259](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt#L259) rootfs readiness回收路径实际使用此等待，故不只是未接线helper。

影响：全局停止返回不再证明此前终端退出清理都已完成，维护可与旧清理重叠。native read有50ms轮询和最终清理，不等于stop是同步屏障；没有验证具体OEM上的mount失败或rootfs损坏，不能宣称必现破坏。

与TR09区分：这里不要求新run、不要求错误删除新registry，只需旧run在取消后未退出。建议保留退出中job的可等待归属直到清理完成，再让维护等待所有相关run；实现需复用现有生命周期，避免引入无调用方框架。未来验收单终端stop后阻塞旧read/cleanup，再调用stopAllAndJoin，验证全局调用直到清理完成才返回。只记录，不实现或执行。

## TR09的旧判定到删除窗口仍在

[TerminalSession.kt:260](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt#L260) finally在实例lock判断activeRun===run并清空，离开lock后按TerminalSession对象removeAll；不持有start的registryLock。新start能在旧判定成功后、删除前注册同一session，旧removeAll随后注销新run。stop也在stopActiveRun后另行removeAll，窗口同形。沿用TR09，不新增重复编号。

stopAll已在registryLock内取得与停止当前session，改善旧snapshot后停止替代run的另一窗口；不能由它持有锁推断finally/单stop也持同锁。start采用registry→instance顺序，旧finally只instance判断后非锁删除，这正是残留边界。

## TR01环境按键广播原入口已移除

当前TerminalSession没有广播自定义环境到活PTY的入口。每个run有输入Channel，sendRawBytes拷贝caller数组，只接显式用户输入/响应/resize；启动env含固定Android PATH、TERM/LANG/HOME和session ID，UbuntuRuntime.prepareLaunch构造direct argv。不能把启动环境当作敲到stdin。

单IO协程拥有fd、partial write pending/offset，写入最多64KiB并保序；read有限轮询，finally统一close/reap。BOOTING重复start由activeRun拒绝，prepare后和open后ensureActive阻止部分晚启动。诸保护真实存在，不描述为完全没有取消或资源归属。

Input Channel.UNLIMITED与output DROP_OLDEST有吞吐候选：长期输入大于消费可积累、输出慢消费者可丢chunk。未量化现役paste限制/输出速度，不新增实际丢字或OOM编号。scope SupervisorJob长期持有但所有run可取消，不仅凭未cancel父scope报泄漏。

## 现有测试覆盖真实生命周期但没有两个目标窗口

[TerminalSessionTest.kt:18](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/runtime/terminal/TerminalSessionTest.kt#L18) 七例用真实TerminalSession加FakePty，覆盖BOOTING重入/取消、EOF和重复stop一次close/reap、旧reader在新run已启动后退出不影响新run、无环境按键、partial writes/数组所有权、global stop、joined global stop等待仍在registry中的run。

旧reader测试第55行是新run先active、旧finally后判断；未覆盖旧finally先决定删除而新run中途注册的TR09。最后joined global stop测试第137行直接对仍登记的running session调用stopAllAndJoin，未先调用session.stop，因此不能证明TCL01已防护。

FakePty没有native JNI、真实su/chroot、进程组或mount，pidFile默认null。测试可以验证Kotlin生命周期部分，不可作为Release/JNI/真实Root设备清理通过证据。本轮只读测试，未运行。

## 固定OpenMinis对照

上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1读取现有TerminalSession第125—170行，无fetch。该快照仍PRootKernel启动及PROOT环境，当前Direct Ubuntu+App-owned生命周期结构明显不同。不能恢复上游PRoot来处理registry/join问题，也不能由上游方法名字判断当前清理正确。

## 全文覆盖及旧文件保留

| 新增全文 | 行数 |
|---|---:|
| [TerminalSession.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt#L1) | 336 |
| [TerminalSessionTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/runtime/terminal/TerminalSessionTest.kt#L1) | 192 |

TerminalSession1—336与测试1—192完整读取。PtyBackend已在此前全文覆盖，只重读不计；其它runtime/launcher片段不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-terminal-session.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-terminal-session.json)。累计全文 **1287 / 1652文件、213078行**；partial4、not_registered361、excluded124，tracked1776。全库通读未完成。

交付核验旧170份报告与旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines和证据行号。只新建本文及台账。
