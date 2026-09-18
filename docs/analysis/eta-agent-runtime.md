# Eta 能力对照分析：Agent 循环、工具合同、上下文与恢复

> 分析对象
>
> - Eta 侧（只读参考）：`/tmp/eta-upstream-clone`，HEAD `c15de97`（2026-09-12），单 `:app` 模块。
> - Minis 侧：`/home/jiale/projects/minis-eta-integration`，分支 `codex/eta-capability-integration`，基线 `origin/main = 69e05e51`。
> - Eta 为 PolyForm Noncommercial 1.0.0，允许按非商业目的复制、修改与派生；本仓库**直接复用其代码**，按本文规格适配到 Minis 的包结构、API 与测试边界。许可条款与 Required Notice 集中放在 `third_party/eta/LICENSE`，归属声明集中在 `PROVENANCE.md` 与 `THIRD_PARTY_LICENSES.md`，代码文件内不重复写许可头。
> - 所有 Minis 判断都带 `文件:行号` 证据，行号对应当前工作树快照；所有 Eta 判断都带路径证据。

## 1. 结论摘要（按移植价值排序）

1. **运行检查点与恢复决策**（价值最高）。Eta 把"在途 run 的 UI 事件、脱敏 transcript、模型上下文快照"三件互不替代的东西一起落盘，并用"checkpoint + 终态 outbox + Runtime 活跃状态"三者对账，把恢复动作分成 已完成 / 重新接入 / 已中断 三种，且只有状态已知时才判定中断（`agent/runtime/AgentRunCheckpointStore.kt`、`agent/runtime/AgentRunRecoveryCoordinator.kt:19-54`）。Minis 目前有界会话事件日志、工具意图 checkpoint 和冷启动尾部形状检测，但没有"在途 run"的上下文快照与归属状态，恢复能力明显更弱。

2. **工具调用入参的合同校验**。Eta 在工具体执行前用模型本轮看到的 JSON Schema 校验参数（类型、必填、枚举、数值范围、组合、本地 `$ref`），失败直接产出 `INVALID_TOOL_ARGUMENTS` 且不执行（`agent/model/AgentToolCallValidator.kt`、`agent/model/AgentLoop.kt:259-265`）。Minis 的 preflight 只检查必填字段与空串，把类型/枚举/范围校验留给各工具自己（`.../ui/chat/ChatViewModel.kt:280`），而 schema 数据其实已经存在（`.../data/model/AgentToolDefinition.kt:111-133`）。

3. **压缩的批次边界与摘要安全检查**。Eta 只在完整工具交换之间切分，保护最新用户轮，并要求摘要"非空、不超长、不含工具调用、确实缩小了上下文"，任一不满足就保留原文（`agent/model/AgentContextCompactor.kt:150-174`、`:124-130`、`:89-91`）。Minis 已经有锚点式压缩、分段重试和循环内压缩上限，但缺少"必须缩小"与摘要合法性校验，且切到工具批次中间时靠事后修复（合成错误结果）而不是事前拒绝。

4. **敏感工具不进入持久会话**。Eta 用一份分类表决定哪些工具的原始参数/结果不得进入持久 transcript，命中即用占位文本替换；`mcp_` 前缀一律视为敏感（`agent/model/AgentSensitiveToolPolicy.kt:5-46`、`agent/model/AgentConversationCodec.kt:193-227`）。Minis 把工具参数与工具结果原文写进 Room（`.../ui/chat/ChatViewModel.kt:9932-9985`、`:9987-10010`），没有按工具分类的落盘脱敏。

5. **长期记忆的窗口比例预算与索引**。Eta 按模型窗口的固定比例给出"核心记忆"字符预算并夹在上下界之间，同时只注入标题索引，截断时显式标记（`agent/memory/AgentMemoryContext.kt:27-84`）。Minis 直接注入整份 `GLOBAL.md` 和最近日记前 200 行，没有窗口比例预算、没有截断标记、没有 revision（`.../data/repository/MemoryRepository.kt:191-250`）。

6. **模型失败分类与重试纪律**。Eta 把"可重试 / 不可重试 / 上下文溢出"分开，规定同一回合内已启动托管工具后不得重试，失败尝试产生的思考内容必须丢弃，溢出走强制压缩并限次（`agent/model/AgentModelFailure.kt`、`agent/model/AgentModelRetry.kt:41-64`）。Minis 主循环有自己的重试与退避，但没有"已产生副作用就不重试""丢弃失败轮思考"这类显式规则。

其余值得注意但优先级较低的点见第 2 节表格与第 3 节次级规格。

## 2. 逐项对照表

### 2.1 Agent 循环与轮次控制

