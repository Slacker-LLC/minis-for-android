# 流式推理前缀、引用与生成图片历史审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P3**；全文阅读 6 个文件、767 行。只做静态审查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。独立报告，旧报告不改。

## SF01 / P3：推理结束后的空白清理依赖分片边界，同一文本产生不同正文

[ThinkPrefixStreamParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/ThinkPrefixStreamParser.kt:105) 找到 </think> 后立即转 BODY，只在第 109 行清理当前 pending 中已有的前导空白；没有保留“推理结束、正文尚未开始”的状态。[ThinkPrefixStreamParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/ThinkPrefixStreamParser.kt:112) 的 BODY 分支遇到下一片内容时，会把先前 heldWhitespace 当成正文内部空白释放。

两个静态输入序列的完整文本都为 `<think>r</think>\n\nbody`：

- 一次 feed 完整字符串：关闭标签后当前 pending 包含两个换行，第 109 行删除，visible 为 body。
- 第一次 feed `<think>r</think>`，第二次 feed `\n\nbody`：第一次已转 BODY，第二次直接从 BODY 输出两个换行加 body。
- 若第二片只有换行、第三片为 body，换行先放入 heldWhitespace，第三片第 119 行再次释放，结果相同。

thinking 在以上序列都是 r，变化仅在 visible。关闭标签的部分字符与剩余空白跨片时也有同类条件：第 109 行只能清理解析关闭标签的那一片，不能覆盖后来才到达的剩余空白。

现役 [OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:1353) 逐个 content delta 调用 feed，第 1359 行直接发 visible，因此合法流分片方式能改变最终正文和显示。范围限定前导换行/缩进的清理不一致；不声称思维内容泄漏、模型正文全部丢失或每次回复都出现。四空格等前导空白还可能影响 Markdown 解释，但没有在本轮实际渲染验证，不能以此夸大严重度。

[ThinkPrefixStreamParserTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/ThinkPrefixStreamParserTest.kt:81) 起的拆分测试覆盖开标签内部拆分与闭标签内部拆分，闭标签剩余部分、空白和正文仍在同一片，所以没有覆盖关闭标签结束处及空白之间的拆分。已有纯正文前导空白保留测试说明修复不能简单对所有 BODY 做 trimStart。

修复方向：用已有状态机保留“推理已结束但尚未发出正文”的事实，跨片清理分隔空白，同时保留没有推理前缀的正文空白。只记录，未写新测试或代码。

## 引用与图片历史核对

ResponsesCitationFormatter 全文核对 flat/nested annotation、HTTP(S) 前缀过滤、偏移缺失、编号、重复 URL、URL 中 > 转义及末尾来源列表。ResponsesCitationStream 只在 end 等于已流文本长度时插入 marker，其它降级到来源列表，是明确设计，不因未在任意旧位置插标记就报故障。

现役 [OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:1096) 把原始 delta 累加到 responsesOutputText，annotation 第 1110 行以该长度判断，marker 发送但不加入原文长度；没有把 marker 自身长度误算进后续引用偏移。多 output/content item 的 offset 范围、UTF-16 与服务索引单位、标题 Markdown 转义仍是需要协议/渲染证据的候选，本轮不直接判定正常引用全部错位。来源列表默认标签按列表位置编号而 marker 取全局编号，是已有测试显式接受的格式。

GeneratedImageHistory 将 assistant 中的 ImageData/imageParts 移到下一条非 assistant 消息并添加来源说明；没有下一条时追加 USER 上下文。现役 [OpenAIProvider.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:654) 在构造请求前调用，三个协议分支第 678—682 行使用 requestMessages。助手普通文本和普通 user 图片保持原样，工具结果 part 与 tool_use ID 不被 helper 改写。已有测试覆盖两种 OpenAI 协议图片回放、工具结果配对和输入不变。

该 helper 对下一条非 assistant 的假定、两个图片字段同时含同一图时是否去重、累积图片的模型预算仍需其它真实构造路径证明，不把假设输入当成已确认系统图片回放错误。测试中 MockWebServer 只是静态阅读，未启动服务器、请求 Provider 或生成图片。

Think parser 的未闭合前缀保留为 thinking、正文中标签保留、finishTurn 幂等和尾部空白丢弃均已核对。resolveAtToolBoundary 在本轮 Provider 检索没有实际调用，不能把它的孤立行为当现役已接线恢复逻辑。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。上游 ThinkPrefixStreamParser 第 105—124 行同样只在转 BODY 的当前片清理，保留 SF01 条件；不因标注来自 iOS 就推断另一平台已解决。

该快照树有 ThinkPrefixStreamParser 和对应测试，但没有本轮 ResponsesCitationFormatter/GeneratedImageHistory 同名路径。仅说明同路径可比范围；不推断上游所有引用/图片实现都没有这些能力。Eta/iOS 来源声明没有在本轮独立核验，不用声明替代本仓调用链证据。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [GeneratedImageHistory.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/GeneratedImageHistory.kt) | 33 |
| [ResponsesCitationFormatter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/ResponsesCitationFormatter.kt) | 112 |
| [ThinkPrefixStreamParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/ThinkPrefixStreamParser.kt) | 197 |
| [GeneratedImageHistoryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/GeneratedImageHistoryTest.kt) | 77 |
| [ThinkPrefixStreamParserTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/ThinkPrefixStreamParserTest.kt) | 187 |
| [ResponsesCitationFormatterTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/provider/openai/ResponsesCitationFormatterTest.kt) | 161 |

累计全文 **1137 / 1546 个文件、222864 行**；partial 13，not_registered 396，excluded 124。全仓通读尚未完成。三个已有测试合计 425 行均全文静态阅读，未运行；Provider 片段合并输出截断处已单独重读，已 full 调用方不重复计数。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 92 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，不访问真实用户图片、Provider 账户或凭据。
