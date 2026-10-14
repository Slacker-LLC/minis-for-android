# 上下文压缩范围、持久化与摘要校验静态审查

日期：2026-10-06。本轮新增独立报告，确认 3 项问题：2 项 P2、1 项 P3。只审查和记录，不修改产品代码或旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读取既有 Git 对象。本轮没有构建、执行测试、启动模拟器、使用 adb/Root/设备/云资源、访问账号或读取凭据。下述路径推演来自静态代码，不是运行结果。全代码通读仍未完成。

## CC01 · P2 · “压缩此处以上”不再以选中消息为范围上限

[确认弹窗](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt:6352)将用户选择的消息 ID 交给 `compactBefore(targetId)`；[入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2010)解析它并作为 `anchorIdxOverride` 传给压缩函数，[调用方](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2192)继续把锚点传给 `planRange()`。

但是[范围规划器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentContextCompactor.kt:196)只在 `start > anchor` 时使用锚点拒绝已压缩范围；选取结束位置时则扫描整个 history 的安全切分点，按总长度减去最近四条消息选取初始 end，并按尾部 token 预算继续向后移动。最后只保护最新用户轮，没有把 end 限制在所选锚点以内。[实际摘要切片](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2209)和[新标记边界](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2382)均使用规划结果，而非用户所选的位置。

可核对的静态例子：历史有五组已持久化的普通用户/助手消息，共 10 条，每条约 400 个 ASCII 字符；选择第二条用户消息，即索引 2，执行“压缩此处以上”。`start=0`、`anchor=2`，安全 end 为偶数，初始 end 取 6；后四条的估算小于 4,000-token 保留预算，最新用户索引为 8，也不会把 end 往前收。因此结果为 `[0,6)`，不但包含所选消息，还包含其回答和下一组对话。只要后续摘要正常完成并通过现有大小/缩减检查，标记会落在索引 5，灰显和摘要范围都越过用户所选位置。这里没有假定模型一定生成某个摘要。

`effectiveAgentHistory` 仍会[按保留预算回取部分近期原文](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2850)，所以不声称越界范围的原文马上全部停止发送，更不声称数据库原消息被删除；缺口在于用户选择的范围与实际摘要、边界标记不一致。

[现有锚点测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/AgentContextCompactorTest.kt:156)只验证 `start > anchor` 的拒绝；[最近消息保护测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/AgentContextCompactorTest.kt:169)验证末尾保护，没有覆盖中间锚点限制。未运行这些测试。

固定 OpenMinis 的[入口](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L3443)也使用锚点，但[实际切片](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L3593)是 `history.subList(effectiveStartIdx, anchorIdx + 1)`，不会继续跨过锚点之后的消息。这个对照只说明本地新增规划器丢失了上限约束，不代表上游所有边界语义都正确。

修复方向记录：在同一范围规划器中明确手动锚点的包含/排除语义，再在该上限内选择完整轮次和工具批次；自动压缩的尾部预算不能覆盖用户手动选择的范围。

## CC02 · P2 · 压缩标记保存失败后仍切换状态并报告成功

[标记落盘](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2403)把 DAO 插入包在 `runCatching` 中，失败只记日志；随后无条件写入 `_compactSummary`、`_cachedLatestMarker`，重建灰显 UI，并[显示成功分隔条](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2464)。`compactSucceeded = true` 不依赖插入结果；[最终回调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2517)向等待中的调用方报告成功，排队提示词也可以继续被排出。

首次压缩的标记插入抛错时，当前进程使用内存标记发送摘要和保留历史，用户看到“messages compacted”；再次打开会话，[恢复逻辑](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4658)只从数据库读取最新标记，读不到刚才的标记就令摘要和缓存标记为 null。[有效历史入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2813)随后返回完整历史。若之前已有标记，则重开恢复旧标记，而不是本次压缩结果。用户刚看到的成功边界无法持久保留，后续请求的历史范围也随重新加载变化。

恢复中的[createdAt 自愈](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4895)只更新一个已有、但边界不再可解的标记；没有在缺少标记时从成功分隔条 payload 重建标记。不能用这条自愈路径解释为已覆盖插入失败。

这是明确的异常处理路径，不声称常规写入每次都会失败，也不声称原消息永久丢失。本轮没有制造存储故障、连接设备或访问数据库运行实例。

固定 OpenMinis 的[保存路径](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L3806)记录了 `markerPersisted`，成功后会登记后续失败的回滚信息，但插入失败仍继续更新内存摘要和标记；[重载路径](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L6541)也从数据库恢复。因此“插入失败仍被当作成功”在该固定版本同样存在，不能直接照搬认为上游已解决。

修复方向记录：成功必须建立在标记已提交的事实之上；提交失败时保留先前标记和摘要，显示失败并向等待方返回失败。提交后的 UI 更新也应有明确一致性处理，不能以日志替代提交结果。

## CC03 · P3 · 普通摘要提及带引号的协议字段会被当作工具调用