| 能力 | Eta 做法（概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|
| 循环终止策略 | 循环本身不设本地轮次上限，只由模型自然结束、用户取消或错误终止（`agent/model/AgentLoop.kt:84-231`，注释在 `:10-16`） | `MAX_AGENT_TURNS = 200` 作为单轮硬上限，触顶后是可恢复的收尾而不是静默卡死（`.../ui/chat/ChatViewModel.kt:372`、`:9325-9360`）；另有工具循环检测器（`.../agent/ToolLoopDetector.kt:63-90`） | 不该移植 | 保留 Minis 的双层上限；Eta 的无上限循环在移动端会把成本与失控风险交给模型 |
| 循环工作线程可暂停/可取消 | 统一的运行控制器提供取消、暂停阻塞点、可取消的退避等待、资源登记簿（`agent/runtime/AgentRunController.kt:27-141`） | 由 `SessionActivityTracker` 的 `onStop` 回调与 `cancelStream()` 承担取消与活跃语义（`.../ui/chat/ChatViewModel.kt:11211`、`:11355`） | 已相当 | 不新增控制器抽象；若引入第 3 节 C1/C6，可复用现有取消语义 |
| 补充指令（steering）投递 | 逐条消费、回合结束时原子"封口"，保证循环返回后到达的补充不会被误报已接收（`agent/runtime/AgentRunController.kt:43-69`） | 排队提示词在循环结束后或工具关闭时注入，可撤回（`.../ui/chat/ChatViewModel.kt:6069-6136`、`:6104-6117`） | 已相当 | 只在 C1 的检查点里记录"补充已消费"计数，便于恢复时不重放 |

### 2.2 工具调用合同

| 能力 | Eta 做法（概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|
| 入参 schema 校验 | 执行前按本轮公布的 schema 校验类型/必填/枚举/常量/数值边界/数组约束/`additionalProperties`/组合关键字/本地引用，深度上限 256（`agent/model/AgentToolCallValidator.kt:23-35`、`:44`、`:345-347`）；未知工具名直接拒绝（`:24-25`） | 只校验"工具存在 + 必填字段存在且非 null/空串"，并允许 `file_edit.new_string` 空串；类型/枚举/范围显式交给各工具（`.../ui/chat/ChatViewModel.kt:280-355`、注释在 `:300-310`） | 更弱 | 见 C2：新增共享校验器，复用已有 `AgentToolDefinition.parameters` |
| 非法参数的处理 | 拒绝执行并回填错误结果，保持 tool_use/tool_result 配对（`agent/model/AgentLoop.kt:259-265`、`:297-323`） | 流式 JSON 修复（截断补全、类型强转、字段名近似匹配）在 preflight 之前（`.../provider/ToolJsonRepair.kt:34-91`）；修复不了才返回缺参错误 | 已相当 | 修复逻辑保留；新增校验必须发生在修复之后，且不得为通过校验而"凭空补值" |
| 工具批次中断语义 | 为中断批次里没有结果的调用补一条"执行状态未知、不要自动重放"的结果（`agent/model/AgentToolBatchRecovery.kt:8-30`） | 按持久化的执行意图注入 `[tool outcome unknown]` 提示（`.../ui/chat/ChatViewModel.kt:2558-2578`，存储见 `.../tools/ToolCheckpointStore.kt:23-145`）；发送前修复孤儿 tool 部件（`.../ui/chat/ChatViewModel.kt:2800-2885`） | 已相当 | 只做一处对齐：孤儿修复合成的占位结果应表达"状态未知"，不要表述成普通失败 |
| 工具循环与重复调用 | 无独立循环检测器；依赖模型自然结束 | 四类循环检测（未知工具重复、同参同结果、轮询无进展、同参重复）+ 全局熔断（`.../agent/ToolLoopDetector.kt:63-90`、`:98-200`） | 不该移植 | 保留 Minis 实现 |
| 工具调用超时 | 由 Catalog/Requirements 侧约束，未在循环内统一包装 | 每个工具声明 `timeoutMs`，执行器包装协作式截止并返回结构化超时（`.../data/model/AgentToolDefinition.kt:17-25`、`.../ui/chat/ChatViewModel.kt:9394-9412`） | 不该移植 | 保留 Minis 实现 |

### 2.3 上下文预算与压缩

