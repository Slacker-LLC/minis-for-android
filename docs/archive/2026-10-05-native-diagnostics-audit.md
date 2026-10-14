# 原生分词、崩溃恢复与诊断日志静态排查

日期：2026-10-05。当前项目 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本轮新增独立报告；不修改已经交付的报告、产品源码或合同。只读取源码、已有测试和文档，并核对固定 openminis 快照。未构建、运行测试、启动模拟器、执行 adb／Root 命令或使用云端设备资源。

## 范围与结论

新增 **12 项**问题／风险：1 项 P1、10 项 P2、1 项 P3。P1 表示满足特定输入状态时可终止整个 App；P2 表示恢复判断、诊断可靠性或资源边界存在实质缺口；P3 表示展示内容错误。

这不是全仓审计结论。累计登记全文阅读 **165／1,546 个代码、配置、源资源及 fixture 文件，34,178 行**；本轮增加 19 个文件、4,855 行。仍有大量文件待读。

Android 开发工具应由沙箱中的 Agent 管理这一架构问题已记录于 [上一轮工具归属报告:1](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-agent-managed-tools-review.md:1)，本轮不重复计数。这里的分词是 App 语音纠错功能的内部实现，不能仅凭它运行在 App 内就归入同一类工具归属问题。

## ND01 · P1 · 词典损坏会触发 abort，分词降级无法接住

**触发：** 已记录当前词典版本，但 App 私有缓存中某份词典变成空文件、格式错误文件，或者同名目录。[shared/JiebaEngine.kt:95](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/shared/JiebaEngine.kt:95) 只检查存在性和版本，不检查普通文件、长度或内容，随后进入 native 初始化。

[jieba_jni.cpp:99](/home/jiale/projects/minis-for-android/src/android/app/src/main/cpp/jieba_jni.cpp:99) 用 C++ try/catch 包住构造器；但 [cppjieba/include/cppjieba/DictTrie.hpp:234](/home/jiale/projects/minis-for-android/src/android/app/src/main/cpp/cppjieba/include/cppjieba/DictTrie.hpp:234) 与 [cppjieba/include/cppjieba/HMMModel.hpp:37](/home/jiale/projects/minis-for-android/src/android/app/src/main/cpp/cppjieba/include/cppjieba/HMMModel.hpp:37) 使用 XCHECK 验证输入。[cppjieba/include/cppjieba/Utils.hpp:32](/home/jiale/projects/minis-for-android/src/android/app/src/main/cpp/cppjieba/include/cppjieba/Utils.hpp:32) 把失败转成 FATAL 日志，析构器在第 108 行直接调用 abort()。当前 CMake 未设置绕过该 FATAL 的 LOGGING_LEVEL。它不是返回 false 或抛出可捕获异常，C++ catch 和 [shared/JiebaEngine.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/shared/JiebaEngine.kt:44) 的 Kotlin catch 均不能恢复。

[shared/TextSegmenter.kt:33](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/shared/TextSegmenter.kt:33) 的 ICU 降级依赖 Jieba 返回空结果；进程终止时该分支无法执行。[speech/correction/VoiceCorrection.kt:55](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrection.kt:55) 把这个分词器接入录入纠错和词汇学习，后台协程并不能隔离原生进程级终止。

**影响和边界：** 满足上述缓存损坏条件时，中文分词可能使 App 崩溃，并在未修复缓存的后续调用中重现。缓存位于 App 私有目录，本轮没有证明远程调用方可以破坏它，也没有证明正常安装必然产生损坏。

**处理方向：** 初始化前验证并恢复缓存；底层可预期的输入失败必须能返回到既有降级路径。已有分词测试覆盖正常文本和语言判断，本轮阅读的两份测试没有损坏词典场景；未执行破坏缓存试验。

## ND02 · P2 · 崩溃报告把工作线程名称写成进程主线程名称

[crash_handler.cpp:177](/home/jiale/projects/minis-for-android/src/android/app/src/main/cpp/crash_handler.cpp:177) 固定读取 /proc/self/comm；第 189 行取得的 TID 却属于当前崩溃线程。第 206／223 行把二者写进同一条记录。

