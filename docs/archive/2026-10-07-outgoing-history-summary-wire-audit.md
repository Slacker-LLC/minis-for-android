# 新抽出聊天运行模块：压缩摘要发送丢失静态审查

日期：2026-10-07。远端固定HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`；本地工作区仍旧HEAD，不pull/checkout。新增全文4文件、733行，确认1项P1。只静态阅读，不修改产品源码，不编写/运行测试、模型请求、构建、模拟器、adb、Root、设备或云任务。旧报告、旧台账（包括上一轮新版基线台账）全部保留，仅新建本文和新的累计台账。

## OHS01 / P1：压缩摘要只写content，结构化用户消息发送仍取旧contentParts

[OutgoingHistory.kt:169](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/OutgoingHistory.kt#L169) effective为v2 marker后的第一个USER消息构造副本，只改content为summaryWrappedText加原content；contentParts按原值保留。注释称content是canonical，因此无需更新parts，这与真实Provider发送行为不一致。

实际构造 [ChatViewModel.kt:5943](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L5943) 排队消息文本加入AgentContentPart.Text(prompt.text)，第5996—6003行以content和combinedParts双表示加入agentHistory。下轮真实请求第7749行调用effectiveAgentHistory，再图像预算和roleplay投影；第2761行确认进入OutgoingHistory.effective及orphan修复。该形状不是只在测试中存在的虚构输入。

[OpenAIProvider.kt:2137](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt#L2137) 用户结构化分支提取Text parts，第2237行发送textParts.joinToString而不看msg.content。[AnthropicProvider.kt:557](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/anthropic/AnthropicProvider.kt#L557) 非空parts时遍历并发送part.text；[GeminiProvider.kt:224](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/provider/gemini/GeminiProvider.kt#L224) 同样使用非空contentParts分支及Text.text。因此静态例子：有效v2 anchor、有summary="EARLIER FACTS"，下一USER为content="继续"且parts=[Text("继续")]；effective的content包含摘要，parts仍只有“继续”，上述发送路径最终没有EARLIER FACTS。

后果是被压缩且不在有限warm-up窗口内的细节，没有通过摘要继续提供给模型。模型可能失去任务约束或已完成步骤的背景；数据库原历史与marker并未因此被删除，warm-up也仍保留部分原文。不是声称所有v1 marker、parts为空的消息或无post-anchor用户的独立摘要分支都丢摘要。故障前提是v2摘要注入非空结构化消息。没有测量模型行为、实际重复操作或调用真实账号。

orphan工具修复只改ToolUse/ToolResult对应关系，不把content补进Text；图像预算也不能补回摘要。不是网络API升级造成的不稳定事实，证明来自当前序列化分支本身。

修复方向：摘要注入时保持content与Text parts一致，或使用Provider实际消费的统一表示，并保留非Text媒体/工具块。不能仅将测试的joined(content)改成另一个镜像函数当验收；应检查真实发送payload。未实施修复或编写测试。

## 新测试为何未覆盖本问题

[OutgoingHistoryTest.kt:15](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/chat/OutgoingHistoryTest.kt#L15) user helper仅创建role/content/dbMessageId，contentParts为空。摘要测试第38行调用生产effective，但第27行joined及后续断言只观察content；因此它验证纯文本副本，不能证明真实双表示消息的wire包含摘要。其它用例覆盖无摘要/marker、anchor未找到、无post-anchor tail、v1 marker、孤立结果删除/孤立调用补错误、尾部in-flight豁免、已配对保持、token budget与message cap。

这些测试调用真实新模块，区别于手写镜像；缺口是输入形状及验证边界，不能笼统称所有新测试无效。没有运行它们或声称现有CI失败。

## 固定OpenMinis与本地旧实现对照

固定上游 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的ChatViewModel第4789—4829行已同时更新content和contentParts：有Text则给首Text加摘要，无Text则前置新Text，parts为空保留纯文本分支。源码注释附有历史设备解释，但本轮只以实际赋值证明上游处理差异，不把上游注释的设备测量当本项目证据。

本地旧HEAD ChatViewModel第2953—2955行也仅修改content，说明OHS01是仍存旧缺口，新模块提取没有制造新的同类问题，也没有修复它。之前138/139份报告未报告这个具体双表示摘要发送反例，本轮新编号不重复现有compaction边界/原文保留预算问题。

## AgentRunSetup及未完成的其它抽出模块

AgentRunSetup全文94行：caller/bot非空拼接、session说明块、ScheduledRunPromptPolicy安全注记；contextShape记录文本、工具结果、图像/音频统计及最大消息。该统计有双表示累计和近似tokens，主要用途是诊断日志，不将其直接当正式预算或用统计偏差另报当前截断故障。AgentRunSetupTest全文78行调用真实函数，覆盖空/base/bot/session/scheduled安全注记和shape/log；没有验证真实Provider请求。

SessionTitleGenerator首次与Setup混合全文查询发生截断，未升级全文。随后1—100行单独完整读取，登记partial；后续仍需读101—562及调用闭环。ChatMessageMapper仅定位路径，没有声称已通读。新模块提取不等于Agent Loop已独立于ChatViewModel，run调用仍在ViewModel；架构结论要继续以真实调用核查。

## 新增全文与覆盖记录

| 文件 | 行数 |
|---|---:|
| [OutgoingHistory.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/OutgoingHistory.kt#L1) | 435 |
| [AgentRunSetup.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/AgentRunSetup.kt#L1) | 94 |
| [OutgoingHistoryTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/chat/OutgoingHistoryTest.kt#L1) | 126 |
| [AgentRunSetupTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/ui/chat/AgentRunSetupTest.kt#L1) | 78 |

OutgoingHistory完整cat；Setup单独94行复读；两测试完整cat；均无截断。混合生产片段查询两次截断，之后仅论证相关provider、排队历史append、请求入口均以短片段单独重读；不将截断查询或Provider/ChatViewModel片段增加为全文。

新的累计台账：[2026-10-07-code-review-coverage-37158d18-outgoing-history.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-outgoing-history.json)。它从上一轮新版台账复制后增加阅读记录，没有覆盖上一轮文件。当前全文 **1146 / 1652文件、185159行**；partial5、not_registered501、excluded124，tracked1776。没有把旧HEAD较高完成度冒充新版覆盖。

交付核验：旧139份报告hash与两旧台账保持；本地旧HEAD和tracked/staged空diff、固定origin/main、全部1776快照文件hash/bytes/lines和Git blob一致、新增全文批次733行、本文固定GitHub链接与本地链接行号。本轮仍未完成全库目标。