| 能力 | Eta 做法（概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|
| 预算估算 | 固定密度估算 + 每条消息结构开销，图片按固定值计价；用同一模型的真实 input usage 校准估算比例并夹在 1.0–8.0（`agent/model/AgentContextBudget.kt:9-31`、`:33-60`） | 固定 4 字符/token 估算（`.../tools/TokenMeter.kt:27-84`），阈值 80%/95% 只做提示（`.../tools/ContextPressure.kt:24-88`）；真正的门槛用上一轮真实 usage（`.../ui/chat/ChatViewModel.kt:901-902`、`.../data/ContextPolicy.kt:28-60`） | 更弱（仅提示层） | 见 C6 次级项：把真实 usage 回灌成校准系数，让提示百分比可信 |
| 压缩触发 | 估算超过窗口 85% 触发；模型报溢出不重试而是强制压缩，最多 3 次（`agent/model/AgentContextBudget.kt:22-31`、`agent/model/AgentLoop.kt:129-139`） | 发送前按真实 usage + 策略决定"压缩后发送/询问/放行"，循环内每轮复查且单轮最多压缩 3 次、压缩后作废旧读数（`.../ui/chat/ChatViewModel.kt:3356-3412`、`:3486`、`:3501-3565`） | 已相当 | 保留；补 Eta 的"溢出即压缩"分支作为兜底 |
| 压缩切割点 | 只在完整工具交换之间切；保护最新用户轮及其后未完批次（`agent/model/AgentContextCompactor.kt:23-41`、`:150-174`） | 按 DB 消息锚点切割，切坏了由孤儿修复兜底（`.../ui/chat/ChatViewModel.kt:2800-2885`） | 更弱 | 见 C3：把"切到批次中间"从"事后修复"改为"事前拒绝" |
| 摘要合法性 | 要求正常结束、非空、不超长、不含工具调用；并拒绝"压缩后没有变小"（`agent/model/AgentContextCompactor.kt:124-130`、`:89-91`、`:55-56`） | 空摘要会失败并保留旧状态（`.../ui/chat/ChatViewModel.kt:2101-2115`）；有超时预算与分段重试（`:155-175`、`:3290-3300`）；未发现"必须缩小"校验 | 更弱 | 见 C3 |
| 压缩提交顺序 | 先提交快照，再原子替换运行中的上下文；快照失败则回退整体（`agent/model/AgentContextSession.kt:57-110`） | 先落 marker 与摘要，再由发送路径重建历史（`.../ui/chat/ChatViewModel.kt:2177-2195`、`:2548-2560`） | 已相当 | 不需要照搬；C1 会引入"上下文快照"概念时再统一 |
| 大工具结果治理 | 摘要输入按完整批次分组，单批超限直接拒绝并提示换更大窗口（`agent/model/AgentContextCompactor.kt:59-71`） | 有溢写策略与剪枝器，但剪枝器尚未接入压缩调用点（`.../tools/internal/SpillPolicy.kt:18-86`、`.../tools/internal/ToolResultPruner.kt:12-40`） | 更弱 | 先把已有剪枝器接入压缩路径，再考虑 Eta 的"单批超限拒绝" |

### 2.4 运行检查点与崩溃恢复

| 能力 | Eta 做法（概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|
| 在途 run 落盘 | 一次运行同时保存 UI 事件、脱敏 transcript、模型上下文快照，三者在同一行记录里（`agent/runtime/AgentRunCheckpointStore.kt:18-131`） | 会话事件日志有界且持久化（`.../ui/chat/SessionEventHub.kt:20-35`、`:123-125`），工具执行意图单独存 JSONL（`.../tools/ToolCheckpointStore.kt:23-145`）；没有上下文快照 | 缺失 | 见 C1 |
| 高频增量写入 | 文本增量合并后落盘（512 字符或 250 ms 任一先到），工具参数增量不进恢复日志（`agent/runtime/AgentRunCheckpointRecorder.kt:16-46`、`:76-78`、`agent/runtime/AgentEventRecoveryProjection.kt:4-9`） | 流式增量通过事件日志批量落库，有每会话条数上限（`.../ui/chat/SessionEventHub.kt:242`、`:685`） | 已相当 | 复用现有批量落库节奏，只补"上下文快照" |
| 恢复决策 | 用 checkpoint + 终态 outbox + Runtime 活跃状态三者对账，区分 已完成/重新接入/已中断；只有活跃与终态都已知才判定中断（`agent/runtime/AgentRunRecoveryCoordinator.kt:19-54`） | 冷启动按历史尾部形状推断可恢复（`.../agent/InterruptedTailDetector.kt:14-78`，调用点 `.../ui/chat/ChatViewModel.kt:4360-4378`）；无"活跃 run 是否仍在跑"的判定 | 更弱 | 见 C1：Minis 单进程模型下，用"运行期心跳/结束标记"替代跨进程状态查询 |
| 终态投递 | 终态先落盘、入口确认后 ACK；已 ACK 的 run 不允许再写回；未确认结果不按年龄或数量淘汰（`agent/runtime/AgentRuntimeResultStore.kt:12-35`、`:62-88`） | 助手回合通过 `PendingAssistantTurn` 做一次性提交（`.../ui/chat/PendingAssistantTurn.kt:9-27`）；消息本身在 Room，不存在"未确认结果队列" | 不该移植 | 单进程直连 Room 不需要 outbox+ACK；只保留"终态一次提交"语义 |
| 停止回调所有权 | 服务销毁只封闭新提交；已接纳的停止回调在专用线程执行完，不随服务作用域取消（`agent/runtime/ExecutionStopQueue.kt:7-30`）；租约按 owner 归属，避免新实例取消后续任务（`agent/runtime/ExecutionLeaseRegistry.kt:4-42`） | 取消走 `SessionActivityTracker` 的 `onStop` 与 `cancelStream()`（`.../ui/chat/ChatViewModel.kt:11211`、`:11355`） | 更弱 | 仅借鉴"停止回调不能在取消作用域里被吞掉"这一点；不引入跨进程租约 |

### 2.5 长期记忆与提示词注入

