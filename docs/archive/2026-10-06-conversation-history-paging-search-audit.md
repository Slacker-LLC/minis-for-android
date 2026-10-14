# 会话历史工具分页与搜索审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2**；全文阅读 3 个文件、455 行。静态排查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。独立报告，旧报告不改。

现役 [MinisApp.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:574) 注册 ConversationHistoryHandler 与 conversation_history 别名。execute 的 sessionId 来自运行入口，不提供模型指定其它 session 的参数。[ChatRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:758) 直接委托 ChatDao.loadMessagesPage，[ChatDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:491) 按该 session 的 sort_order/created_at 进行 limit/offset 读取。本轮问题不是跨会话读取。

## CH01 / P2：分页前永久截断每条消息，后半段既不能续读也不能搜索

[ConversationHistoryTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/history/ConversationHistoryTool.kt:78) 把每条原始 MessageEntity render 后立即传入 boundedEntry；[ConversationHistoryPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/history/ConversationHistoryPolicy.kt:39) 将超过 MAX_ENTRY_CHARS=2000 的文本 take(2000)，不保留原长度、截断标记或后半段。后续 window 的 offset 是相对于这份缩短后的文本，而不是持久消息全文。

静态反例：当前会话只有一条 3000 字符的纯文本消息，第 2500 字符附近有关键词 needle。read 默认 max_chars=4000，只看到前 2000 字符；由于短文本已经读完、数据库 page.size=1，工具返回 next_message_index=null、next_offset=null、truncated=false。再指定 offset=2000，window 的 skip>=entry.text.length 直接跳过，仍无续读。search 也对同一 entries 搜索，needle 不在缩短后的文本里，结果为 0，scan_truncated=false，因为唯一消息已经加载。

此时记录还在数据库，并非物理删除；确认的是工具不能履行“上下文压缩后重读先前准确指令”的完整恢复语义，且未明确提示每条消息的后半段不可见。2000 字符的单条上限可以作为输出分片预算，但不应在分页之前丢掉后续 offset 的数据源。工具描述第 207 行还承诺 continuation 读取同一 transcript，容易让调用者把前缀当作完整结果。

[ConversationHistoryPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/history/ConversationHistoryPolicyTest.kt:68) 的中途续读测试使用仅 500 字符的 Policy.Entry，不经过生产 entries() 的截断；第 99 行的 boundedEntry 测试只确认截断到 2000。这两者没有验证大消息的完整连续恢复。修复方向：保留原文本作为 offset/搜索的数据源，按每次预算分页，或者明示有损摘录并提供现有可恢复入口；不能返回无截断的全文终点假象。只记录。

## CH02 / P2：搜索接收 max_chars，却只回显参数而不限制片段总量

[ConversationHistoryTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/history/ConversationHistoryTool.kt:100) 将 max_chars clamp 到 256—8000，再传 search。[ConversationHistoryTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/history/ConversationHistoryTool.kt:167) 只按 MAX_SEARCH_MATCHES=20 与每片段最多 400 字符生成 matches；第 190 行将 max_chars 写入 JSON，但没有任何搜索内容预算判断。query、JSON metadata 还另加输出。

静态输入：两条各 600 字符、开头含 needle 的消息，query=needle、max_chars=256。各片段为 400 字符，两条片段合计 800，已经超过声明的 256；count=2 小于 20，truncated=false。不需要超过 1000 消息的扫描范围，也不需要巨量响应才能触发。参数 256 与 8000 在同一搜索结果上几乎只改变回显字段，不改变片段数量/长度。

这里将 snippet 文本预算与 JSON 包装开销分开：即使约定 max_chars 不含元数据，800 字符片段本身也违约。下游工具总输出截断即使存在，也不能保证等于这次请求的 256，更不能生成搜索语义的正确截断/续读状态。read 模式 window 会应用预算，不属于同一遗漏。

已有 [ConversationHistoryPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/history/ConversationHistoryPolicyTest.kt:122) 搜索测试验证最多 20 条及每条最多 400，但没有验证 Tool.search 的 max_chars 总预算。修复方向：复用现有字符预算在搜索返回时限制片段，并准确报告因预算/匹配数量停止的条件；只记录，未写新测试。

## 其它核对与暂不计数候选

search 最多扫描最早 1000 条消息，并通过 total>limited.size 报 scan_truncated；没有把未扫描的末尾误说成“全仓搜索漏报”。20 条恰好匹配时也将 truncated=true，但只是候选反馈精度问题，本轮不另计。page.size=200 时即使总量刚好 200 也多给一次空页续读，仍能终止，不当作无限循环。

render 只保留 text、toolUse/toolResult 摘录和 mediaRef 标记，其它 part 不呈现；工具输入/结果每项限制 160 字符，是真实有损投影。注释声称“敏感工具 payload 从未持久化”不能仅由此 formatter 证明；本轮没有读取实际用户消息或宣称全部 payload 已泄漏。JSONArray 解析失败回退原 partsJson 后仍受 boundedEntry 截断，但原始文本含什么需要具体存储链证明，暂不扩大。

loadMessagePageRaw 在截断输出前加载原始 JSON，200 条原始消息是否造成内存峰值还需结合已有消息上限与存储入口核对。SQL 使用 offset 的并发插入/删除语义、相同排序键的稳定顺序也是后续候选；本轮不凭单一 SQL 推断所有正常分页重复。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。该树没有 ConversationHistoryPolicy/ConversationHistoryTool 同名文件，不提供同路径实现对照。源码中的 Eta 来源只按声明记录，未用其注释保证替代本仓的分页/预算事实，也未独立核验论文建议。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [ConversationHistoryPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/history/ConversationHistoryPolicy.kt) | 100 |
| [ConversationHistoryTool.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/history/ConversationHistoryTool.kt) | 221 |
| [ConversationHistoryPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/history/ConversationHistoryPolicyTest.kt) | 134 |

累计全文 **1143 / 1546 个文件、223657 行**；partial 13，not_registered 390，excluded 124。全仓通读尚未完成。134 行已有测试全文静态阅读，未运行；现役注册/Repository/DAO 仅复核相关片段，已 full 文件不重复计数。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 94 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，未访问真实会话数据库、工具输出、凭据或用户文件。
