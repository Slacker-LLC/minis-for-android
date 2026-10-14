# Token 计量、工具检查点与 Agent 预设审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P3**；全文阅读 5 个文件、494 行。只做静态排查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。另建报告，旧报告不改。

## TM01 / P3：压力估算遗漏结构化工具结果，只计空 content 的固定开销

[TokenMeter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/TokenMeter.kt:51) 的 estimateMessage 只读 message.content.length，不读 contentParts；estimateSessionTokens 对所有消息调用它。estimateToolResult 虽单独提供，但没有在该遍历中按 ToolResult 应用。

现役 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9616) 将工具结果加入 agentHistory：role=USER、content=""、contentParts=resultParts。因此 4 字符与 40000 字符的 ToolResult 消息都被估为 MESSAGE_OVERHEAD=8。这里即使按本工具自己的每 4 UTF-16 字符计一个 Token 的启发式，也应对 40000 字符结果产生约 10000 的文本项；实际遗漏不是不同模型 tokenizer 的正常误差。

[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:3728) 的 checkContextBeforeSend 把 effectiveAgentHistory 传 ContextPressure.compute，第 3730 行按该估算是否达到 80% 显示压力提示。ContextPressure 第 69 行直接使用 estimateSessionTokens。所以工具输出占主要内容的历史可被压力提示显著低估。需要 _lastTurnContextTokens>0 且有效 window 才运行该提示入口；本轮没有把首次未取得 usage 的发送也说成一定触发。

影响仅此启发式提示口径。真正发送/压缩检查在第 3736 行使用 ContextPolicy 与 _lastTurnContextTokens，工具预算/压缩另有估算，不由 TokenMeter 直接决定。不能因此宣称计费错误、模型请求必然越界或所有自动压缩失效。图片 Token 未计、系统提示是否纳入同一参考口径仍可继续核对，不另计。

修复方向：复用现役模型可见 part 的文本提取与估算，避免普通 content 与 Text part 重复计价，并按真实 ToolResult 计结构开销；无需更换 tokenizer 或新增框架。本轮只记录。

## 工具检查点复核，不重复既有问题

ToolCheckpointStore 全文确认 recordIntent 追加 pending，markDoneBatch 按 callId 更新 pending，replaceAtomically 写临时文件并 fsync 后原子 move，readPending 不消费记录。已有 39 行恢复测试验证原子替换、未完成记录重读不变化、done 不再视为 unknown，未运行。

现役 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9784) 执行工具前写 intent；[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10423) 先将工具结果提交 Room，再 markDone；[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:2792) 恢复时对已有 dbMessageId 的 ToolResult 批量 reconcile，随后 drainPending 追加 outcome unknown 提醒。检查历史没有在这里擅自把 pending 消费掉。原始参数副本、完成记录不断增长、整文件重写与删除会话未清 sidecar 已为旧 F32/F33，本轮不重新计数。

recordIntent 的 appendText 没有显式 fsync，失败捕获后执行工具继续；readPending 错误则返回空。停电/存储写失败的恢复保证、重复 callId、参数截成4000字符后的可恢复性需要结合失败合同与调用身份继续核对，作为候选，不把静态可能性描述成已发生的重复外部操作。恢复提示不展示 argsJson，只展示 toolName，也是当前有限信息设计，未读取真实检查点。

## Agent 预设与权限核对

AgentPresetRegistry 三个内置值、legacy alias、toolset 和 promptSection 全文阅读；77 行测试只验证纯 registry 值，不验证 SharedPreferences 与实际会话交互，未运行。standard/code 是 FULL，minimal 是 CORE；code 说明目前只是组合提示、没有 SDK executor，不能把声明限制当成已实现执行器。

applyToSession 的 permission=null 当前明确清除原 session permission，虽然 Preset 字段注释说 null=leave-as-is，行为与注释不一致。workspace-sandboxed alias 实际只返回 standard；注释所谓“permission gate preserved”不能单由 get() 证明。但切换时主动清除权限的产品要求需与独立权限设置入口核对，本轮不凭注释直接报安全降级。

presetForSession 对没有显式 session 值的历史会话动态使用 defaultForNewSessions；setDefaultForNewSessions 在本轮 main/java 检索没有调用方，不能把该 helper 的假设修改直接当现役 UI 切换后所有旧会话已变。ChatViewModel 的实际 toolset/promptSection 消费已定位；本轮没有变更预设或权限。

## 固定 OpenMinis 对照

只读 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。该树没有 TokenMeter、ToolCheckpointStore、AgentPresetRegistry 同名路径，不提供同路径直接对照。DSH 来源只按注释声明，不用来源说明保证本地计量已经覆盖结构化历史，也未独立核验论文观点。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [AgentPresetRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/remote/AgentPresetRegistry.kt) | 148 |
| [TokenMeter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/TokenMeter.kt) | 85 |
| [ToolCheckpointStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ToolCheckpointStore.kt) | 145 |
| [AgentPresetRegistryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/remote/AgentPresetRegistryTest.kt) | 77 |
| [ToolCheckpointRecoveryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/ToolCheckpointRecoveryTest.kt) | 39 |

累计全文 **1152 / 1546 个文件、224615 行**；partial 13，not_registered 381，excluded 124。全仓通读尚未完成。AgentPresetRegistryTest 的首次合并输出截断已独立全文重读。ContextPressure/SessionPermissionStore 等已 full 文件只复核，不重复计数；ChatViewModel 仍 partial，追加本轮干净片段。

完成时核对全部 1670 tracked 路径 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 96 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，未访问实际偏好、检查点、会话数据库或凭据。
