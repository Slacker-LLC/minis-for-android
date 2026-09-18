# 08 — Bot 协调合同

本文定义 Bot 协调第一版的持久边界和失败行为。Bot 不引入第二套 Agent Runtime、Tool Runtime 或 Root/runtime 边界。

## 第一版主链

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

Bot 不是新的 Agent 类型。Bot Turn 继续使用现有 Provider、ToolRegistry、Approval、Memory、ExecutionCoordinator 和 direct Ubuntu runtime。

## 身份、Session 与并发

- 一个 Bot 可以有多个历史或任务 Session。
- 一个 Bot 同时最多允许一个 in-flight Turn；Direct Chat、Headless、定时任务、重试/恢复和 Delegation 争用同一把 Bot 锁。
- Delegation 使用独立任务 Session，不能读取或污染 Bot 的 Direct Chat 历史；任务资料必须显式放入 prompt、产物引用或 `/shared`。
- 每个 Turn 使用自己的 session workspace，不得用全局 `/workspace` 绕过隔离。
- Bot 默认模型可被 Session 绑定覆盖；用户在当前 Session 的显式选择优先。
- 用户创建的 Bot 总数硬上限为 12；模型工具不能创建 Bot。`enabled=false` 的 Bot 不能作为派工目标。

## Delegation 状态机

权威状态只存在 Room DB；内存队列和 `session_events` 只能作为运行时投影或唤醒提示。

```text
QUEUED → WAITING_TARGET → RUNNING → COMPLETED
                         ├────────→ FAILED
                         ├────────→ DENIED
                         ├────────→ CANCELLED
                         └────────→ BUSY_GAVE_UP
```

每条任务必须有稳定的 `id`、来源/目标 Bot、来源/目标 Session、来源 run/tool、depth、attempts 和终态字段。领取操作必须在事务内完成，重复扫描不得启动同一任务两次。

- `list_bots` 只返回清洗后的持久 Bot 名单；`delegate_bot` 只写入 `QUEUED` 并立即返回 `taskId`。
- 来源 Turn 成功 settle 后 Dispatcher 才允许领取；来源失败/取消时未启动任务转为取消/丢弃，不得重启复活。
- 目标忙进入 `WAITING_TARGET`；独立 busy period 最多重试 3 次，超过后 `BUSY_GAVE_UP`。
- 单个来源 Turn 最多排队 4 条 Delegation；深度上限 1。超深、禁用目标、重复领取、畸形参数和越权查询全部 fail-closed。
- `RUNNING` 在进程死亡后不得自动重入生成；恢复应标记失败并保留 outcome unknown 语义。

## 结果投递

完成、失败、拒绝和取消都必须写持久回执，并将可去重结果卡投递到来源 Session。结果卡至少包含 `taskId`、目标 Bot、终态、摘要和产物引用；同一 `taskId` 不得重复投递。

后台 prompt 必须等待本请求自己的执行句柄及清理完成，不能用全局 `isStreaming=false` 推断成功。取消、模型错误、断流、上下文耗尽和回合上限必须分别得到明确结果。

## Root/runtime 边界

Bot 协调工具通过现有 ToolExecutor/ToolRegistry Handler 进入 `BotCoordinator`，不走 localhost HTTP 或额外 MCP Server。

Bot Turn 和普通 Chat 共用同一个 direct Ubuntu runtime：App-owned shell lifecycle、per-session mount namespace、App UID/GID guest identity。Bot 不能创建自己的 Root runtime、不能调用 `DirectRootRunner`、不能获得任意 `su -c`、不能绕过 Tool/Approval 权限层。

Root 网络代理只是 Ubuntu guest 的固定出站兼容 helper，不是 Bot 通信通道，也不能承载协调消息或命令。

## 工具与权限

工具列表过滤只是模型可见性控制，不是授权本身；Handler 必须从可信执行上下文重新校验来源身份、通信深度、目标可达性、来源 Session 所有权、额度和 taskId 查询权限。

现有 `ToolPermissionManager` 的调用方策略与 BotToolPolicy 是不同维度。未来 BotToolPolicy 只能收紧系统权限，不能放宽。

## 生命周期与非目标

Android 前台服务按真实 active Turn 运行；数据库里存在 `QUEUED` 不代表系统会自动恢复服务。恢复只在明确 App 初始化入口扫描允许恢复的任务。

当前不默认实现：`ask_bot`、`wait_delegation`、模型 `create_bot`、Chief、Section allow-list、Peer Approval、Room 多发言者、Team Goal、13 轮协调或 Bot/Team 独立 Memory。

必须保持：

- 不把 BotCoordinator / Delegation 状态机塞进 `ChatViewModel`；
- 不把 Subagent、Job、Ralph 改名冒充 Delegation；
- 不为每个 Bot 创建独立 Runtime、Provider、ToolRegistry 或 Ubuntu；
- 不用 Prompt 代替深度、可达性、权限的执行边界拒绝；
- 普通 Chat 的 `bot_id` 为空时，现有 Provider、Tool、Memory、UI、Ubuntu 和 Session 语义保持不变。