| 能力 | Eta 做法（概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|
| 注入预算 | 按模型窗口的 1/16 计算核心记忆预算，夹在 4000–32000 字符之间，并给出截断标记与内容指纹（`agent/memory/AgentMemoryContext.kt:38-63`） | 整份 `GLOBAL.md` 全文注入；最近日记取最多 3 个文件、每个前 200 行（`.../data/repository/MemoryRepository.kt:191-250`、`:25-31`） | 更弱 | 见 C5 |
| 索引与检索 | 额外注入标题索引（上限 4000 字符），正文与索引分离（`agent/memory/AgentMemoryContext.kt:44-47`、`:76-82`） | 由 `memory_get` 关键词检索承担，注入侧没有索引（`.../tools/MemoryTools.kt:41-72`、`.../data/repository/MemoryRepository.kt:69-190`） | 更弱 | 见 C5 |
| 开关语义 | 记忆开关同时决定上下文注入与记忆工具可见性（`agent/runtime/AgentRuntimeRunExecutor.kt:99-134`、`:228`） | 注入与工具同一开关，关闭时在提示词里显式说明（`.../ui/chat/ChatViewModel.kt:10036-10075`、`:10194-10245`） | 已相当 | 保留 |
| 敏感内容 | 记忆读写工具列入敏感工具，原文不进入持久 transcript（`agent/model/AgentSensitiveToolPolicy.kt:42-45`） | 提示词要求不要把密码/密钥写进记忆（`.../ui/chat/ChatViewModel.kt:10065-10068`），但没有落盘脱敏 | 更弱 | 与 C4 合并处理 |

### 2.6 Provider 边界与鲁棒性

| 能力 | Eta 做法（概述） | Minis 现状（文件:行号） | 差距判定 | 建议做法 |
|---|---|---|---|---|
| 失败分类 | 统一把 HTTP/流/传输失败归类为 可重试/不可重试/上下文溢出，并区分配额与计费类永久失败（`agent/model/AgentModelFailure.kt:17-99`） | 主循环有自动重试退避（`.../ui/chat/ChatViewModel.kt:418`），非主链路的统一重试策略按错误类判定（`.../provider/LLMRetryPolicy.kt:25-68`）；上下文超限靠错误文案子串匹配（`.../ui/chat/ChatViewModel.kt:3297-3345`） | 更弱 | 见 C6 |
| 重试纪律 | 已启动托管工具后不重试；重试等待可取消；重试前丢弃失败尝试的思考内容（`agent/model/AgentModelRetry.kt:41-64`） | 未发现对应规则；流式过程中的思考内容与最终结果一起落库 | 缺失 | 见 C6 |
| SSE 分帧 | 只做分帧，协议解释在各自 Provider，收到终态即返回而不等连接关闭（`agent/model/ProviderSseReader.kt:9-41`） | 各 Provider 自行处理流（`.../provider/openai/`、`.../provider/anthropic/`） | 已相当 | 不重构 |
| 自定义请求头/请求体 | 用户自定义头会过滤协议保留字段，校验名称/值并做大小写去重与日志脱敏（`agent/model/CustomHeaderFilter.kt:15-93`）；自定义请求体递归合并，用户值优先（`agent/model/RequestBodyMerge.kt:24-48`） | Guest 侧模型调用链支持 `extra_body` / `extra_headers` 信封（`.../runtime/guest/ModelUseOffloadHandler.kt:322-340`、`:558-591`）；Provider 传输策略强制 HTTPS/允许清单/同源校验（`.../provider/ProviderTransportPolicy.kt:29-180`）；未发现等价的头过滤与请求体合并工具 | 更弱 | 只把"禁止协议保留字段 + 名称/值校验 + 大小写去重 + 日志脱敏"的原则应用到现有 `extra_headers` 路径，不新增用户面 |
| 传输层日志脱敏 | `CustomHeaderFilter.redactForLog`（`agent/model/CustomHeaderFilter.kt:84-93`） | `EnvVarRedactor` 只对 shell 输出里的环境变量值脱敏（`.../data/EnvVarRedactor.kt:21-88`） | 更弱 | 记录到"可借鉴但低优先级" |

### 2.7 契约边界（明确不对照的部分）

Eta 的 Root 设备控制器、无障碍服务驱动、Xposed 钩子、浏览器会话、技能商店/安装器、角色扮演与角色记忆等属于其它领域或与本仓库合同冲突，本文不展开；相关结论见第 5 节。

## 3. 候选能力的可移植规格

以下规格都以"Minis 自有风格重新实现"为前提：只借用行为语义、常量与失败路径，不搬运 Eta 代码。

### C1 运行检查点与恢复决策（对应 2.4）

**输入 / 输出**

- 输入：`sessionId`、`runId`、`operation`（对话/压缩/重写）、入口载荷、当前回合的模型上下文切片（含压缩标记）、脱敏 transcript、UI 事件。
- 输出：一次运行对应一条 checkpoint 记录（状态 + 事件序列 + transcript + 上下文快照）；恢复时输出 `Completed` / `Reattach` / `Interrupted` 三种动作之一，或"状态未知，保持待定"。

**状态机与不变量**

1. 先建 checkpoint 行，再追加事件；没有 checkpoint 行时事件必须被丢弃而不是静默堆积。
2. 文本增量合并写入（阈值可沿用 512 字符 / 250 ms）；工具参数增量不进入恢复日志。
3. 上下文快照与 transcript 独立交付：快照用于"继续对话"，transcript 用于"重建 UI 轨迹"，两者都不能由对方推导。
4. 终态只提交一次；已确认的 run 不允许再回写。
5. 运行中、已中断、已完成三种状态互斥；在无法判定运行是否仍活跃时，一律不判定"已中断"。
6. 恢复出的历史不得包含敏感工具的原文（与 C4 共用分类表）。

