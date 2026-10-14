# 语音纠错与词汇学习静态排查

日期：2026-10-05。目标源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。openminis 对照快照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，本地目录 `/tmp/openminis-review-20261004`，未更新该快照。

本轮新增 10 项：1 项 P1、9 项 P2。仅阅读源码、测试源码和官方文档；未运行构建、测试、模拟器、adb 或设备命令，未修改产品代码。验收场景是后续验证建议，不是已执行结果。本报告独立保存，不覆盖之前的报告。

## VC01 · P1 · 旧纠错结果会覆盖用户后来编辑的草稿

[ui/chat/voice/InlineVoiceInputPanel.kt:267](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/voice/InlineVoiceInputPanel.kt:267) 把当前转录保存到局部变量，异步请求结束后在 293 行直接调用 `setTranscript(suggestion.corrected)`；没有检查当前草稿是否仍等于原文，也没有输入版本或请求失效判断。257–259 行会同步覆盖外部输入框。

等待期间，608–619 行的编辑、删除回调仍然可用；746 行的麦克风也始终启用。修改、清空或继续录音均可能改变正在纠正的那份输入。

**触发与影响：** 对 A 发起纠错，等待时改为 B，旧结果返回后 B 被修正后的 A 覆盖。这里确认的是同一面板内的异步覆盖；没有声称已验证跨会话污染或真机丢稿。

**对照：** openminis 面板 329–358 行也存在无条件应用结果的路径，属于共同缺口。

**处置方向与验收：** 应用结果前核对草稿版本，输入发生变化时废弃旧结果。后续通过真实输入流程及可控延迟端点覆盖编辑、清空、继续录音场景，保存操作录屏和最终草稿摘要。

## VC02 · P2 · 15 秒纠错预算无法终止阻塞请求

[speech/correction/VoiceCorrectionEngine.kt:76](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionEngine.kt:76) 在 `coroutineScope` 内启动 `async`，只给 `work.await()` 加超时，然后调用 `work.cancel()`。取消等待并不等于子任务已经退出；作用域返回仍需等待子协程结束，这是 [Kotlin coroutineScope 的公开语义](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/coroutine-scope.html)。

现役策略 [speech/correction/CorrectionStrategy.kt:84](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/CorrectionStrategy.kt:84) 调用 Provider 的非流式请求。[provider/gemini/GeminiProvider.kt:60](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt:60) 和 [provider/anthropic/AnthropicProvider.kt:98](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt:98) 在 IO 协程中直接执行同步 `Call.execute()` 与读取响应，没有把协程取消连接到 `Call.cancel()`。两者读取超时均为 10 分钟；共享 transport 配置也没有设置纠错请求的总时限。

**触发与影响：** Gemini 或 Anthropic 纠错端点长时间不返回时，15 秒后等待虽然超时，但引擎仍可能等网络阻塞结束，面板保持纠错中。结论限定这些已核对的请求路径，没有推广为所有 Provider。

**对照：** 上游引擎的作用域和超时结构相同，两个 Provider 也使用阻塞请求，属于共同缺口。

**处置方向与验收：** 请求取消必须真正终止底层 Call，并覆盖连接、响应读取及重试。后续使用延迟服务从 UI 发起纠错，保存实际返回时间和服务端连接结束证据；本轮未做网络运行验证。

## VC03 · P2 · 错误建议的负反馈没有接到生产调用链

[speech/correction/VoiceCorrectionRecorder.kt:188](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionRecorder.kt:188) 已实现建议被拒绝后降低置信度，调用 [speech/correction/VoiceCorrectionDb.kt:143](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionDb.kt:143)。但搜索整个现役 main 源码，仅有这个声明和内部数据库调用，没有 UI 或 facade 入口调用它。

面板自动应用结果后只调用 `captureSuggestionAccepted`；返回的 `appliedPairs` 没有保存为可用于负反馈的建议记录。用户手动改回原文会进入普通编辑学习，不能替代对原错误建议的降权。

**影响：** 文档及数据库具备“拒绝后降低推荐概率”的能力，真实输入路径却无法产生该负反馈。这里没有假设存在一个未看到的撤销按钮。现有 instrumented test 直接调用数据库的负反馈方法，只能说明 SQL 计算，不能证明 UI 接通。

**对照：** 上游 main 源码同样只有声明和内部数据库调用，属于共同缺口。

**处置方向与验收：** 保留本次建议的标识与替换对，把用户恢复原文的真实动作接到既有负反馈逻辑，同时核对候选缓存。后续从 UI 连续拒绝同一错误建议，保存置信度变化及再次检索结果。

## VC04 · P2 · 清空数据提前显示成功，并可能被后台任务写回

