# Agent 执行核心：M1 实现与端到端证据

## M1 当前实现（2026-10-12 工作树）

唯一执行所有者为 `agent/AgentSessionStore` 中的 `AgentSession`。`AgentRunner` 直接获取核心，不构造 ViewModel；聊天页面工厂只获取同一个核心的投影。`ChatViewModelStore` 只保存页面投影，清掉 ViewModelStore 不会取消回合、重建历史或创建第二个循环。删除会话显式关闭核心，等待所有模型、媒体、压缩与持久化子任务退出后再执行 Room cascade；关闭未退出的所有者暂留在 store，拒绝迟到提交。

`AgentSession` 持有 Provider/模型绑定、待提交媒体、checkpoint、外部回执、QUEUE/STEER/BREAK 队列和 token/压缩状态，复用 AgentExecution 的唯一 scope。规范历史与 outgoing 修复仍由 AgentConversation 持有。`AgentLoop`、`AgentSend`、`AgentLifecycle`、`AgentEdit`、`AgentPersistence`、`AgentCompaction` 以及预算/offload、摘要规划/执行、ToolRound、TurnStream 等现役实现均归入 agent 包。UI 扩展是同一实现的调用适配，没有另一套模型循环；无调用方的旧执行/持久化适配文件已删除。语音执行桥直接持有 AgentSession，斜杠命令的产品操作也使用会话 scope。滚动、消息窗口、菜单与文件提示订阅保留在页面投影中；流式消息投影和会话事件由核心发布，前后台一致。

清空、删除消息与撤销压缩通过原有回合 predecessor 屏障串行更新历史；清空先等取消清理和媒体/压缩完成，再以 Room 事务同时删除消息与标记，最后发布投影。重载操作等待实际加载 Job，不能等待由操作本身才能恢复的 UI readiness 标志。新的提交不能穿过正在进行的清空事务。循环内压缩属于当前回合，取消父回合会取消摘要；手动压缩属于会话生命周期，页面销毁不影响它。压缩标记提交失败不会发布内存摘要，后台压缩结果等待自己的完成回执，不再把旧摘要或活动标志当成成功。非等待命令报告正在压缩，避免把 Running 当作失败；全历史压缩忽略传入的边界 ID，保留既有调用契约。

草稿转正式 ID 的别名由同一个执行 store 维护，并向页面发布 generation，两个路由都绑定同一核心。页面挂载另带实例 generation，旧页面后销毁不能清掉已挂载的新页面，包含同一 session 的替换。空会话退出清理在 Main 复核当前页面与回合，不能关闭已经挂载的接替页面。释放/取消边界只关闭相应会话或请求；现有 Bot 锁、并发槽位、权限、Repository、事件日志和 Provider 不另造替代实现。

本轮工件目录：`.local/test-runs/agent-m1-20261012/`，十轮补证据保存于其 `ten-rounds/`；对应 M2 实施前冻结的 M1 安装包。M1 核心迁移和受控端到端验收已完成：37 场景连续 10 轮（370 次场景执行）及普通循环/Bot/附件/语音的 17 项共享调用方回归通过；安装包哈希、实际文件 SHA-256、结构化请求体、源码清单与复跑脚本均保存。Debug/AndroidTest 构建、Release Kotlin/R8 与 Debug/Release Lint 全部通过；正式 Release APK 尚缺生产签名配置。构建与发布路径的最终检查结论见该目录 RESULTS.md，不以 Debug 代替 Release。真实模型质量、Root/Ubuntu/OEM 设备、进程死亡后的持续目标恢复仍不在本轮受控模拟器证据内；通用持久恢复按 M2 开发。

## 前三次切片的历史基线

以下保留阶段证据，描述的是各切片当时的源码与验证，不能当成当前所有权或剩余工作列表。


日期：2026-10-12。此切片按 [总体路线](AGENT-ROADMAP.md) 的 M0 → M1 顺序推进，普通 Agent 是验收主线。Bot、子代理、例程与语音是同一核心的调用方。

## 实施前确认的事实与失败方式

实施前，`AgentRunner` 的执行逻辑由 `debug/HeadlessChatRunner` 承担；后者还处理远程消息快照。会话 VM 的实际唯一所有者已经是 `ChatViewModelStore`，不能再建一个 runner 缓存或模型循环。

必须覆盖：

- 产品层调整只移动名字、仍将执行转交给 debug 实现。
- UI 与后台分别创建 VM，启动两次模型或工具。
- 忙时第二个请求取消第一个请求，或读取第一个请求的答案当作自己成功。
- 等待超时/调用方取消后，已拥有的请求仍在后台执行；挂断音频与取消任务被混用。
- 模型失败、未完成流、输出上限、请求拒绝误报 Completed。
- 提交任务偷走文字草稿附件或编辑状态。
- 重试/工具块重跑使用共享 busy 标记判断完成。
- 会话释放后复用已清除 VM，或迟到回调污染新实例。
- 草稿转真实 ID 后，入口指向不同执行实例。
- 共用会话创建入口把普通任务或子任务标成 debug，来源写入与创建分开导致中断后身份不一致。
- 事件快照丢失历史工具/结果、附件或水位，使远程客户端重复或漏读。