**失败与拒绝路径（fail-closed 否定用例）**

- 缺少 `runId` 或入口来源不合法 → 不创建 checkpoint，运行照常但不提供恢复（返回"不可恢复"）。
- checkpoint 解码失败或版本不匹配 → 判定为"已中断"并把轨迹恢复成可读形态，绝不按原样重放工具调用。
- 同一 `runId` 在已确认之后再次提交终态 → 拒绝写入（幂等保护）。
- 活跃状态未知 → 不产生"已中断"通知，避免把仍在运行的回合误报为中断。
- 恢复路径中任何一步失败 → 保留 checkpoint 供下次重试，不清库。

**边界上限（建议值，需实现时复核）**

- 单 run 事件条数：2048（与现有每会话事件上限一致，`.../ui/chat/SessionEventHub.kt:123`）。
- 单条事件 JSON：建议 64 KiB，超出按摘要截断并记录类型。
- transcript 落盘：条数 + 字节双上限（建议 512 条 / 2 MiB），超限保留尾部并标注截断。
- 上下文快照：建议不超过模型窗口对应字符数的 60%，与摘要上限同源。
- checkpoint 清理：终态确认后删除；无终态的孤儿记录保留上限（建议 32 条，超出按时间淘汰最旧且先记日志）。

**落地文件位置**

- 新增：`src/android/app/src/main/java/com/openminis/app/agent/RunCheckpointStore.kt`、`RunCheckpointRecorder.kt`、`RunRecoveryCoordinator.kt`。
- Room：`src/android/app/src/main/java/com/openminis/app/data/db/` 新增 checkpoint 实体与 DAO（含迁移），与现有会话事件表分离。
- 写入点：`.../ui/chat/ChatViewModel.kt:7507`（运行开始）、`:9932-9985`（助手回合落库）、`:9987-10010`（工具结果落库）、`:9367`（工具意图，已有）。
- 恢复入口：`.../ui/chat/ChatViewModel.kt:4340-4378`（会话加载）、`:11620`（Resume）。

**单元测试清单**

- `.../agent/RunCheckpointRecorderTest.kt`：增量合并阈值、工具参数增量被过滤、无 checkpoint 行时事件被丢弃、终态只提交一次。
- `.../agent/RunRecoveryCoordinatorTest.kt`：活跃状态未知时不判中断；已完成/待重新接入/已中断三类划分；已确认 run 不再入队；解码失败走中断路径且不重放工具。
- `.../data/db/RunCheckpointDaoTest.kt`：上限淘汰、按 run 清理、并发写入幂等。
- `.../ui/chat/ResumeAfterProcessDeathTest.kt`：模拟"助手回合已落库但循环未续跑"与"工具已执行但结果未落库"两种尾部，验证 Resume 行为与提示文本。

### C2 工具调用入参校验（对应 2.2）

**输入 / 输出**

- 输入：本轮已公布的 `List<AgentToolDefinition>`、模型返回的工具名与参数 JSON。
- 输出：`null`（通过）或结构化拒绝（拒绝码 + 面向用户/模型的简短原因），拒绝时工具体绝不执行。

**状态机与不变量**

1. 校验只针对"本轮已向模型公布的工具集合"；不在集合内的名字一律拒绝。
2. 校验发生在 JSON 修复之后、权限与审批之前；校验器本身不做权限判断。
3. 无论通过与否，都必须产生一条与 tool_use 配对的 tool_result，避免破坏 provider 的配对要求。
4. 校验器是纯函数，不读磁盘、不访问网络、不修改入参对象以外的状态。

**失败与拒绝路径（fail-closed 否定用例）**

- 参数不是合法 JSON 对象 → 拒绝（`INVALID_TOOL_ARGUMENTS`）。
- 未知工具名 → 拒绝，并提示可用工具范围。
- 必填字段缺失或为 `null` → 拒绝；`file_edit.new_string` 的空串白名单行为必须保留（`.../ui/chat/ChatViewModel.kt:249-255`）。
- 类型不符、枚举越界、数值越界、`additionalProperties` 禁止的额外字段 → 拒绝。
- 递归深度超过上限 → 拒绝，不递归展开（防止自引用 schema 打爆栈）。
- schema 自身损坏（例如 `enum` 不是数组）→ 视为校验不可用，拒绝该次调用并记录日志，而不是默认放行。

**边界上限**

- 递归深度：256（沿用 Eta 的常量数量级）。
- 单次参数 JSON：建议 256 KiB，超出直接拒绝。
- 拒绝消息：≤512 字符，且不得回显完整用户输入。
- 校验耗时：纯 CPU 路径，建议单次 ≤50 ms；超出按拒绝处理（说明 schema 过于复杂）。

**落地文件位置**

- 新增：`src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolCallValidator.kt`（纯函数）。
- 接入点：`.../ui/chat/ChatViewModel.kt:280-355`（preflight）与 `:9419`（`dispatchTool` 之前）。
- 复用：`.../data/model/AgentToolDefinition.kt:11-25`、`:111-133`。
- 与修复的先后：`.../provider/ToolJsonRepair.kt:34-91` 保持在前。

**单元测试清单**

