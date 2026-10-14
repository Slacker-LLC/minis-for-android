# 新远端0b96f7aa压缩职责拆分与覆盖迁移

日期：2026-10-07。当前origin/main `0b96f7aa2a46c313b896a6f66501586e7caba81a`，本地工作树仍`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。旧审查快照`37158d188a190416f2b16b45dfb112ecf53cdd7b`之后1提交、5路径变化：2修改、3新增。没有fetch/pull/checkout，使用现有Git对象建立独立只读审查快照。旧172份报告和全部旧台账保持原样。

本轮只静态阅读及新建报告/台账，无产品代码修改、测试编写/执行、构建、模拟器/adb/Root/设备或云任务。新增/重读全文4文件、1003行；没有把ChatViewModel diff浏览计成全文。未新增重复问题编号。

## 当前重构的实际边界

[CompactionPlanner.kt:34](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/CompactionPlanner.kt#L34) CompactionPlanner负责范围选择、prev marker起点、工具batch安全边界、摘要分块、anchor验证及UI灰显计算。[CompactionSummarizer.kt:49](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/CompactionSummarizer.kt#L49) CompactionSummarizer接provider/contextWindow/progress，负责transcript格式、file activity、摘要分块链、overflow安全split、调用计数及summary gate。将这部分从VM抽出有实际职责收益。

当前[ChatViewModel.kt:2021](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L2021) compact入口调用Planner，launch调用Summarizer、保存Room marker与更新UI仍在VM。不能把一次压缩职责拆分说成整个Agent Loop已脱离UI；也不根据注释“unchanged”免审新接线。

planner保留最新用户与完整tool batch、start clamp、previous summary计价；summarizer每个chunk重新按真实running summary打包、每次结果过gate，限制depth/call budget并传播CancellationException。结构保护真实存在，不描述为任意切断tool结果或完全无重试上限。

## CC01、CC02仍未闭环

[CompactionPlanner.kt:135](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/CompactionPlanner.kt#L135) Planner把anchorIdx传给未变化的AgentContextCompactor.planRange，取实际range.endExclusive作为compactEndIdx。当前compactor第196—225行仅用start>anchor拒绝，结束位置仍按整个history的recent floor/token/latest user选择，没有限制end<=anchor+1。旧CC01“压缩此处以上越过选中锚点”仍有同一实现支撑；代码搬家未改变该缺口。

[ChatViewModel.kt:2182](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L2182) marker insert仍runCatching，只日志记录失败，随后无条件_compactSummary更新。这是旧CC02失败写盘后状态仍推进残留，本轮读取到2190，不冒充对新版本后续所有success回调的全面重审。

summary gate的tool-call子串检测仍通过未变化AgentContextCompactor.containsToolCallSyntax；CC03普通摘要提协议字段误拒绝的根因未被拆分消除。敏感工具参数/结果仍placeholder，file activity尾部只取path，不据此声称所有敏感字符串都已全链路脱敏。

## 锚点与摘要信息保真候选

Planner verifiedAnchorIndex第197行rawDbIds为空时信任compactEndIdx；VM把真实空DB结果和读异常都映射同一个emptySet。新增PlannerTest也断言该行为，测试通过不能把它当失败关闭证据。是否应拒绝空DB、并发删除如何处理需结合完整会话提交链，本轮不强行新增确定问题。

Summarizer普通message/Text各take500、ToolUse非敏感input take200，小于pruner阈值的ToolResult take500；长输出由pruner保留head/tail。提示词“永不遗漏”不能恢复没进transcript的原文。旧消息被概括是一种产品取舍，需具体关键事实丢失反例，不以截断存在就新增必现任务失败。

file activity从ToolUse而非成功ToolResult取路径，因此记录“modified”表示曾发起操作，未必磁盘已改；caption称authoritative并不增加执行证据。作为旧逻辑迁出后的语义候选，未报成已实际误导模型。provider/contextWindow闭包每次取当前值，session/model变化是否被入口取消需完整VM审查，不靠局部代码猜测。

## 新/调整测试真实范围

PlannerTest107行完整：无持久消息拒绝、已覆盖anchor拒绝、长history保尾、prev起点、未知prev重头、raw-id回退、灰显/旧divider/缺cutoff。没有测试中间anchor作为范围上界、marker持久失败、空DB是否fail-closed或真实provider/Room/UI并发。

CompactSplitPredicateTest144行全文重读，修改仅把shouldSplitOnError引用移到Summarizer。它验证完整tool边界/代码fence/JSON结构，以及rate/auth/transport不split、其它provider error可split；不验证真实sendMessage/LLMRetryPolicy请求次数、超时或摘要字节。全部未运行。

## 版本覆盖迁移

新树1779路径，旧树1776；内容不同的VM和CompactSplitPredicateTest清掉旧范围/全文结论，新增3文件独立读。字节完全相同的文件继承覆盖，不能将变更行数少视作全文证据。VM当前仅登记2010—2190 partial。

| 本轮全文 | 行数 |
|---|---:|
| [CompactionPlanner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/CompactionPlanner.kt#L1) | 257 |
| [CompactionSummarizer.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/main/java/com/openminis/app/ui/chat/CompactionSummarizer.kt#L1) | 495 |
| [CompactSplitPredicateTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/data/CompactSplitPredicateTest.kt#L1) | 144 |
| [CompactionPlannerTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/0b96f7aa2a46c313b896a6f66501586e7caba81a/src/android/app/src/test/java/com/openminis/app/ui/chat/CompactionPlannerTest.kt#L1) | 107 |

Planner1—257、Summarizer分1—250/251—495、PlannerTest1—107，SplitPredicateTest初次混合输出截断后独立补读1—144。VM混合输出中缺失段2105—2190独立补读，合并片段2010—2190；其余VM未通读。

新台账：[2026-10-07-code-review-coverage-0b96f7aa-compaction.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-0b96f7aa-compaction.json)。新版本累计全文 **1294 / 1655文件、214984行**；partial5、not_registered356、excluded124。完整全库目标未达到。

本次直接比较前一生产快照已抽出的同职责实现；未追加上游读取，不凭旧OpenMinis引用判断此提交全部正确。交付校验旧172报告/旧台账hash、当前远端及本地/上游HEAD、tracked/staged空diff、新树全部1779blob/hash/bytes/lines和证据行号。只新建本文与新版本台账。
