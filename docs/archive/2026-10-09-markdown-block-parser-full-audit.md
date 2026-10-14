# Markdown块解析器全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。MdBlockParser 776行分1—260、261—520、521—776完整阅读，无截断。旧217报告及全部台账不覆盖。仅静态审查，未修改代码、运行测试/构建、模拟器、设备、Root或云资源。本轮无新增确认缺陷。

## 主状态转换

parse规范化CRLF/CR、移除额外尾空行，逐行替换NUL后匹配开放容器；新块开始与lazy paragraph continuation分开处理。引用、列表项、脚注及缩进以列计算tab；代码围栏检查相同字符及至少原长度，闭合行只允许后续空白。块结束分别处理引用定义、code info/literal、HTML结束、list紧松、table与footnote标签。

实例持有doc/tip/refmap等状态，parse不重置，因而本身是一次性使用。当前查到的三个调用点（StreamingMarkdownTextContext、MarkdownBlockAdapter、测试MdHtmlRenderer）都是BlockParser(...).parse即时新建，不把未重置直接报成真实跨会话污染。没有以此证明全部外部未知调用安全。

## 表格分列候选

splitRow进入backtick run时立即设inCode，并在匹配长度run出现时退出；在inCode内不拆pipe。未闭合backtick会把后续pipe保留在同一个cell，finalizeTable随后按header列数截断或补空。若模型/流式尾输入含未闭合code span，这条分列规则与最终InlineParser如何处理未闭合反引号可能不同。

本轮只确认该状态行为，未全文核对InlineParser或规范该项，也未运行实际renderer，不宣称已经证实文字丢失/规范不符合。候选后续需要从原文本、块cell、inline结果到Compose渲染闭环。既有规范测试只按fixture测最终测试HTML，不能替代流式任意截断输入。

## 其它边界

parseDelimiterRow要求全部字符为pipe/横线/冒号/空白，单独无pipe横线不算table；header和delimiter列数相等才转换。table短行补空、长行截断是本层规则，不仅因截断字样报数据损坏。list ordered marker限制1—9位，段落中只有1起始才能打断；task marker在GFM时消费。footnote只在GFM start集合启用。

HTML块只是节点和literal收集，未执行HTML；后续展示/链接安全仍在adapter/renderer。深层container会遍历开放树、finalizeList也遍历子树，本文件未设全局深度/大小上限，但是否已有上游输入保护和UI降级需各拥有者，保留性能边界而不直接报ANR。

代码包含正则及substring重复处理；未实际测耗时，不凭语法形式宣布ReDoS。字符处理、实体表及inline helper的后续完整阅读继续保留，不把本文件全文等同全部Markdown解析和渲染完成。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/md/MdBlockParser.kt | 776 |

一个现版文件完整读取。新增全文1文件、776行；已全文重读0文件。其它rg定位不计全文。

累计全文1341 / 1758文件，205765行；partial 4，未登记413。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-md-block.json)。

校验旧217报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
