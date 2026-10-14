# 持久任务、执行恢复与验收：M2

> 2026-10-14 当前整体行为见 [M 系列整体接线](M-SERIES-INTEGRATION.md)。本页保留此前按日期记录的切片与证据，不能把旧 APK/重复轮次当作本轮源码已通过。

核对日期：2026-10-13，当前未提交工作树（`main`，HEAD `3527ad8165f11c0f98d8a30f96098fc9280faa94`）。M1 核心迁移已落地；本页保留 M2 切片当日范围；M1–M5 的最终开发与受控验收状态见整体说明。沿 [整体路线](AGENT-ROADMAP.md) 推进，Bot 与普通会话共用现有任务仓库和执行核心。

## 权威状态与兼容迁移

明确 Goal 接入 `bot_tasks` / `BotTaskRepository`，没有假 Bot 或第二套任务框架。Room v24 将 `owner_bot_id` 改为可空，用唯一 `goal_session_id` 标识每会话当前目标；v25 增加进度和完成证据。旧 Bot 根任务的来源、负责人、预算、修订、停止代次和 deadline 原值保留，旧任务自动回合上限默认 3，普通明确目标默认 8，显式配置为 1–100。旧内存 Goal 不能可靠恢复，迁移不从聊天内容猜测创建任务。

目标、验收条件、计划、状态、revision、停止代次、预算及证据读取同一 Room 记录。草稿晋升复用 canonical session 别名，目标订阅跟随正式 ID。模型工具、`/goal`、Debug/Web RPC 和原生目标条共用操作；事务防止检查与更新竞争。没有明确 Goal 的软计划和临时 Todo 保持轻量进程状态。

编辑暂停目标保持暂停与预算。用户修改目标或停止代次后中止旧执行请求；模型在自己的受控回合修订目标后刷新绑定，后续调用使用新 revision。耗尽预算的目标须显式扩充额度并恢复，使用计数不归零。清除目标解除当前关联、使旧代次失效，但不抹掉已完成的历史终态与证据；删除会话同样保留已验收任务事实。清空聊天保留任务，空草稿清理排除明确 Goal。

## Task、请求与运行归属

`AgentTurnHandle` 可由可信入口携带任务 ID。执行准备核对普通会话或 Bot 所有权，将任务快照绑定到实际核心。运行检查点记录 Task ID/revision/stopGeneration 和真实用户消息 ID；模型上下文、Goal 工具、派工和验收都使用这一请求的绑定。多个旧任务唤醒同一会话时，不通过 Session→任意唤醒任务的全局映射推断目标，不修改该会话另外设置的 Goal。

每次工具执行在副作用之前持久写入独立 execution ID，并携带 Run/Task ID。真实工具结果将这一身份随消息保存；Room 提交后才确认精确检查点。模型调用 ID 可重复使用，旧回执不能确认新执行。取消占位结果没有执行身份，不作为恢复成功证据。历史回执缺少新身份时保守保留未知状态。

## 持续推进与停止

明确 ACTIVE 目标在普通回复结束后重新读取持久状态。未验收且没有待运行委派时，在同一核心、同一串行模型循环内按保存的规则接续；每次接续事务消费一次目标预算。模型工具轮数与目标接续预算分别计量。

暂停、需要用户、等待、完成、预算耗尽、chat-only 或未结算工具动作不自动接续。外部暂停实际取消持有旧代次的请求；迟到完成不能越过 revision/stopGeneration 检查。Bot 收件箱复盘沿已有 Dispatcher 启动并消费任务预算，不另建 Agent。WAITING 表示等结果，允许新子工作回执触发复盘；PAUSED 禁止自动唤醒。

## 实际证据决定完成

`update_goal` 结算必须提供当前 Task ID/revision/stopGeneration 与明确进度。完成还必须来自当前实际运行中的 Goal 工具意图，并包含 1–16 个可验证引用：

- 文件引用：复用安全工作区文件访问，流式计算大小和 SHA-256，可对照期望哈希；核验期间检查文件是否变化。无需将整个文件读入模型或内存。
- 工具回执引用：读取同一任务的真实 Room 回执，要求唯一、成功且含执行身份；Goal 自身和未知/中断占位结果不能当验收证据。敏感成功回执只证明真实执行，保存消息/调用/执行身份和 output_redacted，不保存敏感正文或其内容哈希；非敏感输出保存哈希。

