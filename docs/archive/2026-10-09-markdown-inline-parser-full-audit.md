# Markdown内联解析器全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。MdInlineParser 874行分1—300、301—600、601—874完整阅读无截断。旧218报告及全部台账保留；未改产品代码、执行测试/构建、模拟器、设备、Root或云资源。

## MDI01 / P3：大写WWW前缀经过入口判断，却被后续检查拒绝

urlAt先rest.startsWith("www.",true)接受大小写不同的www前缀，进入scheme空串分支；后续domain.startsWith("www.")没有ignoreCase。具体纯文本WWW.example.com或Www.example.com会在此返回null，小写www.example.com则产生LINK节点。这是同一函数大小写规则前后不一致，限定自动链接显示，不影响显式Markdown链接或用户复制网址。

生产MarkdownInlineRender.kt:38—51新建该InlineParser(gfm=true)并parse；:81—84只对LINK节点添加url annotation，TEXT只append。故这个地址不会得到同路径的自动链接注解。未运行Compose或设备点击，不宣称所有其它识别器均无法打开。记录方向是统一www分支大小写判断；只记录交Claude。

## 上批未闭合反引号候选的证据补充

parseBackticks找不到相同长度闭合run时，pos回到开run后，并将开run写成TEXT；剩余字符继续普通解析。因此未闭合code span在inline层确实不是CODE。block splitRow此前却从开run起保持inCode直到行尾而不拆pipe，两层规则存在不一致。实际表格渲染与流式中途转列影响仍需完整adapter/UI消费，候选暂不升级为确认文字丢失。

## 其它解析边界

parse每个block重置subject/pos/delimiters/brackets，footnoteOrder故意跨文档块共享。resolveReferences以SourceEdit按位置倒序替换并避重叠；parseReference处理标签限999、目的地/title、首定义保留。显式link结束后禁用之前的非image bracket，避免nested link；image允许子label节点。链接协议安全依赖消费端，不仅因parser保留destination称可执行脚本。

强调处理有flanking/模3及openersBottom规则，tilde仅1/2长度处理；math依赖ext hook，NONE不解析自定义数学。mergeTextNodes和autolink递归遍历树；深树与反复扫描的性能边界需要输入规模/真实消费，不本轮虚构耗时。

HTML_INLINE仅生成literal节点，实体由MdEntities处理。GFM plain autolink跳过LINK/IMAGE/CODE/MATH内部，避免重复；中文全角标点终止地址，尾标点剥离。EMAIL_LITERAL不代表真实邮箱可投递验证。MdEntities长表仍partial，当前文件阅读全文不替代它的覆盖。

本轮新增确认显示问题1项MDI01；MarkdownInlineRender仅38—90片段、adapter仅32—68片段，不计额外全文。既有测试只定位，不执行或新增。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/md/MdInlineParser.kt | 874 |

一个现版文件完整读取。新增全文1文件、874行；已全文重读0文件。其它rg定位不计全文。

累计全文1342 / 1758文件，206639行；partial 4，未登记412。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-md-inline.json)。

校验旧218报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
