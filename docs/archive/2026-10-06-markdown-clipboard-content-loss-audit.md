# Markdown复制转换与选择工具栏静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文3文件、873行，确认2项P2内容丢失。仅静态阅读，没有修改产品源码、运行测试/构建/模拟器/adb/Root/设备/云/模型，没有读取凭据。新建独立报告，旧报告不改。

## MC01 / P2：行内代码先去围栏再解析强调，标识符和字面文本被修改

[MarkdownClipboard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MarkdownClipboard.kt:205) stripInline先处理图片/链接，然后第212行把反引号代码转换为内部文字，再继续第214—220行的强调/删除线替换。行内代码里的内容没有获得保护。

静态反例：消息含反引号包裹的foo_bar_baz。第212行得到foo_bar_baz，第219行匹配_bar_并删去两个下划线，最后复制为foobarbaz。反引号包裹的__init__会变成init；反引号包裹的**literal**会变成literal。此外在去反引号之前执行链接替换，代码中本应保留的[text](url)也会只剩text。它们是同一个未隔离行内代码的转换问题，不分别计数。

实际生产入口 [ChatScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:3217) 的助手回复普通“复制”调用markdownToPlainText，[MinisTextKitGesture.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MinisTextKitGesture.kt:1179) 的完整纯文本复制调用copyPlain。[MinisMarkdownTextToolbar.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MinisMarkdownTextToolbar.kt:242) 也复用copyPlain。原消息和原始Markdown复制不被修改；问题发生在派生纯文本，用户复制命令/标识符时拿到不同内容。

[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5643) 的startReplySpeech也用同一转换，故整条回复朗读输入同样被改变。不能因此推断模型历史或落盘源码已变更。copyRichText第33行也用该转换产生plain fallback；第225行开始的HTML分支先抽取代码占位、最后恢复，能保留foo_bar_baz，所以同一富文本剪贴数据的HTML与plain载荷可能不一致。

方向：优先复用现有解析能力，或像HTML分支一样先保护代码跨度，在格式处理后恢复原始内容；仅调换强调规则顺序不能保持代码的字面语义。本轮未实现。

## MC02 / P2：表格分隔行识别过宽，普通文本被整行丢弃

[MarkdownClipboard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MarkdownClipboard.kt:95) 的表格分隔正则为 ^\|?\s*:?-{3,}.*$，任意三个连字符之后允许任何内容，而且不要求前面有表格表头。匹配后直接continue，不输出该行。

静态反例：一条回复只有普通文本“---重要说明：不要删除数据”。它不匹配第92行只含分隔字符的水平线规则，却匹配第96行表格分隔规则，最终markdownToPlainText返回空字符串。“--- a/file”这类diff头放在普通段落中也会被丢弃；放在已识别的代码块内则被第62行inFence分支保留，不能泛化成所有diff都丢失。

真实调用与MC01相同，助手“复制”会复制空文本。startReplySpeech第5644—5646行若转换后为空直接reset并返回，因此上述单行可见回复不会进入朗读。不是TTS供应商或权限错误，也不是消息本体丢失。

本地真实MarkdownParser第156—158行只有存在下一行分隔候选时才尝试parseTable，第262—266行逐个cell要求只含冒号和连字符，包含“重要说明”的行不会通过该分隔检查。复制器没有沿用这个边界。方向：基于已解析表格结构跳过分隔行，或至少验证整行全部单元格语法与上下文；不能让任意---前缀文本消失。

## 已有测试、工具栏与暂不计数候选

CopyPlainTextTest全文119行，覆盖普通标题、强调、链接、表格内容、数学、围栏代码和列表。其代码保真只测围栏val x = 1，没有行内代码里的下划线/强调/链接字面内容，也没有---普通行。没有运行测试；存在这些测试不证明MC01/MC02已覆盖。

MinisMarkdownTextToolbar全文489行。完整复制标签明确Full scope，横向滚动保留长操作行，不把完整消息复制误算“应只复制选择区”。浮层坐标有窗口横向限制；gap=8是px而非dp，未以外观差异计为确定故障。

withSelection第204—224行用剪贴板借用选择文本，只在原剪贴数据非null时恢复；原为空时会留下临时选择内容，异常回调也没有统一吞掉。该工具栏接口的现实触发边界仍需完整Compose/MinisTextKit选择路径核对：当前ChatScreen没有SelectionContainer调用，虽然保留LocalTextToolbar注入和相关注释。不能把注释里的旧路径当作已确认的现役入口，因此本轮不新增剪贴板恢复故障编号。

上一轮MessageBoundsRegistry仅按纵向中心认定消息的候选仍未闭环，不借本轮工具栏注释升级为确定跨消息复制问题。MinisTextKitGesture已看到当前完整复制调用，但未全文读其1682行，不升级覆盖。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` Git对象HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其MarkdownClipboard.kt第95—97行相同的过宽表格分隔检测，第205—221行相同的stripInline处理顺序，MC01/MC02属于同源保留缺口。其MinisMarkdownTextToolbar同样只在original!=null时恢复剪贴板；这只是候选实现对照，未把它升级为本地当前真实入口证据。没有要求恢复上游PRoot/minisd或改动现役执行层。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [MarkdownClipboard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MarkdownClipboard.kt:1) | 265 |
| [MinisMarkdownTextToolbar.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MinisMarkdownTextToolbar.kt:1) | 489 |
| [CopyPlainTextTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/CopyPlainTextTest.kt:1) | 119 |

全文输出完整，工具栏分1—250、251—489读取。ChatScreen、ChatViewModel、MinisTextKitGesture与MarkdownParser仅做相关片段核对，不升级覆盖状态。

累计全文 **1285 / 1546文件、245719行**；partial13，not_registered248，excluded124。全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256、字节数、正数文本行数，本仓/上游固定HEAD、tracked/staged空diff、此前122份报告哈希、新报告链接行号与全文批次总计。只新建本报告并更新台账。