普通文本“完成了”和负责人 Turn 正常结束都不升级任务终态。Bot 负责人无实际证据时进入 NEEDS_USER；产生新派工则继续等结果。完成提交与任务状态采用条件更新，旧版本/停止代次拒绝。

## 中断后的核验与交付

模型执行意图已由共用 ToolExecutor 在参数、权限与确认通过之后、执行体之前持久写入；`agent/AgentLoop.kt` 中的 AgentSession 提供不可变 call/run/task 身份，执行器位于 `tools/runtime/ToolRegistry.kt`。参数/权限拒绝不制造未知动作，检查点写入失败不执行工具。`agent/AgentPersistence.kt` 在 Room 保存精确回执成功后确认 nonce；超时、异常和观察失败等未知结果保留检查点，`agent/AgentConversation.kt` 的加载对账使用同一条件。见 [工具执行整理](AGENT-TOOLS-EXECUTION.md)。

进程死亡后先恢复任务、预算、计划和未结算动作；模型收到带调用、执行、Run/Task 和脱敏意图数据的 UNKNOWN 提醒，不盲目重放副作用。

未知完整文件写入已通过 `verify_outcome` 独立核验。模型先从 `get_goal.unknown_outcomes` 取得原 execution ID、Run、Task 与版本，再提交当前任务的 ID/revision/stopGeneration 和精确 outcome 身份。核验只结算该动作，不提交任务完成证据、不重放写入，也不自动恢复 WAITING/NEEDS_USER；ACTIVE 任务按原有目标预算接续，等待或需用户的任务保留其控制状态。暂停、过期版本、错误 Run/Task、跨会话及无实际 owner 执行身份均拒绝。

新写入意图只保存最终文件路径、UTF-8 字节数与 SHA-256，不把长正文或额外参数写入检查点。只支持非 append 的完整写入；旧完整正文须有原任务版本与停止代次的可信 Run 绑定。实际文件须与原意图指纹一致，同 call ID 的另一 execution 独立保留。核验回执在 Room 事务内保存原执行身份及文件证据后才 ACK；提交后 ACK 前崩溃或重试读取已有精确回执，不重复核验历史动作或保存回执，文件后续变化另行记录为当前事实。`get_goal` 不把自身正在执行的读取检查点当作未知动作。

任务完成仍单独调用 `update_goal(status=completed)`，拒绝任何其它未结算动作和旧 `resolved_call_ids` 批量确认参数。安装、网络请求和任意 Shell 的未知结果仍需领域核验或用户决定；不能删除检查点假装成功。检查点读取、追加与确认现共用整文件校验，损坏、重复 execution ID、坏编码、权限/I/O 错误和非普通文件失败关闭并保留原文。只有确认不存在的文件按新现场读取；历史 reported 仍是未知动作，缺少新身份的旧记录不被非模型入口过滤。损坏读取在实际模型请求前停止，自动接续不消费目标预算，也不换模型修复本地存储。页面发送压力检查与程序化请求明确报告错误/拒绝，不让异常逃逸或留下悬空请求。各入口结果与持久回执语义、非文件核验及 M4/M5 完整交付继续推进。重新上线后恢复现场不等于承诺手机关机期间执行，常驻自动唤醒仍遵循 Android 生命周期和已保存授权。

## 验证与边界

Android instrumentation 使用真实 App、Room、执行核心、工具分发、工作区和 Provider 请求，仅模型传输受控。第一切片冻结证据位于 `.local/test-runs/agent-m2-20261012/`；本次执行闭环与回归工件位于 `.local/test-runs/agent-m2-loop-20261012/`，最终通过统计以该目录 `RESULTS.md` 为准，阶段日志不替代最终构建证据。