当工作线程崩溃时，报告中的 TID 与 Thread 名称指向不同线程，容易把 RenderThread、JIT 或其他工作线程故障归因到主线程。[Linux man-pages 的 comm 文档](https://man7.org/linux/man-pages/man5/proc_pid_comm.5.html)明确给出了读取线程自身名称的 /proc/pid/task/tid/comm 路径。

**上游对比：** 固定 openminis 已在同文件第 387—399 行按 TID 构造路径，并在降级读取主线程名称时添加标记。当前项目没有保留这段修正。这是源码差异，不能据此推断是哪一次改动或哪位作者造成的。

**处理方向：** 名称与报告 TID 使用同一线程入口；降级取进程名称时显式说明来源。本轮未在设备上主动制造崩溃。

## ND03 · P2 · 不安全的信号处理工作仍发生在交还系统 handler 之前

[crash_handler.cpp:153](/home/jiale/projects/minis-for-android/src/android/app/src/main/cpp/crash_handler.cpp:153) 声称 snprintf 有 POSIX 异步信号安全保证；实际路径先调用 localtime_r、snprintf 构造文件名，再写摘要，并在第 257 行同步展开栈，直到第 262 行才交还原有 handler。

[Linux man-pages 的 signal-safety 文档](https://man7.org/linux/man-pages/man7/signal-safety.7.html)给出的保证列表不包含 snprintf 或 localtime_r；实现可以额外提供保证，但不能直接以 POSIX 为依据。当前代码自身也承认 _Unwind_Backtrace 没有该保证。

**风险：** 如果展开栈等操作卡住，先写了 App 摘要也不能保证 debuggerd 获得控制权。g_in_handler 只能处理再次进入 handler 的情况，不能解除一次调用内部的等待。因而“摘要已写入”不能推出“平台 tombstone 一定生成”。

**边界：** 本轮确认的是安全保证和控制流缺口，没有确认某款设备上的具体死锁，也不声称每次 native crash 都会丢失 tombstone。上游同样先展开栈再交还系统，属于双方共享风险。处理时应让可选诊断步骤无法阻断平台崩溃处理，并核实目标 Android 实现的保证。

## ND04 · P2 · 卡顿检测回退到可跳变的墙上时钟，误报会影响恢复

[diagnostics/HangDetector.kt:116](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/HangDetector.kt:116) 初始化心跳；第 196 行更新心跳；第 222 行计算间隔。三处都使用 System.currentTimeMillis()，但调度心跳的 Handler 基于 uptime 时钟。[Android SystemClock 文档](https://developer.android.com/reference/android/os/SystemClock)说明墙上时钟可以前后跳变，间隔计时应使用其他时钟。

**触发：** 在心跳写入后、watchdog 取样前，系统时间向前校正超过阈值；或者进程休眠／冻结后 watchdog 先于心跳恢复。代码会把日历时间或整体暂停间隔计入 since。第 244—251 行没有确认主线程确实阻塞，就增加 hang 计数。

第 318—327 行把计数持久化，并在两次时开启纯文本渲染降级；[diagnostics/HangDetector.kt:172](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/HangDetector.kt:172) 三次即请求启动回首页。一次错误的 stall 记录还可能被 LaunchCycleBeacon 当成前一周期异常的证据。

**上游对比：** openminis 使用 SystemClock.uptimeMillis()，还过滤过大的暂停间隔和主线程停在 MessageQueue.nativePollOnce 的情况；当前项目均未保留。处理方向是统一间隔时钟，并区分主线程阻塞与进程整体暂停。未执行调时、休眠或设备复现。

## ND05 · P2 · 日志格式器跨线程共用，异常保护又晚于格式化

[logging/AppLogger.kt:28](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:28) 全局复用两个 SimpleDateFormat。[logging/AppLogger.kt:303](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:303) 在取得锁之前格式化日期和时间，且这两次调用位于 enabled 检查和 try/catch 之前。logcat 后台线程也在第 188 行使用同一 dateFormat；stdout 捕获持有对象锁不能保护这些未持锁的调用。

[Android SimpleDateFormat 文档](https://developer.android.com/reference/java/text/SimpleDateFormat)要求并发访问时额外同步。当前路径可能产生错误时间／日期，或让格式化异常从日志函数传播给业务调用方；即使文件日志关闭，log() 仍执行格式化。

**上游对比：** 上游也共享格式器，不能宣称它已经解决并发问题；但它先判断是否写文件，并把格式化放进异常保护范围。当前项目失去了这部分保护。处理方向是保证所有格式化入口的并发安全，同时让辅助日志故障无法中断调用方。

## ND06 · P2 · stdout/stderr 捕获的未换行缓存没有上限，flush 不落盘

[logging/AppLogger.kt:209](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:209) 创建可增长 ByteArrayOutputStream；第 217／232 行一直追加，只有遇到换行才 emitLine。第 235 行 flush 只刷新原始 delegate，没有处理捕获缓存；该类没有 close 覆写，stopCapture 也没有补齐剩余行。

**触发与影响：** 开启日志后，库持续 print 而不输出换行，或用回车更新进度，捕获器会持续占用堆内存，极端情况下导致 OOM；较短的最后一段输出也不会经该捕获器落盘。原始 stdout 仍收到字节，logcat 旁路能否补回取决于原始输出流与采集时机，不能视为该捕获器已正确处理部分行。

这是 App 全局输出捕获，不是只影响一个 Agent 工具。上游保留相同缓存与 flush 实现，也有此缺口。处理方向是有界分段捕获，并明确部分行在 flush／停止时的处理；不能关闭原始全局输出流。

## ND07 · P2 · 日志读取限制在整文件分配后才生效

[logging/AppLogger.kt:397](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:397) 用 readText 读取整份日志。[debug/DebugRPCHandler.kt:775](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt:775) 先调用它，再用 offset／limit 截取；默认 512 KiB、最高 16 MiB 的返回限制没有限制读取内存。即使请求 limit=0，整文件仍先读进堆。

[ui/settings/LogManagementScreen.kt:545](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/LogManagementScreen.kt:545) 的分享降级同样先 readText，再 take(100_000)，而且从点击回调同步进入。很大的日志会先产生整文件内存和解码成本，之后的截取不能防止 OOM；分享降级还可能阻塞 UI。

**边界和对比：** 当前 RPC 对文件名做了路径拒绝，本项不把它描述成路径越界问题。正常日志详情页采用按行加载，不能说该页面仍全面 readText。上游两个对应入口也先读整文件。处理方向是在读取阶段应用边界，同时保留接口现有字符／字节语义，避免改变分页协议。

## ND08 · P2 · 历史静默回收被累计成“连续崩溃”

[diagnostics/LaunchCycleBeacon.kt:198](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/diagnostics/LaunchCycleBeacon.kt:198) 只分类最近一次周期；没有 crash/stall 文件时返回 silent_kill。可是第 145—149 行计算 restartCount 时只统计尾部所有 launch 减去 clean_exit，不检查这些历史周期的结局，也不在最近一次正常周期之后截断。

**具体序列：** 三个启动周期由系统静默回收，没有 clean_exit、也没有 crash/stall；第四次启动后发生一次真实崩溃。下一次启动分类为 crash_or_stall，restartCount 却为 4，第 90 行触发回首页，尽管没有发生四次连续崩溃。中间的一次干净退出也只是从历史总数中减一，不能重置此前累计。

[ui/navigation/AppNavigation.kt:390](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:390) 确实把该结果用于覆盖用户启动选项。object 变量在 silent_kill 时清零只影响那一次进程，不能消除日志尾部中保留的历史累计。上游同文件逐字一致，问题共享。处理方向是按连续周期的分类计算，并明确恢复后的重置条件。

## ND09 · P2 · 崩溃日志压缩直接阻塞主线程

[crash/CrashFrequencyDetector.kt:556](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/crash/CrashFrequencyDetector.kt:556) 的 View 点击回调同步执行 action，分享／保存分支直接调用 packageZip。第 628／781／820 行没有异步切换；[crash/CrashFrequencyDetector.kt:879](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/crash/CrashFrequencyDetector.kt:879) 对所有选中的文件完整读取并执行 ZIP 压缩。

64 KiB 缓冲区限制了单块复制内存，并没有把总读取量或压缩时间限制在 UI 可接受范围。用户选中很大的日常日志、或存储较慢时，分享／保存会冻结界面，持续足够久可导致 ANR。对刚经历频繁崩溃、正在导出诊断资料的用户，这是恢复流程中的额外故障点。

上游具有相同同步调用链；不能把邮件选项等界面差异当成本项根因。处理方向是将打包移出主线程，保留完成／失败状态和可取消性。本轮未打包真实日志，也没有测量设备 ANR 时间。

## ND10 · P3 · 分块 UTF-8 解码会破坏跨 8 KiB 边界的中文和 emoji

[ui/settings/LogManagementScreen.kt:346](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/LogManagementScreen.kt:346) 用 8 KiB 字节数组读取一行，但第 354／360 行分别把每块直接构造成 String，随后拼接。解码器没有跨块保留未完成字符。

例如一行从起始偏移开始有 8,191 个 ASCII 字节，随后是三字节的“中”：第一块只含该字符的首字节，第二块含剩余字节。两块各自解码都会丢失原字符，事后拼接 String 无法恢复。emoji 也可能跨界。

这是显示层损坏，原始日志字节不因此改变。上游相同算法同样存在。处理方向是持续使用同一解码状态，或按整行字节边界解码并对过长行设置资源边界。这里只给出静态输入推导，没有创建或运行测试。

## ND11 · P2 · 日常日志失去上游文件容量限制，保留天数无法约束增长

当前 AppLogger 的各写入入口直接追加并 flush；[logging/AppLogger.kt:329](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:329) 创建 daily 文件后没有大小检查或截断路径。第 432 行只按最后修改时间清理超过 15 天的文件，不能限制当天或仍不断修改的文件。

**上游对比：** openminis AppLogger 第 53 行设置 500 MiB 文件阈值，第 429—436 行统一追加并定期检查，第 447—469 行用流式临时文件保留最新一半。当前实现没有这些控制。上游默认 Info 级别与 stdout/stderr 去重也被移除：当前 [logging/LogcatTailer.kt:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/LogcatTailer.kt:50) 采集 *:V；[logging/AppLogger.kt:184](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:184) 没有过滤 System.out／System.err，从 stdout 捕获写过的内容可能再次经 logcat 写入，进一步放大增长。

**影响与边界：** 开启文件日志后，高频框架、库和 App 输出可以长期占用 App 私有存储；盘满会妨碍其他持久化和诊断导出。没有宣称所有设备一定重复记录每条输出，或本轮实测了磁盘耗尽。上游 500 MiB 阈值也不等于整个 logs 目录有总配额。

**处理方向：** 为统一写入路径建立明确容量和保留预算，恢复输出去重／级别选择时核对全部入口。本项记录容量控制缺失；与 ND06 的堆缓存、ND07 的读取分配是不同阶段的问题。

## ND12 · P2 · 取得日志 writer 后释放锁，轮换可使后续写入静默丢失

[logging/AppLogger.kt:329](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:329) 的 getWriter 有同步保护，但返回后 [logging/AppLogger.kt:320](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:320) 与 logcat 第 189 行均在该对象锁之外 println／flush。另一个线程可以在第 331 行轮换日期，或者 [logging/AppLogger.kt:415](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/logging/AppLogger.kt:415) 清理时关闭这个 writer。

**可行交错：** 线程 A 得到旧日期 writer 后暂停；线程 B 获得新日期 writer，并关闭旧 writer；A 恢复向已关闭对象写入。调用方没有检查写入结果。[Android PrintWriter 文档](https://developer.android.com/reference/java/io/PrintWriter)明确说明写入方法不抛 I/O 异常，需要 checkError 读取错误状态。现有 catch 不会捕获这类丢行。

这不代表日志从此永久停止，下一次 getWriter 可以重开；问题是本次记录可能静默丢失。清理日志时主动删除历史数据也不是本项所指故障，日期轮换交错不涉及用户主动删除。

**上游对比：** 上游第 429 行的 appendLine 同步覆盖取 writer、写入和检查整个过程；当前项目拆回裸 writer 使用，未保留这段并发边界。处理方向是将 writer 的生命周期与写入放在同一现有锁内，避免再引入一套日志基础设施。

## 固定上游证据与阅读覆盖

比较对象为本地 openminis 快照 `/tmp/openminis-review-20261004`，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。使用 git show 读取提交中的实际文件，没有用稀疏检出缺失推断上游没有实现；没有 fetch 或更新快照。上游来源分为：

| 项目 | 固定快照的情况 |
|---|---|
| Jieba JNI／Kotlin、三个词典实现头文件、TextSegmenter、LaunchCycleBeacon | 当前文件逐字相同，共享问题 |
| 工作线程 comm | 上游已有 TID 路径修正，当前未保留 |
| HangDetector | 上游使用 uptime 并过滤暂停／空闲误报，当前未保留 |
| SimpleDateFormat | 上游仍有共享格式器；当前额外失去关闭日志后的早退和格式化异常保护 |
| 输出流缓存、RPC 整文件读取、分享降级、ZIP 同步调用、日志分块解码 | 核对上游对应实现，相关缺口也存在 |
| 日志容量、writer 追加同步 | 上游已有对应控制，当前未保留 |

本轮全文阅读：jieba_jni.cpp、crash_handler.cpp；JiebaEngine、TextSegmenter、SystemSegmentEngine；DictTrie.hpp、HMMModel.hpp、Utils.hpp；AppLogger、LogcatTailer；NativeCrashHandler、CrashFileReporter、CrashFrequencyDetector；LaunchCycleBeacon、HangDetector；VoiceCorrection、LogManagementScreen；已有 TextSegmenterTest 和 TextSegmenterLanguageDetectionTest。大文件分段阅读后核对连续范围，截断片段单独补读。

DebugRPCHandler、MinisApp 和 AppNavigation 只核对相关调用片段，不计全文。搜索与上游 diff 均不计入当前项目的全文阅读数字。覆盖详情保存于 [阅读覆盖登记:1](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json:1)。

证据等级：ND01／02／04—12 来自实际源码、调用链及平台 API 语义；触发后在具体设备上的崩溃、OOM、ANR 时间或显示表现未运行验证。ND03 是静态确认的保证缺口与条件性风险，不能当成已发生死锁。没有对疑点实施修复。