- `.../tools/runtime/ToolCallValidatorTest.kt`：类型、枚举、嵌套对象/数组、数值边界、必填缺失、`null`、非法 JSON、未知工具、深度上限、损坏 schema。
- `.../ui/chat/PreflightEmptyStringAllowedTest.kt`：`file_edit.new_string` 空串放行；其他工具空串仍拒绝。
- `.../ui/chat/ToolCallRejectionPairingTest.kt`：被拒调用仍生成配对 tool_result，且不触发工具执行与审批。

### C3 压缩的批次边界与摘要校验（对应 2.3）

**输入 / 输出**

- 输入：待压缩的历史区间、锚点消息、模型窗口与保留预算。
- 输出：摘要 + 新锚点；或结构化失败且历史与 marker 完全不变。

**状态机与不变量**

1. 切割点必须落在完整工具交换之后；不允许把 tool_use 与其 tool_result 分开。
2. 最新用户轮不得被压进摘要；其后尚未完成的批次也不得被压缩。
3. 摘要必须非空、不超过字符上限、不含工具调用、以正常结束状态返回。
4. 压缩结果必须严格小于压缩前（按同一估算口径比较），否则视为无效压缩。
5. 只有全部校验通过后才替换运行中的历史与 marker；任何一步失败保持原状，并向用户给出可操作提示。

**失败与拒绝路径（fail-closed 否定用例）**

- 找不到安全切割点 → 拒绝压缩并提示"没有可安全压缩的完整批次"。
- 单个完整批次本身就超出摘要容量 → 拒绝并提示缩短输入或换更大窗口，而不是切碎该批次。
- 摘要为空、含工具调用、超长 → 拒绝，保留原历史。
- 压缩后估算未变小 → 拒绝（`CONTEXT_NO_REDUCTION`）。
- 压缩期间会话被取消或用户切换会话 → 中止且不写 marker。

**边界上限**

- 摘要字符上限：`min(12_000, 0.6 × 窗口)` 且不小于 256 字符。
- 保留最近内容：条数下限 4 条，或按窗口 20% 的 token 预算；与既有 `COMPACT_KEEP_RECENT_TOKENS = 20_000` 和 100 条上限（`.../ui/chat/ChatViewModel.kt:410-413`）取更严格者。
- 压缩尝试：3 次（与循环内压缩上限 `.../ui/chat/ChatViewModel.kt:3486` 对齐）。
- 分段重试深度：沿用现有限制（<3 层）。

**落地文件位置**

- 新增：`src/android/app/src/main/java/com/openminis/app/agent/CompactionGuards.kt`（切割点判定、摘要校验、缩小校验；纯函数）。
- 接入点：`.../ui/chat/ChatViewModel.kt:1960-2300`（`compactAll`）、`:2800-2885`（孤儿修复改为最后一道防线而非主要机制）。

**单元测试清单**

- `.../agent/CompactionGuardsTest.kt`：批次中间不可切、最新用户轮不可压、无安全点、单批超限。
- `.../ui/chat/CompactionSummaryValidationTest.kt`：空摘要、含工具调用、超长、未缩小四种拒绝路径均保持历史不变。
- `.../ui/chat/CompactionMarkerConsistencyTest.kt`：失败路径不写 marker、不更新摘要状态；成功路径 marker 与有效历史一致。

### C4 敏感工具持久化脱敏（对应 2.5 / 2.2）

**输入 / 输出**

- 输入：工具名、参数 JSON、结果文本、目标存储（会话消息表）。
- 输出：写入存储的替代文本；模型在当轮上下文里仍看到真实结果。

**状态机与不变量**

1. 分类表是唯一真源；新增工具必须显式归类，未归类默认按现有行为写入。
2. 脱敏发生在写库之前，不依赖 UI 是否展示。
3. 从历史恢复出的内容不得包含被省略的原文。
4. 每轮上下文（发给模型的部分）与持久化内容可以不同，但不能反向漂移：模型看到占位符就意味着磁盘里也是占位符。

**失败与拒绝路径（fail-closed 否定用例）**

- 分类表命中但脱敏规则不可用 → 写入占位符而不是原文。
- `mcp_` 前缀工具一律按敏感处理（包括未知的 MCP 工具）。
- 工具名大小写或别名不匹配导致的歧义 → 按敏感处理的保守分支执行。
- 记忆写入工具的原文不得进入持久 transcript（与 2.5 一致）。

**边界上限**

- 占位符：固定短文本，长度与工具名无关。
- 参数留档上限：需要保留时只留前 N 字符（建议 256）用于"调用过什么"的可读性，不含值。
- 分类表：建议集中在一个对象里，条目数量级与现有工具数同阶。

**落地文件位置**

- 新增：`src/android/app/src/main/java/com/openminis/app/tools/ToolSensitivePolicy.kt`。
- 接入点：`.../ui/chat/ChatViewModel.kt:9932-9985`（助手回合里工具调用参数落库）、`:9987-10010`（工具结果落库）。
- 互补关系：`.../data/EnvVarRedactor.kt:21-88` 继续负责"env 值在进入模型前脱敏"，两者不合并。

**单元测试清单**

- `.../tools/ToolSensitivePolicyTest.kt`：前缀匹配、大小写、别名、未知 MCP 工具。
- `.../ui/chat/ToolPersistenceRedactionTest.kt`：敏感工具参数与结果落库为占位符；非敏感工具保持原文；恢复出的历史不含原文。

