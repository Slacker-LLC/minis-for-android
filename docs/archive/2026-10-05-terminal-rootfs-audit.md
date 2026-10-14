# Minis for Android — 终端与 rootfs 全文排查

日期：2026-10-05。当前源码提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

## 范围与结论

本轮继续全库只读排查，补齐终端输入、PTY、ANSI parser、渲染、rootfs 健康检查及设置页面的调用链。另建本报告，不修改既有报告。共记录 9 项问题；它们是本轮新定位的条目，不意味着全部由本仓库最近新增。

全文阅读覆盖本次补齐并登记 **27 个文件、5791 行**，包含此前续读但尚未登记的文件。累计 **139／1546 个纳入范围的文件、27521 行**；4 个仍只有片段记录，1403 个尚未登记全文。124 个一般文档、许可证、二进制或批量数据另列。全库阅读仍未完成，详情见 [覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

所有判断均来自当前源码和静态调用链。未改产品代码、测试或合同，未运行构建、产品测试、模拟器、设备命令、Root 操作或云任务。下文验收仅为建议。

| 编号 | 优先级 | 问题 |
|---|---|---|
| TR01 | P1 | 环境变量广播被当作按键送给 PTY 前台程序，代理认证值也可能被带出 |
| TR02 | P2 | IME 组合文字和最终提交都追加到终端，造成重复输入 |
| TR03 | P1 | ANSI 滚动及制表参数直接控制主线程循环次数 |
| TR04 | P2 | 未结束的 OSC／CSI 序列可以跨批次无限积累 |
| TR05 | P1 | 无效 UTF-8 码点进入渲染器后触发未捕获异常 |
| TR06 | P2 | 原生终端丢掉每次拖动的小数行，慢速拖动不能累计滚动 |
| TR07 | P2 | rootfs 必需路径类型错误仍可能通过健康检查 |
| TR08 | P2 | tar 测试验证复制的旧实现，没有覆盖现役解包链路 |
| TR09 | P2 | 清理判定与 registry 删除之间存在窗口，可注销新启动的终端 |

## TR01 — 配置更新实际是在给前台程序输入文字

[TerminalSession.kt:362](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt:362) 的 `applyTimezone` 将 `export TZ='…'` 和回车送入 `sendRawBytes`；[applyEnvMap:368](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt:368) 同样把 unset／export 当作 PTY 输入。判断只有 `isRunning`，它表示 PTY 已启动，不能证明前台是正在等待新命令的本地 bash。

生产入口已确认：[MinisApp.kt:1090](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1090) 响应时区广播，1097 行响应代理广播；[ExecutionCoordinator.kt:280](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt:280) 合并用户代理和 Root helper 代理后调用终端广播。helper 活着并 READY 时，[RootNetworkProxy.kt:181](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootNetworkProxy.kt:181) 返回含认证值的 URI；[buildProxyUri:198](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootNetworkProxy.kt:198) 明确把进程级 token 放在 URI userinfo。

具体后果取决于广播时的前台状态：编辑器可能把 export 写入当前文档；读取 stdin 的程序会得到这些文字；SSH 会把它们送到远端终端，本地 helper 的认证值可能因此进入远端输入流；本地 bash 若已有未提交的半行，也会拼接这些文字并收到回车。正确的 shell 引号转义不能解决输入被交给哪个程序的问题。

风险边界：只有对应广播发生、终端处于该前台状态时触发；认证值是否出现还取决于 helper 是否 READY。不宣称 token 单独足以从远端访问只监听 loopback 的 helper，也没有证明历史文件已泄漏。

现有 [TerminalSessionTest.kt:86](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/terminal/TerminalSessionTest.kt:86) 只验证 fake backend 收到 export／unset 字节，没有真实前台程序语义。

建议方向：环境更新不能通过模拟用户输入隐式执行；按生命周期为后续 shell 应用配置，或采用明确、不会写入任意前台 stdin 的处理。后续验收应分别处于空提示符、半行命令、编辑器、stdin reader 和受控 SSH 时触发更新，保存接收端字节证据。未执行。

## TR02 — 输入法替换语义被实现成追加

[TerminalInputView.kt:129](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/canvas/TerminalInputView.kt:129) 的 `commitText` 与 135 行的 `setComposingText` 都把整段文字立即编码后发送，既不维护组合区，也不替换前一次组合内容；`finishComposingText` 只返回 true。

例如输入法调用 `setComposingText("n") → setComposingText("ni") → commitText("你")`，当前输入流是 `nni你`。这属于有效的 IME 调用序列：[Android InputConnection 文档](https://developer.android.com/reference/android/view/inputmethod/InputConnection#setComposingText(java.lang.CharSequence,%20int)) 规定新组合文字替换旧组合文字，commitText 则用最终内容替换组合区。

当前设置了 no-suggestions／visible-password，但这些 flags 不构成“永远不会发生 composing”的保证。因此这是使用组合回调的输入法会触发的问题，不能写成所有键盘必然重复。

建议方向：区分尚未提交的组合区和已发送的终端内容，正确处理替换、空组合和取消。后续验收覆盖中文／日文组合、选词、改词和取消，核对 PTY 最终收到的字节。现有单键编码测试不能证明该链路正确，未执行输入法验证。

## TR03 — 少量 ANSI 输出可以占用主线程大量循环

[TerminalEmulator.kt:244](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/TerminalEmulator.kt:244) 把 S／T 参数直接传入滚动；[TerminalBuffer.kt:98](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/TerminalBuffer.kt:98) 使用 `repeat(n)` 逐行搬移、分配空行，未按滚动区高度限制工作量。269 行的向前制表 I 同样直接 `repeat` 参数次数。

参数只做最小值处理，没有最大预算。一个很短的、大正整数参数就能驱动远超屏幕尺寸的循环。scrollback 保留 2000 行只限制最终保留量，不能限制搬移和分配次数。

更上游的 [AnsiParser.kt:176](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt:176) 用 Int 乘十累加，没有溢出检查；光标移动也存在先进行 Int 加法再取边界的路径。但本条不依赖溢出：合法正 Int 已足以造成过量计算。

[TerminalScreen.kt:134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/TerminalScreen.kt:134) 的 Compose `LaunchedEffect` 收集 PTY 输出并同步 `emulator.feed`，未把解析移到后台或限制单次处理。循环期间无法正常处理 UI 事件，有主线程卡死／ANR 风险；本轮未测实际 ANR 时间。

建议方向：按终端尺寸处理参数、限制序列数值并检查溢出，保证单次输入的计算有界。后续验收保存参数边界前后的 UI 响应和终端状态，使用受控输出，不以长期阻塞作为测试方法。未执行。

## TR04 — 状态机没有序列级容量上限

[AnsiParser.kt:245](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt:245) 收到 OSC 字符串后，持续向 `StringBuilder` 追加，直到 BEL／ESC；没有长度限制、超限恢复或丢弃状态。[CSI 参数:179](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt:179) 每个分号都可增加一项，也没有参数个数限制。

parser 是 emulator 的长寿命成员，多次 feed 共用状态。因此把输出切成 4096 字节、SharedFlow 限制 64 个缓冲项，仍不能限制一个未结束序列跨批次积累的总量。一个缺少终止符的输出源可以持续增加 App 堆占用，并使后续内容留在序列解析状态。

建议方向：对 OSC 字节数、CSI 参数数及数值分别设置预算，超限后进入可恢复的有界状态；确保随后普通文本能恢复显示。后续验收覆盖跨多批次、无终止符和超限后恢复。未执行。

## TR05 — parser 接收的码点超出渲染器可接受范围

[AnsiParser.kt:102](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/AnsiParser.kt:102) 接受 F0–F7 的四字节起始，262 行的解码只拼接位段；完成后只判断 `cp >= 0`，不验证 Unicode 上限、过长编码或 surrogate 范围。

例如无效序列的起始及后续字节组合能得到 U+110000。该值沿 [TerminalEmulator.kt:187](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/emulator/TerminalEmulator.kt:187) 写入 cell，然后进入现役 [TerminalNativeView.kt:478](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/canvas/TerminalNativeView.kt:478) 的 `String(intArrayOf(ch), 0, 1)`；这里没有异常恢复。[String 构造器契约](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html#%3Cinit%3E(int%5B%5D,int,int)) 对无效码点抛出 IllegalArgumentException。

因此显示未经验证的终端字节可能触发 UI 渲染异常，不能只当作乱码。旧 Compose renderer 也使用同类构造。输入可以来自普通 guest 程序输出，本条不需要假设拥有 Root 权限或攻破 chroot。本轮未运行崩溃复现。

建议方向：在解码边界拒绝／替换无效 Unicode 序列，渲染边界也保持合法码点不变量。后续验收包含有效四字节字符、非法起始、超过上限、surrogate 和截断后继续输入。未执行。

## TR06 — 慢速拖动的位移每次都被舍弃

现役 [TerminalNativeView.kt:212](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/canvas/TerminalNativeView.kt:212) 把每次 GestureDetector 回调的 `distanceY / cellHeight` 立即转 Int。[Android onScroll 文档](https://developer.android.com/reference/android/view/GestureDetector.OnGestureListener#onScroll(android.view.MotionEvent,%20android.view.MotionEvent,%20float,%20float)) 明确 distanceY 是自上次回调以来的位移。若每次移动都不足一行，每次结果都为 0，没有累计余量；即使整个拖动距离已经跨越多行，仍可能不滚动。

此前的 [TerminalCanvasView.kt:98](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/canvas/TerminalCanvasView.kt:98) 有累计像素余量逻辑，但 [TerminalScreen.kt:257](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/terminal/TerminalScreen.kt:257) 已使用 NativeView。这不是要求恢复旧 renderer，而是当前路径丢掉了有效位移。

建议方向：在现役 renderer 中累计不足一行的位移并正确处理边缘。后续验收用连续小位移慢速拖动和快速拖动比较实际滚动行数。未执行。

## TR07 — 损坏的节点类型没有进入健康失败

[RootfsManager.kt:159](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:159) 将 workspace、memory、skills、shared、proc 等放在必需布局中，但 [noSymlinkLayoutChecks:311](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:311) 对各节点只检查 `-e` 和 `! -L`，没有按目录／普通文件分别检查类型。

保持其它布局及合法 metadata 不变，仅让 rootfs 的 `workspace` 变为普通文件，这条布局检查仍通过，并可返回 HEALTHY。随后 [UbuntuKernel.kt:244](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:244) 因健康而跳过修复；实际 [bind setup:596](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:596) 要求目录，才会失败。设置页同时可显示已安装。

这不是证明 guest 可改 Root-owned 树，也不是宣称所有损坏都会漏检；它是特定损坏状态的错误健康判定。后续 mount 的目录校验仍然能拒绝，不应把它误写为成功越界挂载。

现有 [RootfsManagerRecoveryTest.kt:74](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/recovery/RootfsManagerRecoveryTest.kt:74) 检查生成命令包含路径及若干 guard，没有构造类型错误的真实布局。

建议方向：声明必需节点类型并在 probe／repair 中一致检查。后续验收覆盖目录变文件、文件变目录以及恢复后的健康与启动一致性。未执行。

## TR08 — 退役 tar 算法还在自测

[TarExtractionTest.kt:12](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/sandbox/TarExtractionTest.kt:12) 宣称测试 RootfsManager 的 POSIX tar 解包。实际测试调用的是同文件中的 `extractTarHelper`，[helper:325](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/sandbox/TarExtractionTest.kt:325) 明确称其为生产算法的重实现；声明的 `manager: Any` 没有被初始化或调用。

现役 RootfsManager 已没有 `extractTar`／`readFully`／`extractString` 方法，实际 [buildRepairCommand:279](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:279) 经 Root runner 调用 tar 解包并换目录。因此该组成功、链接及块对齐断言都不能覆盖当前生产解包行为。

本条记录具体的失效测试对象，不把 test helper 的路径行为当作生产漏洞。项目另有 payload 校验及现役 recovery 测试，不宣称当前安装完全没有检查；但不能拿这份复制算法的结果证明现役链路正确。

建议方向：后续维护按现役链路安排可复核的端到端验收，清楚标识或移除失效测试，不再靠复制算法自证。当前没有改测试，也未运行它。

## TR09 — 新一代终端可能被旧清理注销

[TerminalSession.kt:288](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt:288) 在实例 lock 中判断旧 run 是否仍为 active，清空 activeRun 并得到 `removeLiveSession=true`。随后离开 lock，300 行按 TerminalSession 对象删除全部 registry 项；此处没有持有 start 使用的 registryLock。

可发生的交错：旧 run 判定自己仍 active、清空并释放实例 lock；另一线程 [start:214](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/TerminalSession.kt:214) 启动新 run 并重新登记同一 TerminalSession；旧 finally 随后按对象删除，移除的是新 run 的登记。新 run 仍可运行，却不再收到广播，也不会出现在 stopAll／stopAllAndJoin 的遍历中。`stop()` 的 stopActiveRun 与 registry 删除之间也有类似窗口。

现有 [TerminalSessionTest.kt:55](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/runtime/terminal/TerminalSessionTest.kt:55) 覆盖的是“新 run 已先 active，旧 finally 再判定”的顺序，此时 identity 判断能保护；没有覆盖“旧判定完成后，新 run 登记，再执行旧删除”的窗口。

建议方向：注册、判定及删除应在同一并发边界保持一致，或删除与 run 身份绑定的登记。后续验收需要明确控制上述交错并核对新终端仍被 stopAll 找到。未运行并发复现，不宣称真机维护事故已发生。

## openminis 对照、既有保护与全文清单

上游只读快照：`/tmp/openminis-review-20261004`，提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。通过 `git show HEAD:<path>` 对比当前文件及对应片段：

- emulator 的 AnsiParser、TerminalBuffer 和 TarExtractionTest 与固定上游全文相同。TR03–05 的相关 parser／buffer 缺口及复制测试来自继承实现；本地把 tar 生产路径换为 Root 命令后，TR08 的验证对象更已脱节。
- InputView、NativeView、TerminalEmulator 虽有本地差异，但 composing 直送、每次拖动取整、I 参数 repeat 等对应片段在固定上游也存在。TR02、TR06 不归因于本地最近新增。
- 上游 TerminalSession 同样把时区及代理 export 当 PTY 输入；TR01 的输入通道问题已有，本地 authenticated proxy URI 使其还有认证值处理风险。不能把整个问题都说成本地新引入。
- 本地 RootfsManager 与 TerminalSession 生命周期已显著改造；TR07、TR09 针对当前 Direct Ubuntu 检查与登记实现，不据此倒推上游 PRoot 行为。

当前 PTY JNI 有数组范围检查、非阻塞 I/O 与 poll；TerminalSession 把 fd／child 清理集中在 run 的 finally；维护入口也有 stopAllAndJoin。这些保护存在，不能因新发现而声称“终端完全没有清理”。本轮阅读 JNI 和相应测试源码，没有运行 native host 测试，也没有验证 Release／OEM 行为。

登记全文文件包括：TerminalSession、RootfsManager、CMakeLists、pty_bridge、TerminalSessionTest、test_pty_bridge.py、TarExtractionTest；TerminalScreen、TerminalKeys 及其测试、TerminalFonts、两份不同用途的 AnsiParser、TerminalTypes、TerminalBuffer、TerminalEmulator；TerminalInputView、TerminalNativeView、TerminalCanvasView、TerminalPaletteTest、MinisOpenUrlBroker、MinisUrlMarker；RootfsManagementViewModel、RootfsManagementScreen 及 ViewModel 测试、RootfsManagerRecoveryTest、RootfsSizeProbeTest。各文件的完整区间、行数和 SHA-256 均写入覆盖台账，不把本轮调用方片段计成全文。
