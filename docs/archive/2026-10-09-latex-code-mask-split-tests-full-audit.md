# 数学表达式、代码屏蔽与增量切分测试全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。三文件完整输出，无截断。旧220报告及全部台账保留。仅静态阅读，未改产品代码、编写/运行测试、构建、模拟器、设备、Root或云资源。本轮无新增确认缺陷。

## 行内数学预扫描范围

InlineLatexTest真实调用collectInlineMathLatex，覆盖短变量/标识符、反斜杠命令、相邻表达式、粗体/list中的公式、currency及escaped dollar、display dollar与table pipe伪公式。若预扫描为某公式注册slot，这不直接证明Compose实际绑定并绘制KaTeX；渲染还需INLINE MATH node/placeholder/slot拥有者。不能把测试注释“faithful proxy”扩大为设备视觉验收。

粗体/list场景用contains断言，不限制无关额外formula数量；其余部分有exact list。这里只说明各断言强度，未运行、未发现实际多收集回归。

## 代码屏蔽回归测试范围

LatexCodeMaskTest的latexOf组合extractDisplayMath及真实newChatInlineParser，递归读取MATH node；并用parseMarkdownBlocks后的raw拼合检查中间和尾段保留。覆盖未闭合display delimiter不配对到后续fence、普通单/多行display、无glyph多行、段落空行拒绝、inline closer不跨inline code及正常inline。

它没有经过最终KaTeX WebView/Compose或实际会话流式增量。latexOf会对提取后整个文本执行inline parse，与生产逐block/fragment的调用条件可能不同；因此通过这个辅助函数（本轮未执行）也不能证明所有嵌套容器都正确。未闭合inline backtick与table splitRow的候选仍由前批记录，不因这里有inline code用例宣布修复。

## 增量切分断言范围

SafeInlineSplitOffsetTest真实调用safeInlineSplitOffset，检查inline code中的双星不阻塞、未闭合backtick按literal、未闭合bold不切、切点在newline后及短文本无split。大样本由90行正文和300字符尾部构造，只验证offset性质，不比较切分后与整段解析完整AST/annotation一致性，也不覆盖所有reference/HTML/math跨界语义。

这些是直接helper回归测试，不是复制实现，但仍不等于端到端聊天验收。无执行产物或通过状态可报告。后续继续阅读未覆盖的实现与消费者，三文件本轮计入现版全文。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/test/java/com/openminis/app/ui/chat/InlineLatexTest.kt | 85 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/LatexCodeMaskTest.kt | 136 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/SafeInlineSplitOffsetTest.kt | 43 |

三个现版文件完整读取。新增全文3文件、264行；已全文重读0文件。其它rg定位不计全文。

累计全文1350 / 1758文件，207481行；partial 4，未登记404。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-latex-tests.json)。

校验旧220报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