### C5 记忆注入预算与索引（对应 2.5）

**输入 / 输出**

- 输入：全局记忆内容、最近日记、模型窗口、内容指纹。
- 输出：注入片段（核心段落 + 标题索引 + 截断标记 + 指纹与字节数）。

**状态机与不变量**

1. 核心段预算 = `clamp(窗口 / 16, 4000, 32000)` 字符；窗口不可用时使用默认窗口。
2. 预算不足时必须显式标注"已截断"，模型不得把截断内容当作完整事实。
3. 索引只列标题，不复制正文；索引本身有独立上限。
4. 记忆不可读时不注入、不阻塞发送（沿用现有 best-effort 语义）。
5. 指纹变化才需要模型重新理解内容；同一指纹下注入内容应保持字节稳定，避免破坏提示词缓存前缀。

**失败与拒绝路径（fail-closed 否定用例）**

- 记忆目录不可用 → 不注入并记录日志，发送照常。
- 单个文件超过预算 → 截断并标记；不因为"内容太多"而整体丢弃。
- 找不到核心段落标题 → 只注入索引与最近日记，不把整份文件灌进提示词。
- 文件内容含疑似凭据 → 沿用现有提示词告知模型不要落盘敏感信息（`.../ui/chat/ChatViewModel.kt:10065-10068`）。

**边界上限**

- 核心段：窗口/16，夹在 4000–32000 字符。
- 标题索引：建议 ≤4000 字符。
- 最近日记：最多 3 个文件、每个前 200 行（沿用 `.../data/repository/MemoryRepository.kt:25-31`）。
- 检索输出：沿用 30 KiB 上限（`.../data/repository/MemoryRepository.kt:30`）。

**落地文件位置**

- 新增：`src/android/app/src/main/java/com/openminis/app/agent/MemoryContextBuilder.kt`（纯函数，便于单测）。
- 接入点：`.../data/repository/MemoryRepository.kt:191-250`、`.../ui/chat/ChatViewModel.kt:10194-10245`。

**单元测试清单**

- `.../agent/MemoryContextBuilderTest.kt`：窗口比例与上下界、截断标记、无核心段、空文件、超大文件、指纹稳定性。
- `.../data/repository/MemoryBudgetTest.kt`：注入片段不超过预算、最近日记文件数与行数上限。

### C6 模型失败分类与重试纪律（对应 2.6）

**输入 / 输出**

- 输入：provider 抛出的失败（HTTP 状态、流内错误、传输异常）、是否已产生副作用（托管工具已启动、部分输出已提交）、当前重试轮次。
- 输出：分类结果（错误码、是否可重试、是否允许恢复）+ 重试计划（次数、退避）；或直接给出终态失败。

**状态机与不变量**

1. 只有"请求未产生副作用"的失败可重试；同一回合内已经启动托管工具或已提交部分输出后，失败一律视为终态。
2. 重试前丢弃失败尝试产生的思考内容，避免把失败路径的推理混进最终答案。
3. 重试等待必须可被用户取消打断，取消后不再发起新请求。
4. 配额/计费/认证/参数类失败不重试，直接给出可操作提示。
5. 上下文溢出走"压缩后重试"专用路径，限次；耗尽后停止并保留已完成的工具结果。

**失败与拒绝路径（fail-closed 否定用例）**

- 认证失败、参数非法、配额耗尽 → 不重试。
- 已启动托管工具后失败 → 不重试，且不重复提交同一回合。
- 重试次数耗尽 → 停止，说明已重试次数并保留既有工具结果。
- 溢出自适应次数耗尽 → 停止并提示用户开新会话或压缩，不静默降级到不完整请求。
- 取消信号与重试定时器竞争 → 以取消为准。

**边界上限**

- 重试次数：3 次；退避基数 2 s 指数增长（Eta 常量），或与现有 `AUTO_RETRY_DELAYS_SEC = 1/2/4`（`.../ui/chat/ChatViewModel.kt:418`）对齐后取其一，不要两套并存。
- 溢出自适应：3 次。
- 单次退避上限：建议 30 s。

**落地文件位置**

- 新增：`src/android/app/src/main/java/com/openminis/app/provider/LLMFailureClassifier.kt`（纯函数分类器）。
- 接入点：`.../provider/LLMRetryPolicy.kt:25-68`、`.../ui/chat/ChatViewModel.kt:7507`（主循环重试分支）、`:3297-3345`（超限文案匹配改为结构化分类）。

**单元测试清单**

- `.../provider/LLMFailureClassifierTest.kt`：HTTP 状态集合、流错误码、永久失败、上下文溢出、传输异常。
- `.../ui/chat/AgentRetryDisciplineTest.kt`：已产生副作用不重试、失败轮思考被丢弃、取消优先于退避、溢出走压缩路径且限次。

### 次级候选（建议但不必优先）