[工具调用检测](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentContextCompactor.kt:65)列出文本标记，包括 `"tool_calls"`；[实现](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentContextCompactor.kt:316)只做忽略大小写的子串包含判断。[真实校验调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:3421)把它的布尔结果交给 `validateSummary()`，[拒绝条件](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentContextCompactor.kt:294)则把任何 `hasToolCalls=true` 的摘要拒绝为 `CONTEXT_SUMMARY_INVALID`。

合法纯文字摘要例如：`本轮修复了 "tool_calls" 字段的合并逻辑，尚未修改权限策略。` 没有工具调用对象、名称、参数或调用 ID，只是在描述一个协议字段；仍会命中该子串而失败。模型正常结束、摘要长度合规也不能通过。总结 XML 语法而引用 `<invoke` 等字符串时同样可误判。

[摘要请求](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:3630)没有提供工具定义，随后仅将 `response.text` 和 stop reason 交给摘要门槛；这个检查应区分可解析的调用结构与普通文字引用。现在的[纯文字测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/AgentContextCompactorTest.kt:366)只覆盖无引号的 `tool_use` 和 `file_read`，没有覆盖带引号的字段名。被拒后[异常分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2481)保留原历史并显示失败，不是内容损坏或工具被执行；影响是正常开发对话的压缩可能反复失败。

固定 OpenMinis 树中没有本地新增的 `agent/AgentContextCompactor.kt`。此处只确认本地实现缺口；代码注释提到的 Eta 解析器不能据此当成已核验的 OpenMinis 实现，本轮没有声称上游同样误判。

修复方向记录：复用已有适用的结构化解析或明确调用结构识别，保留真正调用载荷的拒绝，同时允许普通文字、字段名和语法说明。不要移除摘要完整性检查。

## 本轮读过但未列为新问题的行为

摘要分块会带入前块摘要，并在真正发出下一块前重新计价；溢出拆分先完成并校验前半摘要，再把它传给后半。还存在深度、调用数量和总时长限制。边界提交前会再次检查数据库可解析锚点是否切断工具批次。本轮没有把这些已经存在的保护写成缺失，也没有把未知 stop reason 的兼容接受或超过 100% 的压力展示单独列为问题。

`CompactToolPairingTest` 和 `CompactDividerPlacementTest` 中部分算法是测试内复制的逻辑，不能据其断言替代生产 ViewModel/DAO 链的运行证据。本轮只读取测试源码，没有声称它们执行通过。

## 阅读范围与完整性记录

本轮新增全文阅读 16 个文件、2,253 行：

| 文件 | 行数 |
|---|---:|
| [AgentContextCompactor.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentContextCompactor.kt:1) | 343 |
| [CompactBudget.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/CompactBudget.kt:1) | 29 |
| [CompactSplitPredicate.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/CompactSplitPredicate.kt:1) | 154 |
| [ContextPolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/ContextPolicy.kt:1) | 91 |
| [AutoCompactPrefs.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/AutoCompactPrefs.kt:1) | 65 |
| [ContextPressure.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ContextPressure.kt:1) | 89 |
| [ToolBatchRepair.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/ToolBatchRepair.kt:1) | 122 |
| [AgentTurnHandle.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentTurnHandle.kt:1) | 40 |
| [AgentContextCompactorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/AgentContextCompactorTest.kt:1) | 419 |
| [CompactBudgetTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/CompactBudgetTest.kt:1) | 67 |
| [InLoopContextPolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/InLoopContextPolicyTest.kt:1) | 86 |
| [AgentTurnHandleTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/AgentTurnHandleTest.kt:1) | 53 |
| [CompactSplitPredicateTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/CompactSplitPredicateTest.kt:1) | 144 |
| [ToolBatchRepairTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/ToolBatchRepairTest.kt:1) | 128 |
| [CompactToolPairingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/CompactToolPairingTest.kt:1) | 234 |
| [CompactDividerPlacementTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/CompactDividerPlacementTest.kt:1) | 189 |

补读 `ChatViewModel.kt` 的 2005–2539、2800–2865、3390–3749、4640–4695、4750–4785、4865–4910，以及 `ChatScreen.kt` 的 6335–6368；这些大文件继续标记为 partial。资源字符串仅作搜索核对，没有据此登记为通读。

覆盖台账累计：tracked 1,670，纳入范围 1,546，排除 124；full 864、partial 14、未登记 668，全文阅读行数 179,397。full 只计本轮及先前已完整读取、且内容哈希仍一致的文件，不把检索命中或调用链摘读算入全文。

本轮保存前重新核验所有 1,670 个受跟踪路径的集合、SHA256、字节数及有文本行数记录的行数；HEAD 未变，tracked/staged diff 均为空。此前 55 份报告逐一核验哈希，均未改动。新报告链接和行号、固定 OpenMinis 对照对象在保存后再次核验，结果登记在交付检查中。
