# 08 — Bot 协调合同

本文定义 Bot 协调第一版的持久边界和失败行为。它不把 OpenMausBot 的桌面进程拓扑、HTTP、MCP proxy 或 JSON 文件格式带入 Minis；Minis 继续只有一套 Agent Runtime、Tool Runtime 和 Root/runtime 边界。

本文的“第一版”是当前正在验证的实现阶段，不是最终产品目标的收缩。完整协作还需要负责人自动接续、复核与返工；加入这些行为时必须同时扩展根任务、停止语义、回执消费和预算合同。

## 第一版范围

第一版只交付一条可以独立验收的链路：

```text
持久 Bot 身份
    ↓
Direct Chat
    ↓
Bot A delegate_bot(B)
    ↓
Room DB 中的持久任务
    ↓
B 的独立 session workspace 执行
    ↓
终态回执投递回 A 的来源会话
```

Bot 不是新的 Agent 类型。Bot Turn 继续使用现有 Provider、ToolRegistry、Approval、Memory、ExecutionCoordinator、Ubuntu 和 `minisd`。

## 身份、Session 与并发

- 一个 Bot 可以有多个历史或任务 Session。
- 一个 Bot 同时最多允许一个 in-flight Turn。Direct Chat、Headless、定时任务、重试/恢复和 Delegation 争用同一把 Bot 锁。
- Delegation 使用独立任务 Session，不能读取或污染 Bot 的 Direct Chat 历史；任务所需资料必须显式放入 prompt、产物引用或 `/shared`。
- 每个 Turn 使用自己的 session workspace；不得用全局 `/workspace` 绕过现有隔离。
- Bot 默认模型可以被 Session 绑定覆盖；用户在当前 Session 中途选择的模型优先于 Bot 默认值。
- 第一版不持久化 Section；所有用户创建的 Bot 处于同一个隐含的 `default` Section，因此彼此默认可达。Section allow-list 留待后续版本。
- 用户创建的 Bot 总数硬上限为 12；模型工具不能创建 Bot。Bot 的 `enabled=false` 时不能出现在 roster，也不能作为派工目标。

## 成员入口与对话连续性

- 团队是聊天侧栏和大屏会话列表的一级入口。资料、编辑和进展沿用现有页面、模型弹层与外层导航，不增加另一套底栏或导航框架。
- 大屏在左侧切换普通会话与团队名单，选择成员后在右侧继续对话；资料与编辑不在列表区域另建返回栈。
- 点击成员默认继续最近的 Direct Chat；仅显式选择“新话题”才另建对话。Subagent 和委派执行 Session 不参与“最近直聊”的选择。
- 新对话一次性保存 Bot 身份和模型绑定。显式绑定失效时提示重新选择；不静默使用其他模型。跟随应用默认时复用现有默认组、最近模型与文本模型解析规则。
- 聊天顶部显示当前成员姓名、职责或运行状态，并提供资料入口。普通聊天不显示 Bot 身份。删除成员保留历史对话并解除身份关联。
- 职责编辑保留段落、换行与列表缩进。停用成员可查看历史，但不能开始新的工作。
- 协作进展展示持久任务的真实状态、请求、结果和相关会话入口。“执行结束”仅代表本次执行终止，不能代替产物复核或根任务验收。

## Delegation 状态机

权威状态只存在 Room DB；内存队列和 `session_events` 只能作为运行时投影或唤醒提示。

```text
QUEUED → WAITING_TARGET → RUNNING → COMPLETED
                         ├────────→ FAILED
                         ├────────→ DENIED
                         ├────────→ CANCELLED
                         └────────→ BUSY_GAVE_UP
```

每条任务必须有稳定的 `id`、`sourceBotId`、`sourceSessionId`、`sourceRunId`、`sourceToolId`、`sourceTurnSettled`、`targetBotId`、`targetSessionId`、`depth`、`attempts` 和终态字段。领取操作必须在事务内完成，重复扫描不得启动同一任务两次。

默认规则：

- `list_bots` 只返回清洗后的持久 Bot 名单；`delegate_bot` 只写入 `QUEUED` 并立即返回 `taskId`；来源 Turn 成功 settle 后才允许 Dispatcher 领取。
- 来源 Turn 失败或被用户取消时，属于该 Turn 的未启动任务转为 `CANCELLED` 或 `DROPPED`，不得在下次启动时复活。
- 目标忙表示该 Bot 已有 in-flight Turn。任务进入 `WAITING_TARGET`，最多重试 3 个独立 busy period；超过后为 `BUSY_GAVE_UP`。
- 同一 busy period 内等待现有 Turn 释放 Bot 锁，不按定时轮询次数消耗额度；启动成功不计为忙重试。领取 `RUNNING` 时同时写入目标 Session ID，恢复时仍能打开实际执行入口。
- 单个来源 Turn 最多排队 4 条 Delegation。深度上限为 1；超深、不可达、禁用目标、重复领取、同一源工具调用重放和畸形参数全部 fail-closed。
- `RUNNING` 在进程死亡后不能自动重入生成。恢复时转为 `FAILED`，并保留 `outcomeUnknown = true`；若工具可能产生副作用，必须沿用 ToolCheckpoint 的“效果未知，先核实再重试”语义。

