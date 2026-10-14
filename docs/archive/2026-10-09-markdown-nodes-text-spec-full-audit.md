# Markdown节点、字符处理与规范测试全文审查

2026-10-09，固定主线3527ad8165f11c0f98d8a30f96098fc9280faa94。旧216报告和全部旧台账不覆盖。只静态阅读，未改产品源码、运行测试/构建、模拟器、设备、Root或云资源。本轮无新增确认缺陷。

## 节点与字符处理

BNode持有块树、list/fence/table/task/footnote元数据；INode以parent/prev/next/first/last维护内联双向树，移动前unlink。没有在这里执行HTML或URL，真正解析和最终renderer仍需各自全文，不把节点数据结构当整个解析链已验证。

MdText覆盖可转义ASCII、Unicode标点/符号与空白、实体/反斜杠解码、reference归一化及URI百分号编码。已有%XX保留，其它不安全码点按UTF-8编码；sanitizeLine替换NUL。非法surrogate输入的最终编码行为和parser引用处理需要消费链，未仅由纯helper登记安全故障。

MdEntities解码逻辑1—27行可见：数字0、越界和surrogate回退替换符，未知命名实体保留原样。调用层ENTITY_HERE限定数字格式，不能把直接传负数字给decode的人工输入当真实Markdown崩溃。DATA长单行未完整可见，严格登记partial，不凭文件仅29行宣布整个实体表已读。

## 规范测试的真实验收范围

MarkdownSpecTest加载资源fixture，调用测试MdHtmlRenderer并逐例严格比HTML，记录失败样本到build报告。CommonMark上限0，GFM上限1；GFM断言只限制失败数量，没有锁定失败例身份，因此一个新回归替换原有失败也可能在数量不变时通过。这是测试能力限制，不代表当前已有新解析回归；未运行。

MdHtmlRenderer使用真实BlockParser/InlineParser，再按测试输出规则遍历节点，链接属性和普通text转义，HTML节点原样输出。它不是最终聊天Compose renderer；测试允许的tagfilter失败也不能证明App最终不会输出原始HTML，必须看实际UIrenderer。测试的footnote简化为sup编号、MATH输出literal、扩展NONE，范围不覆盖聊天所有自定义媒体/数学或导航行为。

输出工件仅是测试代码计划生成的文件，本轮没有执行、没有生成或宣称取得测试通过工件。

## 阅读与后续范围

MdNodes、MdText、MarkdownSpecTest在首次组合输出中的截断区之外完整可见；MdHtmlRenderer独立完整输出。MdEntities的DATA中间被截断，未计全文。BlockParser/InlineParser和fixture内容未在本轮全文阅读，后续继续，不能把文件名/规范标签当已验收实现。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/ui/chat/md/MdNodes.kt | 169 |
| src/android/app/src/main/java/com/openminis/app/ui/chat/md/MdText.kt | 90 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/md/MarkdownSpecTest.kt | 57 |
| src/android/app/src/test/java/com/openminis/app/ui/chat/md/MdHtmlRenderer.kt | 156 |

四个现版文件完整读取。新增全文4文件、472行；已全文重读0文件。其它rg定位不计全文。

累计全文1340 / 1758文件，204989行；partial 4，未登记414。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-md-basics.json)。

校验旧216报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