[ui/settings/ChatInputSettingsScreen.kt:356](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ChatInputSettingsScreen.kt:356) 调用清空后立即显示“已清空”。[speech/correction/VoiceCorrection.kt:230](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrection.kt:230) 只是向独立 IO scope 发起任务，没有等待完成或返回失败；[speech/correction/VoiceCorrectionDb.kt:389](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionDb.kt:389) 失败时只记日志。

清空事务只能保护它自身的删除操作。[speech/correction/TypedVocabularyBuilder.kt:77](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/TypedVocabularyBuilder.kt:77) 的逐词写入和 facade 的录入任务没有与清空形成同一个写入屏障；先取得消息的 builder 可以在删除完成后继续 upsert，并重新写回水位。录入和 builder 的 consent 检查也仅在任务入口执行。

**触发与影响：** 学习任务处理中清空数据，UI 先声称成功，稍后词汇或事件可能再次出现；数据库初始化或清空失败时也没有可靠失败提示。不能把该动作当作已完成的隐私删除。

**对照：** 上游 facade、builder 和数据库相同；旧设置页 SystemPermissionsScreen 340–348 行也立即提示成功。

**处置方向与验收：** 明确清空与在途写入的顺序，等待清空结果后再提示。后续在学习处理中执行清空并注入删除失败，保存清空完成时和后台稳定后两次行数、UI 结果及错误记录。

## VC05 · P2 · 时间戳分页会永久跳过页尾同时间的消息

[data/db/ChatDao.kt:287](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:287) 只查 `created_at > since`，按时间戳升序，最多返回一页。[speech/correction/TypedVocabularyBuilder.kt:78](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/TypedVocabularyBuilder.kt:78) 仅将本页最大的 `createdAt` 保存为下次水位；单页上限为 500。

**触发与影响：** 第 500 条和尚未返回的第 501 条时间戳同为 T，处理后水位成为 T；下一次严格大于 T，第 501 条及其它同时间记录永久漏学。只要同时间记录跨页即可触发，并不要求全部 501 条同时间。查询没有稳定的第二排序键或同时间去重补读。

**对照：** 上游 builder 全文一致，DAO 265 行使用同样的严格时间戳条件。

**处置方向与验收：** 用时间戳与稳定消息 ID 的组合水位，或有去重依据的重叠读取。后续通过正常历史导入及真实学习入口覆盖跨页同时间数据，保存完整消息 ID 与被处理 ID 的差集。

## VC06 · P2 · 词汇写入与水位提交没有原子性

[speech/correction/TypedVocabularyBuilder.kt:90](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/TypedVocabularyBuilder.kt:90) 每次 upsert 后无条件增加 accepted，整页最后在 112–115 行分别写水位与节流时间。[speech/correction/VoiceCorrectionDb.kt:230](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionDb.kt:230) 的写入返回 Unit，内部异常被吞掉，insert 失败返回值也不检查；调用方无法判断成功。

**失败方式：** 某条词汇写入失败、随后水位写入成功，会越过失败消息；相反，进程在若干 upsert 后、水位提交前退出，下次重读会对已写入词汇再次累加 frequency。页级 `Mutex` 不提供数据库事务或重放幂等性。

**影响：** 漏学和重复计数会改变候选排序及检索门槛。这里陈述的是明确可达的失败窗口，没有声称发生过现场数据损坏。

**对照：** 上游 builder 与数据库全文一致。

**处置方向与验收：** 学习结果与相应水位共同提交，写失败须传播，并提供可重放的计数语义。后续在真实学习流程的写入中断点结束进程并重启，保存两次词频和水位；另覆盖单次写入失败。

## VC07 · P2 · 并发录入会丢计数或插入重复纠错对

[speech/correction/VoiceCorrectionDb.kt:75](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionDb.kt:75) 先 SELECT 旧次数，再以旧值加一 UPDATE；不存在时用新 UUID INSERT。查询与写入之间没有事务或锁。479–500 行的表定义只约束 UUID，纠错对 `phoneticKey + correctedTerm + locale` 没有唯一约束。

[speech/correction/VoiceCorrection.kt:171](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrection.kt:171) 和 190 行的录入方法分别向同一个并发 IO scope 启动任务，生产路径允许重叠。

**触发与影响：** 两次学习同时读取 frequency=1，最终都写 2，少计一次；或同时读到“不存在”，生成两个 UUID 行，把证据拆开。后续查询 `LIMIT 1` 不能合并这些证据。单条 SQLite 操作的线程安全不保证整个读改写序列的原子性。

**对照：** 上游数据库全文一致，facade 也采用并发后台录入。

**处置方向与验收：** 为逻辑纠错对建立唯一性，并在数据库边界原子更新计数；已有重复行要核对迁移。后续通过真实输入入口制造重叠编辑，保存最终行数、次数与 variants，对照实际发生的学习次数。

## VC08 · P2 · 后台编辑学习可分配无界二次规模 LCS 表