## 结果投递

完成、失败、拒绝和取消都必须写持久回执，并将一张可去重的结果卡投递到来源 Session。结果卡至少包含 `taskId`、目标 Bot、终态、摘要和产物引用；同一 `taskId` 重建后不得重复投递。 `check_delegation` 只能查询当前来源 Session 自己创建的任务。

- 后台 prompt 等待该请求独立的执行句柄及清理完成，不以 `isStreaming=false` 推断成功。未接受、取消、模型错误、断流、上下文耗尽和回合上限分别返回明确结果，不能沿用上一条回复冒充本次产物。
- 持久回执以来源 Session 与任务 ID 派生的稳定消息 ID 在事务内去重，普通回复中引用任务编号不会冒充回执。回执同时通知现有 ChatViewModel 与 `session_events`，当前聊天直接可见；模型历史在下一处安全回合边界合入，不由异步派工线程直接改写正在使用的历史。
- 来源失败或取消的持久收尾在 `NonCancellable` 中执行。进程启动时将未通过来源成功屏障的遗留队列转为取消并投递回执，不永久停留在排队状态。

第一版不自动唤醒来源 Bot 生成新 Turn。用户可以在来源会话中查看回执并要求汇总；自动唤醒若以后加入，必须另行定义忙状态、取消、预算和去重。

## 根任务与负责人收件箱基础层

当前 Bot 分支已经为后续的负责人接续预留了持久边界，但没有把自动接续误报成已完成：

- `bot_tasks` 保存用户目标的根任务、来源会话/回合、负责人、验收条件、阶段、修订号、停止代数和预算计数。委派执行结束只会把根任务推进到 `REVIEWING`，不会直接标记为 `COMPLETED`。
- `bot_delegations.root_task_id` 将一次执行绑定到根任务；旧数据库中的历史委派允许为 `NULL`，因此迁移不会改写历史语义。
- `bot_inbox_events` 以稳定 `dedupe_key` 去重，记录终态回执、负责人、租约和消费状态。终态回执写入收件箱后仍由显式恢复/唤醒逻辑消费；当前版本不在后台无条件拉起新的 Bot Turn。
- 首次 `delegate_bot` 使用来源会话与来源回合作为幂等锚点创建根任务；同一回合的重放复用根任务，不重复建立目标。

这层只提供 B1 数据合同和恢复所需的租约状态。负责人合并事件、单次唤醒、同一目标会话返工及预算耗尽分别属于后续闭环，不能因为表已存在就宣称已经实现。

## 工具与权限

Bot 协调工具通过现有 ToolExecutor/ToolRegistry Handler 进入 `BotCoordinator`，不走 localhost HTTP 或额外 MCP Server。工具列表过滤只是模型可见性控制，不是授权本身；Handler 必须从可信执行上下文重新校验来源身份、通信深度、目标可达性、来源 Session 所有权、额度和 taskId 查询权限。

现有 `ToolPermissionManager` 的 `LOCAL_ONLY`、`MCP_ALLOWED`、`MCP_CONFIRM`、`MCP_DENIED` 是本地 Agent 与 MCP caller 维度，不等于 BotToolPolicy。第一版 Bot 共享系统权限；未来的 BotToolPolicy 只能收紧，不能放宽系统权限。

## 生命周期

Android 的前台服务策略当前是按真实 active Turn 运行并使用 `START_NOT_STICKY`。不能把“数据库有 QUEUED”当作系统会自动恢复服务的保证。第一版只允许在 App 初始化后的明确恢复入口扫描 `sourceTurnSettled = 1` 的 `QUEUED`/`WAITING_TARGET`；来源 Turn 未成功结束的任务保持闸门关闭，不会因重启复活。无人值守后台拉起 Worker 需要单独的产品和设备验收。

## 暂不实现

以下能力保留为长期方向，不属于第一版验收：`ask_bot`、`wait_delegation`、模型 `create_bot`、Chief、Section allow-list、Peer Approval、Room 多发言者、`speaker_bot_id`、Team Goal、13 轮协调和 Bot/Team 独立 Memory。

## 必须保持的非目标

- 不把 BotCoordinator、Delegation 状态机或 Team Goal 塞进 `ChatViewModel`。
- 不把 Subagent、Job、Ralph 改名当成 Delegation。
- 不为每个 Bot 创建独立 Runtime、Provider、ToolRegistry、Ubuntu 或 minisd。
- 不用 Prompt 劝模型遵守深度、可达性或权限；这些规则必须在执行边界拒绝。
- 普通 Chat 的 `bot_id` 为空时，现有 Provider、Tool、Memory、UI、Ubuntu 和 Session 语义保持不变。