## 本切片的实现范围

把已有执行实现归入 `agent/AgentRunner`，移除只有产品门面在调用的 Headless 旧路径。只读消息投影归入已有 remote 包，Debug RPC 调用共享实现。沿用现有每请求句柄、VM Store、Room、工具与 Provider；不新增第二套调度器或数据库。

会话来源由真实调用方传入 Room 创建事务；普通默认无 debug 标签，Debug、旧/新子代理与 Ralph 保留各自原有来源值。语音的任务提交调用相同 `AgentRunner.submit`，音频挂断仍只结束等待，不变成执行取消。

普通入口的端到端基线通过真实 AgentRunner、App 会话仓库和工具分发，使用受控 Provider 固定结果；观察工具执行、历史、请求归属、超时/取消和 VM 重用。先保存当前实现工件，迁移后复跑相同场景。真实模型任务质量、Ubuntu 构建、浏览器/OEM/Root 设备工作另记待验，不能由 Android 模拟器报告代替。

实现复用范围：原 Headless 的创建/提交/等待/取消/重试/重跑/压缩代码归到 AgentRunner；原只读消息与工具轨迹投影归到 ChatReadMethods；原快照和水位逻辑归到 SessionSnapshotReader。Debug 读取旧名称只保留 Kotlin 类型别名，以兼容现有调用和投影检查；没有第二份执行实现，也没有增加依赖或数据库 schema。

验证入口：`AgentCoreWorkflowE2ETest`。JSON 记录受控模型调用次数、真实文件 SHA-256、事件水位、通过场景和未验证项；本地基线与候选工件保存在 `.local/test-runs/agent-core-20261012/`。现有 AgentLoopCharacterization、BotTurnExecution、AttachedTextReachesModel 与 VoiceAgentWorkflow 场景验证共享调用方行为。

## 后续顺序

### M1 执行生命周期迁出：实施前失败方式

- 停止只取消模型子协程，附件准备、OAuth 或重试截断仍继续。
- 重试句柄在模型启动后才绑定，准备阶段取消无效。
- 旧回合的持久化/工具清理未结束，新回合已开始写同一历史；旧清理撤销新回合的活动状态或屏幕租约。
- 旧回合结束回调把新回合的 busy 状态清空。
- 等待只等待最内层 Job，遗漏准备、子任务或取消后的清理。
- 释放会话只取消 UI scope，新执行 scope 和工具仍存活；旧 VM 又能提交任务。
- 界面离开误释放会话执行；队列、继续、重试和工具重跑仍走另一套生命周期。

验证采用 Android 端到端场景：受控模型阻塞取消清理，在真实 AgentRunner/Room 中停止并立即提交下一回合，核对启动顺序、句柄归属、busy 状态与会话释放；不新增单元测试。

执行入口和回合生命周期切片不代表 M1 全部完成。下一步迁出剩余非 UI 状态、上下文与模型循环；之后进入 M2 的普通持久目标与恢复验收、M3 的工具管线、M4 的现场与产物、M5 的协作及交互入口。

先前 Bot/语音切片保留已有功能，后续随总体路线接入统一核心，不再独立增加接待、mailbox 或语音调度框架。

### M1 回合生命周期实现

`agent/AgentExecution` 持有每会话执行 scope 与完整回合 Job；复用现有每请求句柄，登记后再启动。发送、重试、工具重跑、继续和取消后的队列接续共用该所有者。模型/工具子协程仍保持结构化父子关系，不再替换当前回合引用。完成回调在准备、子协程和清理全部退出后更新忙状态，旧回调不能清除新任务状态。

停止仍立即更新界面；下一回合等待上一个回合真实退出，防止旧持久化、活动状态和租约清理干扰新任务。取消等待中的下一回合也保留该退出屏障。重试句柄在准备阶段即绑定，包含等待媒体落盘；重试/继续不再由 UI scope 延后重新调用入口。重试历史截断和继续提醒的写入在屏障之后执行；取消后的队列接续等待真实退出，移除原先固定 200 ms 猜测。

当前每会话所有者仍由既有 `ChatViewModelStore` 保存，不新增另一份会话缓存。页面离开保留执行，释放会话时 `onCleared` 显式关闭执行 scope 并沿用 shell 终止。已关闭实例拒绝再次提交；UI scope 不再是模型回合的父 scope。模型循环、预算/offload、压缩执行、队列和消息投影仍在 VM；后续会话上下文迁出见下节，尚无进程死亡后的通用目标恢复保证。

本次端到端工件目录：`.local/test-runs/agent-lifecycle-20261012/`。在原有普通 Agent 工作流基础上加入取消清理屏障、准备阶段重试取消、普通重试、部分回复继续、队列接续、工具块重跑、ViewModel scope 独立性和释放后拒绝提交场景。