[speech/correction/VoiceCorrectionDiff.kt:151](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionDiff.kt:151) 分配 `(a.size+1) × (b.size+1)` 的 Int 二维表，再完成二次规模计算。tokenPairs 按字符产生输入，没有计算预算。

正常选区替换从 [ui/chat/ChatScreenHelpers.kt:337](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreenHelpers.kt:337) 进入 recorder；[speech/correction/VoiceCorrectionRecorder.kt:81](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionRecorder.kt:81) 先做句子对齐及 diff，再决定是否值得学习。选区长度没有限制，[shared/SentenceSplitter.kt:1](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/shared/SentenceSplitter.kt:1) 也不会把无标点长段自动截为有界块。因此最终被判定为普通改写、不会入库，也不代表能避开这笔分配。

**规模与影响：** 两侧各 20,000 字符，单是 Int 单元约 1.60 GB，尚未计算对象与字符列表；可能造成 OOM、严重堆压力和长时间 CPU 占用。这是源码推算，没有运行大输入或宣称每台设备必然崩溃。后台协程不能隔离 App 堆内存。

**对照：** 上游 LcsDiff 也使用完整二维表，属于共同缺口。

**处置方向与验收：** 先限定学习计算预算，超预算跳过学习；不应截断用户草稿来掩盖问题。后续经真实编辑入口逐级增加文本长度，保存内存峰值、耗时和原始草稿完整性。

## VC09 · P2 · 支持的旧 Android 使用不兼容的 SQLite UPSERT

[speech/correction/VoiceCorrectionDb.kt:343](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionDb.kt:343) 使用 `ON CONFLICT(key) DO UPDATE`，这个数据库直接使用 Android 平台 SQLite。应用仍声明 [minSdk 26](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:222)。

[SQLite 官方文档](https://www.sqlite.org/lang_upsert.html) 说明 UPSERT 从 3.24.0 开始支持；[Android 官方 SQLite 版本表](https://developer.android.com/reference/android/database/sqlite/package-summary) 列出的 API 26、27、28 分别是 3.18、3.19、3.22，未单列版本使用最近较低 API 的版本。按该基线，API 26–29 的语法不兼容；厂商可能替换版本，因此不能断言每台该 API 设备都失败。

**影响：** 水位和上次构建时间写失败被内部吞掉，学习仍报告完成。后续发送再次从旧水位学习，反复累计首批词频，历史后页也可能一直到不了。这个问题不等于所有纠错功能完全不可用。

**对照：** 上游数据库全文一致，同样声明 minSdk 26。

**处置方向与验收：** 使用最低支持系统已有的数据库 API，检查元数据写入结果。后续在实际低 API Android 上执行两轮学习，保存 SQLite 版本、水位、节流时间和计数；本轮未启动任何设备。

## VC10 · P2 · 中英术语纠正的修改比例防护退回旧算法

[speech/correction/VoiceCorrectionDiff.kt:32](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionDiff.kt:32) 对原始字符算 Levenshtein；[speech/correction/VoiceCorrectionEngine.kt:106](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/speech/correction/VoiceCorrectionEngine.kt:106) 在比例超过 0.5 时拒绝结果。

**具体反例：** `看看遗穴 → 看看Issue` 是恢复一个英文术语，当前算法替换/补入 5 个字符，比例为 5/7≈0.714，直接判为 `diff_too_large`。这发生在 tokenPairs 和后续录入规则之前，因此调整词汇或多信号 admission 无法解除这个拒绝。

**对照：** 上游 VoiceCorrectionDiff 32 行先构造 editUnits，把连续 ASCII 字母数字作为一个单位，该例距离 2、最长 4 个单位，比例 0.5，允许通过。上游 VoiceMixedTermRestoreTest 129–135 行还明确覆盖这个反例。本项目没有这个测试文件，当前原始字符算法与该功能要求不一致。这里确认确定性防护差异，不声称已经重新测过模型输出质量。

**处置方向与验收：** 核对并恢复既有混合语言修改单位语义，无需引入新的工具或语言框架。后续经语音纠错 UI 返回该建议，并覆盖真正整句改写仍被拒绝，保存原文、返回文本和 UI 最终内容。

## 阅读范围与证据边界

本轮新增全文阅读 30 个文件、7,680 行；此前已经全文登记的 VoiceCorrection.kt 只做复核，不重复计数。全文进度由 165/1546 增至 195/1546，仍未完成全仓阅读。Provider 的 Gemini/Anthropic 请求和 ChatScreen 调用位置仅按读到的片段登记，不能用本报告声称这几个大文件已全文审完。

原生分词故障沿用上一份 ND01，本轮没有把它重复算作新增。模型提示词和视口上下文相对上游也有差异，但本轮未把未经完整产品语义核对的质量判断扩成确定 Bug。

本轮覆盖清单见 [阅读台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。报告写入后核对所有受版本控制文件摘要、旧报告摘要及 Git diff，确保只有新报告与阅读台账发生写入。

