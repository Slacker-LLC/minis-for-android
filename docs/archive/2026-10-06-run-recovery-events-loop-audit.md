# 运行恢复、事件持久化与工具循环检测静态审查

日期：2026-10-06。本轮新增独立报告，确认 5 项问题：4 项 P2、1 项 P3。只审查和记录，不修改产品代码或旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读取既有 Git 对象，没有拉取或执行上游代码。本轮没有构建、执行测试、启动模拟器、使用 adb/Root/设备/云资源、访问账号或读取凭据。以下调用序列均是静态推演，不是设备验证。全代码通读仍未完成。

## RR01 · P2 · 已保存的中断流式正文未恢复，检查点被直接删除

[运行入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5878)创建 run；[初始化检查点](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4222)返回 recorder。模型流出的正文在[Text delta 分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8355)持续交给 recorder，[合并与落盘](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointRecorder.kt:40)按 512 字符或到达新片段时的时间间隔追加 `assistantDelta`。这意味着一部分正文可能已在 sidecar 中持久保存，而助手消息尚未提交到 Room。

[正常无工具回答](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9083)在模型流完成后才调用 `persistAssistantTurn`；[提交封装](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10331)随后写聊天记录并关闭检查点。`PendingAssistantTurn` 的[不可取消提交保护](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PendingAssistantTurn.kt:19)保护已经开始的提交，不能让进程被系统直接终止时尚未调用的提交发生。

可成立的窗口：用户消息已提交，模型已流出 1,500 字符，其中至少一个 512 字符批次成功写入检查点；模型流尚未结束，进程被系统终止。重新打开会话，[加载路径](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4519)从聊天消息表重建，而[检查点协调](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10490)把旧 run 判为 Interrupted 后直接 `remove()`，没有消费 `events`、`transcript` 或 `contextSnapshot`。正文恢复并未发生，已落盘的恢复副本反而被删除。

[尾部形状检测](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4706)可以把尚未回答的用户消息标为可 Resume，所以本项不声称继续按钮必然消失。缺口是无法恢复已流出的正文，后续继续从聊天历史发起，不能从检查点还原那段正文。

独立的 `SessionEventHub` 确实会异步存储原始事件，[远程重连入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:567)也能按旧 cursor 读取它们；但 native `loadSession` 没有从这些事件重建助手消息，[远程新快照](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:619)叠加的是当前进程的内存 tail。这不等于检查点恢复已实现，也不声称所有副本立即永久消失。

修复方向记录：复用现有消息提交与事件投影，在删除中断检查点之前实际恢复可验证的正文并记录中断状态；工具输入、工具副作用仍不能仅凭残缺记录自动重放。恢复失败时保留可重试的记录。

## RR02 · P2 · 每次追加整份 transcript，记录上限不能约束检查点文件总量

[助手提交后](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10442)把新助手项加入累积列表，再保存整份列表；[每批工具结果之后](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10457)再次保存整份累积列表。[Store 保存 transcript](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:149)把本次列表裁到 512 项、2 MiB，再将整个 JSON record 追加到同一个 JSONL 文件。旧 transcript record 不被替换、压缩或裁剪。`saveContext()` 同样反复追加快照。

所以 2 MiB 只是单次 record 内容的限制，不能把一个 run 的 sidecar 约束在该大小内。举例：20 轮工具执行，每轮新增约 20 KiB 的非敏感结果，助手内容很小；单次最终 transcript 约 400 KiB，完全没有触发 2 MiB 裁剪。仅结果后的 20 次快照就重复写入约 `20 KiB × (1+…+20) = 4,200 KiB`，再算每轮助手提交后的快照，累计约 7.8 MiB，还没有包含 context 和流式 event records。这是调用数量与内容长度的静态计算，没有执行工具或写入样本检查点。

[孤儿清理](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:230)只限制文件数量 32，并不限制每个文件或目录的总字节数；[列表读取](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:221)又会将每个文件完整扫描后解码，恢复一个会话时调用方先读取所有 run 文件再按 session 过滤。较长的工具任务会产生重复落盘和重开扫描负担。

Agent 仍有[200 轮上限](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:391)，所以不声称一个 run 的工具轮数无限；它不足以让声称的 2 MiB 检查点限制成立。流式 event 的数量上限也不能限制反复追加的 transcript/context records。本项与旧报告中另一套 `ToolCheckpointStore` 的执行意图文件生命周期问题不同。

