# 流式Markdown投影与代码块管道字符静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文2文件、562行，确认1项P3。仅静态阅读源代码和已有测试，不改产品代码、不编写或运行测试、不构建/启动模拟器/adb/Root/设备/云/模型，不读取真实会话或凭据。独立新报告，旧报告不改。

## SPJ01 / P3：代码围栏里的管道命令被当成待确认表格，已显示的命令会在流式输出中消失

[StreamingMarkdownProjection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/StreamingMarkdownProjection.kt:53) project先调用ambiguousTableStart并截取source，之后才在第56行判断代码围栏。ambiguousTableStart没有代码区域状态；[StreamingMarkdownProjection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/StreamingMarkdownProjection.kt:144) 只要末行起点大于零、非空且有未转义pipe，就返回该行起点。shell中的管道符是代码内容，不能用作该行是Markdown表格的证据。

可逐句复核的短输入（不是运行过的测试）：

1. 流式source为字符串 `"```sh\nprintf x"`。不存在pipe，tableSafeEnd是全长；findOpenFence识别未闭合围栏，输出 `"```sh\nprintf x\n```"`。此时用户能看见命令printf x。
2. 后续追加到 `"```sh\nprintf x | head"`。末行从offset 6开始，containsUnescapedPipe=true；ambiguousTableStart返回6，projected只剩 `"```sh\n"`。findOpenFence再补闭合标记，得到 `"```sh\n```"`，命令整行消失。
3. projectOrFallback无法阻止：两行均非blank，confirmedPrefixLength=0；投影非空，coversConfirmedPrefix返回true。StreamingMarkdownProjectionSession虽保存acceptedSource，却不保存“曾发布的前缀”或将此前画面纳入这一检查，不能据baseline存在声称已显示命令受到保护。

这是项目自定义投影算法的静态推导，没有执行Kotlin或Compose。临时隐藏由管道字符触发，输入很短，不依赖大文本降级、实际shell执行或工具返回。完成输出时project(isComplete=true)返回raw source，通常恢复命令，因此**不是持久聊天内容被删除**。

### 真实显示入口

[ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3656) AssistantMarkdownBlock实际调用MarkdownBlock，传入rawText/isStreaming。[StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:1018) live分支调用projectionSession.project，再把renderedSource交给parseMarkdownBlocks。短代码片段远低于第967行的大块降级阈值，不会被plain-text tail路径避开。

[StreamingMarkdownText.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StreamingMarkdownText.kt:733) splitMarkdownIntoBlockTexts在fence内部累积完整片段，flush第738行只trimEnd换行；上述未闭合围栏与末尾pipe仍能原样到达投影。不是只存在于没有调用者的helper。较完整的StreamingMarkdownTextBody也在第601行接入同一个session，问题不限于单一展示函数；completed fragment在第896行走rawText缓存/解析而不再投影。

### 修复方向与证据边界

先根据同一份Markdown区域状态识别代码围栏，再在围栏外判断候选表格；保持合法表格延迟发布和虚拟闭合能力。后续验收应覆盖代码逐步从无pipe追加为pipe、多个管道、代码字面表格行、blank line后的代码行、围栏关闭及正常GFM表格。此处只是记录方向，没有实施或编写验收代码。

## 已有测试的实际覆盖

[StreamingMarkdownProjectionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/markdown/StreamingMarkdownProjectionTest.kt:1) 全文158行。测试确实调用生产StreamingMarkdownProjection/Session，验证complete identity、open/closed fence、强调/代码marker虚拟闭合、escape/intraword、pending link与settled-prefix fallback、候选表格及delimiter、非append替换和terminal exact-source门。

其中open fence输入只有val x = 1，没有pipe；table测试在围栏外；fallback只测pending link跨blank-boundary。因此已有测试没有交叉验证“代码围栏内pipe不参与表格识别”，不能用这些测试存在来否认SPJ01。没有运行测试或将其结果宣称通过。

## 全文其它核对与暂不计数点

- 完成输入直接return source，verifiedTerminalSource要求isComplete且originalSource与调用者当前内容完全相等；渲染快照的校验不表示修改数据库，也不表示流式投影被写回消息。
- pending link/image的截断是明确展示策略；普通方括号、未闭合URL在真实EOF恢复，不将该策略本身另计缺陷。代码ticks使行内代码区域跳过link/强调扫描。
- Session每次仍从当前source重算投影，acceptedSource只是baseline；没有incremental parser状态复用。非append分支清baseline与已有测试对应，不把不可变String当可变状态。
- 表格候选、fenceMarker和inline delimiter是简化判定，对缩进/blockquote/list nesting及多重marker的全部语义是否与实际block parser一致，仍需具体输入和现役路径证据；本轮不凭“不是完整CommonMark parser”概括报错。
- 没有重新统计注释噪音、翻译、基础设施复制或lint基线，也没有改动用户交给其他Agent的清理内容。

## 固定OpenMinis对照

只读已有 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。git tree中ui/markdown仍有MarkdownParser/MarkdownText/SyntaxHighlighter/KaTeXView；没有本轮StreamingMarkdownProjection.kt及StreamingMarkdownProjectionTest.kt同路径，git show确认source路径不存在。只能说该固定上游没有这一独立文件，不能据路径不存在断言上游所有流式渲染都没有同类问题。

文件注释标注Eta移植来源，来源注释不是上游行为证明；本轮没有联网下载Eta或论文。SPJ01以本仓实际投影与现役展示入口为依据，不建议恢复minisd/PRoot或替换Direct Ubuntu架构。

## 覆盖与完整性复核

| 新增全文文件 | 行数 |
|---|---:|
| [StreamingMarkdownProjection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/markdown/StreamingMarkdownProjection.kt:1) | 404 |
| [StreamingMarkdownProjectionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/markdown/StreamingMarkdownProjectionTest.kt:1) | 158 |

投影源码分1—210、211—404读取，测试1—158完整读取，输出无截断。ChatScreen/StreamingMarkdownText只做相关片段复核，不重复登记全文。

累计全文 **1321 / 1546文件、255807行**；partial13、not_registered212、excluded124，全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256/字节数/正数文本行数、两仓固定HEAD、tracked/staged空diff、此前130份报告哈希、新报告绝对链接与行号、全文批次总计。只新建本报告并更新覆盖台账。