本切片最终验证：Debug/AndroidTest 构建与 Lint 通过，17 个场景连续 10 次通过，17 项共享调用方 instrumentation 回归通过。真实文件副本、源码/APK 哈希、逐次报告与复跑脚本保留在上述工件目录；受控模型流程不代替 Root/OEM 或真实模型工作验收。

### M1 会话上下文迁出：实施前失败方式

- 核心只保存一份字段包装，实际历史修复、摘要拼接和中断工具恢复仍依赖 UI。
- 摘要和压缩锚点分别更新，下一次模型请求读到不匹配的组合。
- 撤销压缩触发重新加载时，对已有历史再次 addAll，重复发送消息和工具结果。
- 清空会话仅清锚点，界面仍显示旧摘要；新的会话上下文继承旧工具循环窗口。
- 压缩读视图直接修改完整历史，丢掉 Room 审计轨迹、最新用户指令或附件。
- 摘要只写入 content，结构化 contentParts 中没有摘要；Provider 编码后实际上未发送摘要。
- 中断工具的 outcome-unknown 警告被历史读取消费，或未落盘的合成占位结果被当成真实执行结果。
- 重试、编辑、恢复使用另一份历史或工具循环检测器。
- 并发槽位已登记，但协程在 acquireSlot 恢复前被取消：取消只删排队条目，没有归还已授予槽位，同会话下一回合永久等待。原工作流复跑已触发该失败。

实施前先用真实 Room、AgentRunner、压缩与撤销入口保存 Android E2E 结果。沿用现有历史/压缩/Checkpoint 算法，不增加 Provider、存储、权限实现或单元测试。新工件目录：`.local/test-runs/agent-context-20261012/`。

### M1 会话上下文实现

`AgentExecution` 创建并持有唯一的 `AgentConversation`，其中保存规范模型历史、工具循环检测器和最新压缩标记。VM 原有内部历史/检测器访问是同一对象的只读属性转发，便于继续迁模型循环；没有第二份历史或执行缓存。摘要 StateFlow 仅供界面显示，压缩规划和 outgoing 请求从同一个标记快照读取摘要与锚点。

既有 `OutgoingHistory` 移入 agent 包；批次修复和 ToolCheckpointStore 对账/恢复提醒由 AgentConversation 执行，复用原有算法与存储。会话加载替换规范历史；编辑、重试、重跑与删除重建历史时通过同一 replaceHistory 重置工具循环窗口。清空聊天统一清除历史、循环窗口、压缩标记和摘要投影。压缩成功、撤销与锚点自愈共用 applyCompactMarker；规范历史与 Room 审计轨迹继续保留。

实施前 Android E2E 已复现：撤销压缩使 22 条规范消息变成 44 条；部分工具结果行补入的 TOOL_INTERRUPTED 占位内容继承 dbMessageId，错误清掉未知工具 checkpoint。后一处现在排除既有协议的合成结果，只用实际已持久化结果对账，恢复警告的读取仍不消费意图记录。

`AgentContextWorkflowE2ETest` 验证真实压缩、重开、撤销、重复加载、清空、模型请求摘要与工具配对；另一个完整会话流程验证部分批次恢复不会把合成占位当成真实结果。模型传输固定返回，真实进程被杀与 Root/OEM 行为仍未验证。唯一原有单元测试改动是 OutgoingHistory 的 import 随生产包迁移，未新增或执行单元测试。

继续核对真实 Provider 编码后，还复现了 v2 摘要只在 content 中、未进入结构化 contentParts 的问题：完整工作流捕获的 Chat Completions messages 和 Responses input JSON 均缺摘要。现在只在 outgoing 副本中同步装饰两种表示，保持原来的文本/附件 parts 和完整规范历史。E2E 传输边界复用生产 OpenAI 请求编码器，核对两种 JSON 的摘要只出现一次、位于最新用户指令之前；JSON 请求体作为工件保留。该验证覆盖编码，不声称真实外部模型或所有 Provider 网络链路验收。

最终共享回归又触发既有 SessionConcurrencyManager 的槽位泄漏：acquireSlot 已登记 running session，协程在恢复执行前被取消，调用方的 finally 尚未建立。修正即时授予与队列授予两处 continuation.resume，复用协程原生 onCancellation 归还未交付槽位；不另建限流器。AgentConcurrencyWorkflowE2ETest 用五个真实 Agent 会话占满槽位，第六会话在实际 grant 通知时取消，旧 APK 稳定失败，修复 APK 验证排队取消、授予瞬间取消和同会话再次提交。原普通工作流的超时场景同时核对槽位已归还。

会话上下文切片最终验证：Debug/AndroidTest 构建与 Lint 通过；28 场景连续 3 轮通过（保留原 17 场景，共 84 次场景执行），17 项共享调用方回归通过。最终源码/APK 哈希、失败基线、真实请求 JSON、逐轮报告与复跑脚本见 `.local/test-runs/agent-context-20261012/RESULTS.md`。M1 仍包括预算/offload、压缩执行与模型循环等剩余迁移工作，未宣称整个 Minis 重整完成。