工作流包含实际文件写入/读回/验收、60 MiB 流式核验、无证据和伪造引用拒绝、自动预算、执行中暂停、请求任务越界、同会话旧 Bot 任务归属、检查点重用与精确对账。恢复分两次 instrumentation，中间启动真实页面、确认活跃 PID、`am force-stop` 确认退出，再恢复 ACTIVE 目标、核验文件并交付；不重复写入，预算和代次保留。真实 v23 数据库文件迁移经 v24→v25 的 Room schema 验证。

上述实现切片不新增或运行 JVM 单元测试；既有类型/签名仅机械适配并编译。模拟器不替代真实模型质量、Ubuntu 构建、Root/SELinux/VPN/BPF/OEM 或生产签名验收。M4 已复用完成证据接入持久成果引用和会话分类清理，见 [工作现场与交付](AGENT-WORKSPACE-DELIVERY.md)；SAF 提供的非 POSIX 外部目录、非文件未知副作用、完整交付闭环、语音恢复和协作交接仍随 M3–M5 推进。根任务按来源在 Room 事务中去重；清除/取消目标会停止实际负责人及已有委派，重复取消不重复投递，执行中的成员保留未知副作用标记。未结算成员会在同一验收事务中阻止根任务完成。来源删除也停止无成员记录的根任务；暂停仍不打断已运行成员。模型自身清除目标先保存控制回执。执行中从界面新增 ACTIVE 目标由原请求在模型响应边界领取，不替换其已有根任务。协作流程使用真实 Agent 请求、Room 与回执，成员关联包含恢复日志种子，不代表完整无人值守派工或真机验收。

协作后续切片证据保存于 `.local/test-runs/agent-m2-coordination-20261012/`；它独立于前一执行切片的冻结源码与 Release 工件。当前阶段仍不宣称 M3–M5 已完成。

2026-10-13 独立文件动作核验、当前任务上下文及记忆流程分别连续 10 轮通过；同一最终主/测试 APK 上另回归任务闭环、真实强制停止后的恢复和五组共享入口，共 38 次 instrumentation、376 次场景执行。Debug/测试 APK、Release Kotlin/R8 及 Debug/Release Lint 通过，Lint 保留既有 baseline。冻结源码、安装包指纹、每轮报告、阶段失败与复跑方式见 [.local/test-runs/agent-m2-verification-20261013/RESULTS.md](../../.local/test-runs/agent-m2-verification-20261013/RESULTS.md)。该证据不覆盖损坏/不可读检查点的失败关闭，也不代表生产签名或真实 Root/OEM 验收。

同日后续检查点完整性切片已复现并修复损坏记录导致实际任务误完成的问题；解析错误不附带记录原文。最终同一主/测试 APK 上，25 个损坏/恢复场景、5 个执行意图场景及 10 个独立核验场景分别连续 10 轮通过，加上共享入口、任务闭环、实际数据库迁移及两组强制停止恢复，共 45 次 instrumentation、499 次场景执行通过。补齐新增提示的五种翻译后，最终 Debug/测试 APK、Release Kotlin/R8 与 Debug/Release Lint 通过；旧 APK 结果不计入最终统计。源码、安装包、原始检查点哈希和各轮工件见 [.local/test-runs/agent-m2-checkpoint-integrity-20261013/RESULTS.md](../../.local/test-runs/agent-m2-checkpoint-integrity-20261013/RESULTS.md)。既有 JVM 夹具仅类型编译，尚有旧前提不适配，不能据此声称完整 CI 通过；本切片不证明真实模型、正式签名或 Root/OEM 行为。

2026-10-14 Android 领域回执切片：参数/授权拒绝保留未执行事实，联系人聚合 ID 观察失败及日历提醒不确定回执沿真实模型与 Room 保留精确意图。日历事件成功而提醒失败继续保留实际部分结果；UNKNOWN 不自动重放，也不消费自动接续预算。认证 Guest CLI 的可选 framing 和经确认的 MCP 返回同一执行事实。验证及限制见 [工具执行](AGENT-TOOLS-EXECUTION.md) 和 [.local/test-runs/agent-m3-domain-outcomes-20261014/RESULTS.md](../../.local/test-runs/agent-m3-domain-outcomes-20261014/RESULTS.md)；这尚未增加日历/联系人等非文件动作的 verify_outcome 能力。