- **估算校准**：把上一轮真实 input usage 与本地估算的比值作为校准系数（夹在 1.0–8.0），用于发送前提示百分比；图片按固定 token 计价。落地：`.../tools/TokenMeter.kt:27-84`、`.../tools/ContextPressure.kt:24-88`。测试：估值在校准后单调、异常 usage 不污染系数。
- **上下文续写构造**：把"已完成回合 + 补充内容"构造成下一次运行的完整历史，而不是让调用方自行拼接。落地：新增 `.../agent/ContinuationBuilder.kt`。测试：历史顺序、补充编号、空补充拒绝。
- **呈现层摘要与显示脱敏**：工具调用/结果在 UI 上只显示有界预览，并对疑似凭据做显示层脱敏；终端输出预览按行数与字符双上限。落地：`.../ui/chat/ChatToolFormatting.kt`、`.../ui/chat/ChatToolDetailUI.kt`。测试：超长输出、含 token 的命令、空结果。
- **停止回调所有权**：进程内把"已接纳的停止回调"放到独立执行器上跑完，避免随作用域取消被吞掉。落地：`.../ui/chat/ChatViewModel.kt:11211`、`:11355` 附近。测试：取消期间回调仍执行且只执行一次。

## 4. 必须真机验证的点

以下事项在 CI/宿主测试之外，必须在真实设备上验证后才可声称通过；本文不声称任何一项已验证。

1. **进程被杀后的恢复**：系统低内存回收、用户强制停止、崩溃三种路径下重新打开会话，检查在途 run 的事件轨迹、部分文本、上下文快照是否一致，以及是否被误判为"已中断"或"已完成"。
2. **工具执行中被杀**：写文件、发消息、外部网络请求等有副作用工具在"意图已记录、结果未落库"窗口被杀，验证下一次发送是否注入"状态未知、不要自动重放"的提示，并确认模型不会盲目重试。
3. **压缩后的长任务连续性**：真实长会话触发压缩后继续多轮工具调用，确认摘要与最近轮次拼接顺序正确、锚点与有效历史一致、没有孤儿 tool 调用。
4. **敏感工具落库脱敏**：用真实 MCP 服务与真实设备数据（联系人/剪贴板/通知等）验证落盘内容确实为占位符，且当轮模型仍能完成操作。
5. **记忆注入预算**：用真实的长全局记忆与多天日记，验证首字节延迟、提示词前缀稳定性（缓存命中）与截断标记对模型行为的影响。
6. **OEM 后台策略**：小米 HyperOS 等设备上验证后台策略、前台服务与 ViewModel 生命周期对停止回调、检查点写入与恢复的影响。
7. **多会话并发**：多个会话同时运行时检查点与恢复不应串台；一个会话的恢复不得影响另一个会话的进行中回合。

宿主侧可先覆盖的部分：纯函数校验器、分类器、预算计算、切割点判定、恢复决策表，这些可以用 JVM 单测把失败路径全部覆盖；涉及 Room、进程死亡与 OEM 生命周期的部分必须真机。

## 5. 不建议移植的清单与理由

1. **Root / 无障碍 / 钩子设备控制链**。Eta 把 Root 命令执行、无障碍驱动与系统钩子作为模型可调用的设备工具；本仓库合同规定 Root 只执行 App 构造的受控基础设施动作，本地 Agent 的 Root 能力必须走结构化、local-only 的工具合同，不接受原始命令字符串。两者边界不同，不移植。
2. **独立的 Runtime 进程 + 自定义 IPC + 终态 outbox 全链路**。Eta 为了跨进程投递引入了消息码、Bundle 编解码、结果邮箱与确认机制；本仓库是单进程应用，直接以 Room 与 ViewModel 为真源。只移植第 3 节 C1 的检查点与恢复决策语义，不引入第二套服务协议。
3. **无本地轮次上限的循环**。Eta 明确把终止权交给模型；本仓库已有轮次上限与工具循环检测两层保护，去掉任何一层都会让失控成本不可预期。
4. **事件序列化沿用 Bundle 中间格式**。那是跨进程投递的产物；本仓库的会话事件日志已经是 JSON，直接在其上扩展即可，不引入与 Android IPC 细节耦合的编解码层。
5. **厂商语音入口适配**。Eta 的入口隔离逻辑围绕特定厂商语音助手的包名与关闭行为展开；本仓库入口不同，照搬包名与策略没有意义。
6. **额外用户态 Linux 发行版安装器**。Eta 提供的 Alpine/Debian/rootless 安装器与本仓库 Direct Ubuntu 24.04 的单一运行时合同冲突，不引入。
7. **技能商店、浏览器会话、角色扮演与角色记忆**。属于其它领域或与本仓库产品定位不符，本次不评估、不移植。
8. **工具 trace 的具体文案与排版**。只借鉴"有界预览 + 显示层脱敏 + 终端按行/字符双上限"的原则，具体文案跟随本仓库现有 UI 风格。

## 附：证据索引

- Eta（只读）：`app/src/main/kotlin/io/github/mangi/eta/agent/model/`、`.../agent/runtime/`、`.../agent/memory/`、`.../ui/app/AgentRunRecoveryCoordinator.kt`、`.../ui/app/AgentAppState.kt`。
- Minis：`src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt`、`.../agent/`、`.../tools/`、`.../provider/`、`.../data/repository/MemoryRepository.kt`、`.../data/model/AgentToolDefinition.kt`、`.../data/ContextPolicy.kt`。
