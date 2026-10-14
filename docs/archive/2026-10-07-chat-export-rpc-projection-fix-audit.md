# 会话导出与聊天RPC投影的远端修复复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文5文件、991行，复核已有问题，不另计新编号。仅静态阅读，没有修改产品代码、编写/运行测试、实际导出或请求RPC、访问真实会话、构建、模拟器、adb、Root、设备或云任务。此前146份报告与旧台账保留，新建本文与新版累计记录。

## 修复状态

原编号来自[导航/分享/导出报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-navigation-share-export-audit.md)与[聊天RPC报告](/home/jiale/projects/minis-for-android/docs/archive/2026-10-06-chat-rpc-mutations-audit.md)。

| 原编号 | 固定源码结论 |
|---|---|
| EX01 导出重复复用ZIP路径 | 顺序导出已有时间戳区分；同毫秒并发仍没有独立性保证，部分修复 |
| EX02 附件统计schema与Markdown误称图片 | 图片/视频计数已认识mediaRef；Markdown仍将全部mediaRef数量写为image(s)，部分修复 |
| EX03 Markdown元数据声明text | Summary.format已区分markdown，原错误已修 |
| CR03 includeTools=false仍有工具parts | 真实messageToJson已调用visibleParts过滤，原问题已修 |
| CR04 attachments漏mediaRef | 真实isAttachmentPart包含mediaRef，原问题已修 |
| F40 导出无稳定快照 | 本轮原生导出分页仍count+多次OFFSET查询，原入口残留 |

“已修”是所述静态调用链闭合，未运行端到端验收，不扩展成全部并发/历史/设备行为已验证。

## EX01残留：时间戳不是独占文件分配

