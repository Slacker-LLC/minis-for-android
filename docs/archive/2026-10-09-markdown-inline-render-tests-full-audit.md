# Markdown内联渲染及链接、HTML、片段、adapter测试全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。五文件完整输出，无截断。旧219报告和全部台账保留。未改产品代码、编写/执行测试、构建、模拟器、设备、Root或云资源。

## MDI02 / P3：HTML href实体未解码，链接注解带原始转义

MarkdownInlineRender.html读取attr(attrs,"href")到Open.href，close直接addStringAnnotation("url",o.href)。attr只用regex提取引号/非引号原文，没有实体解码。输入 <a href="https://example.com/?a=1&amp;b=2">go</a> 会显示go，但链接注解是含字面&amp;的地址，而非普通HTML属性语义中的&a=后第二参数分隔形态。这里具体期望地址为https://example.com/?a=1&b=2。

同仓Markdown显式链接的destination在MdInlineParser通过MdText.unescapeString/normalizeUri处理；HTML属性路径未复用该实体处理。此项确认解析到注解的差异，不声明所有浏览器/后续处理一定失败，也未运行实际点击或HTTP请求。建议在HTML属性取值边界复用实体解码并保持URL消费权限规则，交Claude处理。

## 当前渲染与旧MDI01覆盖

parseInline使用真实InlineParser生成AnnotatedString，LINK添加url、CODE添加inline_code（不含外侧U+2006垫片），math生成inline placeholder；HTML支持已知文字样式/换行/image alt，未知tag保持字面。comments/processing/declarations隐藏，未执行网页脚本。链接是否能调用外部scheme仍取决于消费层，不由本文件注解生成直接认定安全突破。

BareUrlLinkTest真实调用parseInline并检查url annotation，覆盖标点、括号、中文邻接、angle/显式link及小写www/email；没有大写WWW，所以不覆盖MDI01。只是读取断言，不称当前测试已通过。

## 四个测试的范围

MarkdownHtmlAndUnderscoreTest覆盖identifier下划线、强调样式、br、命名/数字实体、known/unknown tag及未闭合b。它没有HTML a.href、实体编码的href或实际点击，无法否定MDI02。

MarkdownFragmentsTest调用真实split及parseMarkdownBlocks，覆盖标题/段落/list/fence/table、增长前缀、reference随片段、code不注入reference、footnote保持全片段。没有前批冻结take前缀与完整列表coalesce条件的比较，不将“切点不移动”测试扩大为全部FlatChatItem keys稳定。

MarkdownBlockAdapterTest调用真实adapter/parseInline，覆盖setext/list/fence/缩进/table alignment及escaped/code pipe/task/nested list/quote/reference/footnote/math/HTML/details。未闭合code fence与未闭合inline backtick是不同情况；已有前者用例不能证明表格未闭合inline候选已处理。

部分assert只检查局部形态，例如stray details的kinds取take(1)，不会断言完整输出是否多余。测试不用设备布局，不覆盖虚拟列表滚动、异步重排或所有点击来源。

本轮新增确认显示问题1项MDI02，MDI01保留旧编号；其余候选仍未虚构运行结果。五文件全文均计入现版覆盖。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/MarkdownInlineRender.kt | 185 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/BareUrlLinkTest.kt | 56 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/MarkdownHtmlAndUnderscoreTest.kt | 68 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/MarkdownFragmentsTest.kt | 71 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/MarkdownBlockAdapterTest.kt | 198 |

五个现版文件完整读取。新增全文5文件、578行；已全文重读0文件。其它rg定位不计全文。

累计全文1347 / 1758文件，207217行；partial 4，未登记407。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-md-render.json)。

校验旧219报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