修复方向记录：复用已有有界存储模式，让 transcript/context 的最新值替换或做有总字节预算的提交，或只追加可恢复的增量；清理和读取也应覆盖实际总文件大小，不只约束单次 payload。

## RR03 · P2 · 不可用工具绕过循环检测，重复幻觉工具策略无法触发

[本轮工具白名单检查](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9237)会先拒绝不在本轮工具表中的名字。这条权限检查是正确的，不能为循环检测而放松。问题是拒绝分支只[调用 record](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9263)，忽略返回的警告后便在 9281 行 `continue`；真正的[check 调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9384)在该分支之后，因此重复不可用工具始终到不了 `check()`。

[unknown_tool_repeat 策略](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/ToolLoopDetector.kt:101)只在 `check()` 中达到连续 10 次错误后返回 CRITICAL；`record()`只会产生警告。实际错误文案 `Tool 'name' is not available.` 能被[错误名称解析](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/ToolLoopDetector.kt:381)识别，但计数正确也无法弥补接线位置错误。模型连续请求同一个不存在的工具、每轮都被白名单拒绝时，每次仍只收到相同的普通错误，达不到更明确的停止重试信号；其他 generic_repeat 的 record 警告在该分支也被丢弃。

[现有测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/ToolLoopDetectorTest.kt:117)直接交替调用 detector.record/check，可以验证 helper，却没有覆盖 ViewModel 中先拒绝后 continue 的真实分发路径。本轮未执行测试。全局 200 轮上限仍在，本项不声称无限循环，也不声称不可用工具被执行。