[ChatExporter.kt:135](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt#L135) ZIP路径从固定stem改成stem+currentTimeMillis的36进制字符串；旧“每次导出先删同路径”去掉，并仅清同stem超过一小时的产物。这能区别大部分先后导出。

但同一session/title的两个exportToZip可并发运行，分别有不同workDir，却仍能在同一毫秒算出同一zipFile，随后FileOutputStream直接创建/截断，不用CREATE_NEW、独立UUID或锁。A与B即便只是同格式也会指向同一最终路径；不同格式可交错写ZIP或覆盖先前内容。真实SessionListScreen第3025/3062行都调用此入口，exportToZip内部没有单会话串行边界。仅确认条件成立时文件身份相同，不声称每次普通点击或所有设备都能稳定撞到同一毫秒。

归旧EX01独立产物要求的残留，不因路径后缀换了再加一项。已有workDir使用UUID，记录方向可复用现有唯一身份为最终产物名，并完整写好后发布URI；未修改。超过一小时删除是另一个阅读窗口合同，不能从“保留一小时”推断接收方任意晚读仍有保障。

## EX02部分修复与EX03

[ChatExporter.kt:424](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt#L424) countAttachments对mediaRef.value.mimeType lowercase后分类image/video，并保留旧image/image_url/video/video_url。新测试直接调用生产helper，覆盖两图片一视频一PDF、旧类型和坏JSON，不是镜像算法。普通PDF不属于imageAttachments字段计数，不能把该字段为0本身当错误。

但Markdown第242行把所有mediaRef数量传Block.User.attachmentCount；[ConversationMarkdownExporter.kt:39](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ConversationMarkdownExporter.kt#L39) Labels.imagesFormat仍是%d image(s) not included，第85行直接用attachmentCount格式化。因此只带PDF/音频的用户消息导出仍会说“1 image(s) not included”。这是EX02原种类文案问题，不说原附件字节被删除，也不要求这个ZIP必须携带媒体。

Summary在第303行明确json/markdown/text，writeSessionMeta第350行写summary.format；Markdown ZIP不再被metadata声明为text，EX03静态已修。estimatedBytes仍是文本字符估算且不计所有渲染标记，不将名称estimated当精确字节合同误报。

## F40原生导出快照残留

[ChatExporter.kt:168](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt#L168) 先messageCount，再forEachBatch按offset<total加载50行；[ChatRepository.kt:826](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt#L826) 原样委托DAO；[ChatDao.kt:530](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt#L530) SELECT按sort_order/created_at排序并LIMIT/OFFSET，没有这整个导出共用事务或固定记录集合。

删除早期行会改变后续OFFSET，编辑/重试/追加也能使批次代表不同瞬间。只是count固定不能让已有行的身份稳定。此为F40已记录的原生导出调用方，未新增编号，不把少于总数直接推断永久丢DB消息。读取都是raw行，没有旧CLI先投影再过滤的那一种count口径问题，两种原因必须区分。

## 聊天RPC读取投影

[ChatDebugMethods.kt:132](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/ChatDebugMethods.kt#L132) visibleParts在includeTools=false时滤toolUse/tool_call/tool_use/toolResult/tool_result；第159行真实messageToJson采用该返回值，派生toolCalls/toolResults也按开关控制。attachments投影判断使用isAttachmentPart，第126行包含mediaRef。[DebugRPCHandler.kt:153](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/DebugRPCHandler.kt#L153) 五个chat读取方法真实dispatch到本模块，HeadlessChatRunner第621行也调用messagesList，不是无调用helper。CR03/CR04原输入形状已修。

sessionsList最多返回500但先listSessions，逐会话lastMessageParts；sessionsGet只为count仍loadMessages，messagesList先全量load再角色过滤/分页，sessionsUsage也全量读取。每个返回limit不能证明读取内存已按页受限，这些为预算候选，未用真实长会话制造OOM或把正常全量统计直接计缺陷。

ToolTrajectoryNormalizer优先value再flat，保留raw，统一toolUseId/input/output及success/isError。缺失成功字段不伪造success；支持旧别名本身不表示旧minisd/PRoot回到生产。reasoning仅在includeReasoning时返回，坏JSON降原文；未将其说成敏感权限过滤器或凭坏形状直接计泄漏。

## RpcParams与已有测试

RpcParams对存在的错误类型（含JSON null）返回-32602；缺失返回null表示leave unchanged，stringList逐项检查、stringMap逐值检查。RpcParamsTest覆盖缺失、合法空字符串/false/空list、JSON null、标量类型与集合错误。它只验证helper，不证明所有mutation入口都已采用；本轮没有把ChatDebugMethods仍用optString的读取接口误报为凭据被覆盖。

AttachmentCountTest不覆盖独立ZIP分配、Markdown文案、元数据和分页快照。已有全文的ConversationMarkdownExporter本轮完整复核但不重复增加覆盖；Normalizer旧测试只有既有覆盖，此轮未运行或创建测试。固定OpenMinis对照沿用旧报告已读取的 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 对象证据，不fetch或拿上游旧代码替代当前本地修复事实。

## 覆盖与交付

| 新增全文文件 | 行数 |
|---|---:|
| [ChatDebugMethods.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/ChatDebugMethods.kt#L1) | 405 |
| [RpcParams.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/RpcParams.kt#L1) | 42 |
| [ChatExporter.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ChatExporter.kt#L1) | 449 |
| [RpcParamsTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/debug/RpcParamsTest.kt#L1) | 65 |
| [ChatExporterAttachmentCountTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/share/ChatExporterAttachmentCountTest.kt#L1) | 30 |

Exporter1—225、226—449，ChatDebugMethods1—220、221—405完整输出，其余三文件完整cat，无截断。Repository/DAO、DebugRPCHandler、HeadlessRunner及UI仅定位调用片段，不计全文。

新的累计台账：[2026-10-07-code-review-coverage-37158d18-export-rpc.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-export-rpc.json)。全文 **1186 / 1652文件、191359行**；partial4、not_registered462、excluded124，tracked1776。全库目标继续。

交付核验旧146份报告与旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、远端1776快照Git blob/hash/bytes/lines及本文证据行号。只新增两文件。