固定 OpenMinis 的[同类检测策略](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/ToolLoopDetector.kt#L117)仍在 check 中；[实际调用点](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L12607)对非 scripted 调用执行检测，[未知名字执行结果](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L13173)再返回 Unknown tool。本地新增的早期拒绝需要同时接入循环反馈，不能仅继承检测器类就认为集成完整。这个对照不评价上游并行分发是否满足所有其它要求。

修复方向记录：保留白名单拒绝，并在该拒绝路径调用适用的循环检查、传递警告/阻断反馈；所有真实分发分支都应遵循相同的计数与反馈约定。

## RR04 · P2 · 敏感工具绕过聊天脱敏，原始参数和结果写入持久事件表

[敏感策略](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ToolSensitivePolicy.kt:34)把 MCP、记忆、短信、联系人、位置等工具归为敏感；[聊天提交](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10341)会替换敏感参数，[工具结果提交](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10413)会替换敏感结果。这些保护在相应聊天记录和 run transcript 中确实存在。

但真实流在[工具参数完成时](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8629)先把完整 `chunk.args` 交给 emitter；[工具执行结果](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9541)将实际输出放入 live block，随后调用 emitter.toolResult。[原始参数事件](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt:872)、[结果事件](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt:936)和[序列化 helper](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt:1168)均未使用 `ToolSensitivePolicy`。[Hub append](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt:224)将原始 JSON 排入持久化队列，[IO 写入](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt:672)再原样放入 `SessionEventEntity.payloadJson`；[DAO](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:145)将它写入 `session_events`。

因此一次已授权的敏感工具调用，例如 `memory_write` 的私人正文，聊天消息中是 placeholder，事件记录中却保留原始输入。读取短信等工具返回的个人内容同样可以在 `tool/result` 中留下原文。无需开启 WebSocket 客户端或连接额外服务器，日志镜像就会持久化这些事件；没有读取真实私人数据、凭据或运行敏感工具来核验。

事件表确实有保留窗口，并非无限永久保存，但“只存脱敏聊天记录”不能涵盖同一个 Room 数据库里的原始副本。[重连 API](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:567)还会提供持久事件回放。本项限定在本地持久副本及已有认证回放面，不声称未经认证访问或已发生远程窃取；不重复旧报告中的 Logcat 和 `ToolCheckpointStore` 原始参数副本。

修复方向记录：复用现有敏感分类，在持久化事件的边界脱敏，同时保留事件身份、顺序和结构；若 live UI 需要原文，应明确区分其瞬时投影与持久镜像。不要让不同存储通道各维护一份敏感名单。

## RR05 · P3 · 检查点终态写入追加在末尾，拒绝重入却只读首行

[start](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:102)创建的首行只有 run 身份、来源、payload 和时间，没有 state 字段；[markTerminal](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:199)把终态作为新 record 追加在末尾。它和[appendEvent](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:125)却调用只读首行的 `readHeader()`，再检查 `header.optString("state") == STATE_TERMINAL`。这个比较不会看见末尾的 terminal record。

据当前实现，合法调用 `start → markTerminal → markTerminal` 的第二次终态追加不会因已有终态而拒绝；`start → markTerminal → appendEvent` 也不会被终态阻止。`saveTranscript` 和 `saveContext` 则只检查文件是否存在，连首行终态检查都没有。[完整读取](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:317)遇到第二个 terminal 时还会把 checkpoint 标为 damaged。

本项是存储 API 的明确协议缺口：文档声称的“首次终态胜出、拒绝后续写入”没有由持久层实现。当前 ViewModel 的[finish wrapper](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10475)在一次 finish 后把 recorder 置 null，正常顺序路径可减轻发生概率；没有据此声称每次运行都会重复终态或重放工具。当前协调器还[优先把 terminal 当 Completed](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunRecoveryCoordinator.kt:65)，故不能把重复 terminal 的 damaged 标记解释为已观察到的工具副作用错误。

修复方向记录：复用完整 record 状态读取或明确的终态状态存储，在所有写入口统一拒绝终态后的追加；调用方置空字段不能替代持久层的协议约束。

## 已核对的保护与对照边界

敏感分类支持现役 canonical 名称和别名；run context 中的工具部分也使用该策略。`PendingAssistantTurn` 对 normal completion 与 Stop 的同一提交有互斥及不可取消边界。中断工具不应仅凭 checkpoint 自动执行；本轮也没有这样做。循环检测器会稳定排序参数并忽略 `tool_title`，所以未把换标题导致漏计数列为当前问题。

固定 OpenMinis 树中不存在本地的 `RunCheckpointRecorder.kt`、`RunCheckpointStore.kt`、`RunContextSnapshot.kt`、`RunRecoveryCoordinator.kt`、`SessionEventHub.kt`、`ToolSensitivePolicy.kt`。RR01/RR02/RR04/RR05 是本地新增链路的核对结果；注释里引用 Eta 不能替代对固定 OpenMinis 的源码核验，也不能据此声称 Eta 存在相同缺口。

## 阅读范围与覆盖

本轮新增全文阅读 13 个文件、3,252 行：

| 文件 | 行数 |
|---|---:|
| [InterruptedTailDetector.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/InterruptedTailDetector.kt:1) | 79 |
| [RunCheckpointRecorder.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointRecorder.kt:1) | 117 |
| [RunCheckpointStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt:1) | 345 |
| [RunContextSnapshot.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunContextSnapshot.kt:1) | 100 |
| [RunRecoveryCoordinator.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/RunRecoveryCoordinator.kt:1) | 101 |
| [ToolLoopDetector.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/ToolLoopDetector.kt:1) | 418 |
| [InterruptedTailDetectorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/InterruptedTailDetectorTest.kt:1) | 96 |
| [ToolLoopDetectorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/ToolLoopDetectorTest.kt:1) | 302 |
| [PendingAssistantTurn.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/PendingAssistantTurn.kt:1) | 28 |
| [PendingAssistantTurnTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/PendingAssistantTurnTest.kt:1) | 76 |
| [SessionEventHub.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionEventHub.kt:1) | 1213 |
| [SessionEventHubTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/SessionEventHubTest.kt:1) | 74 |
| [ToolSensitivePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/ToolSensitivePolicy.kt:1) | 303 |

补读 ChatViewModel 的运行初始化、流式参数、工具拒绝/结果、助手提交、检查点协调、Stop 提交与恢复相关片段；继续标记为 partial，覆盖台账只记录实际完整读取区间。复读 HeadlessChatRunner 和 DAO 的相关片段不重复计数。源码输出发生局部截断的片段已补读，不把截断输出算成全文。

累计 tracked 1,670，纳入范围 1,546，排除 124；full 877、partial 14、未登记 655；全文阅读行数 182,649。现有测试仅作为源码证据，没有执行，也没有新增测试。

保存前后核验全部 tracked 路径、SHA256、字节数及有文本行数记录的行数，HEAD 保持固定版本，tracked/staged diff 为空；此前 56 份报告哈希保持不变。新报告的本地链接、行号和固定上游对象另做完整性检查。
