# Bot 持久任务、委派、收件箱与自动复盘续查

日期：2026-10-05。当前提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。固定 openminis 对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，本地目录 `/tmp/openminis-review-20261004`。

本轮新增 10 项静态发现，另建报告；只写本报告和阅读台账，不修改产品代码或旧报告，不运行构建、测试、模拟器、设备或云任务。涉及并发、进程死亡的后果由下述源码顺序推导，尚未做设备复现。P1 表示停止边界、错误完成或重复副作用；P2 表示持久状态、恢复和可用性错误。

| 编号 | 优先级 | 问题 |
|---|---|---|
| BC01 | P1 | 主 Bot 正常结束回合且未再委派，就被当成任务验收通过 |
| BC02 | P1 | 根任务暂停／取消不阻止排队成员启动，也不取消已运行成员 |
| BC03 | P1 | 派工末尾的无条件写回能复活刚暂停／取消的根任务 |
| BC04 | P2 | 根任务目标来自第一个成员工作包，验收标准未接入生产入口 |
| BC05 | P1 | 自动复盘中断后租约重领会重新生成，缺少此前执行结果未知的处理 |
| BC06 | P2 | 收件箱先消费，根任务后更新；中断后可永久停留在活动状态 |
| BC07 | P2 | 检查主 Bot 空闲后没有预留执行权，Busy 回合也会消费全部结果 |
| BC08 | P2 | 删除 Bot 与新会话创建不协调，可留下引用已删除 Bot 的会话 |
| BC09 | P2 | 终态回执投递异常被静默吞掉，没有当前进程内的后续重试 |
| BC10 | P2 | 来源会话删除后回执被处理掉，根任务没有对应结束状态 |

## BC01 — 回合结束不等于验收通过

[BotWakeDispatcher.kt:273](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:273)只看 `result.status == Completed`；[309 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:309)把它和剩余非终态委派数交给策略。[BotWakePolicy.kt:55](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakePolicy.kt:55)在成功结束、没有新委派时无条件返回根任务 `COMPLETED`。

但同一策略的[复盘指令](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakePolicy.kt:81)允许回答“需要用户决定，或预算已尽”。模型正常回复“测试仍失败，需要你决定是否接受”“修订额度已尽，未达到要求”，同样是成功完成一个模型回合。[HeadlessChatRunner.kt:226](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:226)只映射执行句柄结果，没有任务验收结论。

因此失败成员的结果、未知结果或主 Bot 明确等待用户的文字，都可能在主 Bot 正常回复后被标成已完成并发完成通知。现有 [BotWakePolicyTest.kt:84](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/BotWakePolicyTest.kt:84)恰好断言该二元规则，没有覆盖“回合成功、验收未通过”。

修复方向：在现有任务边界保存主 Bot 明确的验收结论与证据；执行完成、需要用户决定、修订失败分别转换。不能仅靠自然语言提示保证数据库终态正确。

## BC02 — 停止只影响根记录，成员执行不受它约束

[BotsScreen.kt:302](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/bots/BotsScreen.kt:302)和 307 行分别调用 Repository 的 pause、cancel；[BotTaskRepository.kt:64](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotTaskRepository.kt:64)最终只调用 [BotTaskDao.stop](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotTaskDao.kt:53)，更新根任务状态与 stop_generation。

[成员派发链](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:317)检查来源会话、来源 Bot、目标 Bot、模型和委派状态，但不读取 rootTaskId 对应根任务的状态、停止代次或 deadline。忙目标解除占用后，[375 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:375)仍重新派发。恢复查询也只依据委派的 QUEUED／WAITING_TARGET 与 source_turn_settled。

明确触发：目标成员忙，委派进入 WAITING_TARGET；用户在进度页取消根任务；目标变为空闲，旧委派仍创建 session 并执行。已经 RUNNING 的成员也没有因 root stop 调用 AgentRunner.cancel。自动复盘的 stop_generation 检查能阻止部分后续复盘状态写回，不能阻止这些成员副作用。

修复方向：根任务停止与所有所属排队／运行委派协调；领取前检查持久停止条件，运行时把取消传到实际执行句柄，并保留结果未知／清理退出语义。暂停至少须阻止新派发，其运行中处置应明确。

## BC03 — 派工最后一步无条件把停止状态覆盖成 ACTIVE

[enqueueFromTool](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:175)先读取根状态，再插入委派、[203 行预留额度](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:203)，最后调用 updateState 写 ACTIVE／EXECUTING。[BotTaskDao.kt:26](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotTaskDao.kt:26)只以 id 为条件，无状态或 stop_generation 比较。

可达交错：recordDelegation 在 ACTIVE 时成功；用户 pause／cancel 提交并增加 stop_generation；派工随后执行 updateState，把 PAUSED／CANCELLED 覆盖成 ACTIVE。停止代次的数字仍在，但以后新的唤醒会把它当成当前基线，不能恢复被覆盖的停止状态。

这与 BC02 不同：BC02 是停止状态仍然正确、成员执行忽略它；这里连数据库权威状态都会被较晚的派工写回复活。

修复方向：预算预留、委派写入和根状态推进使用协调的条件更新／事务边界；所有较晚写回必须拒绝覆盖停止状态或不同停止代次。

## BC04 — 根目标实际上是第一个成员的工作包

[BotTaskEntity.kt:8](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotTaskEntity.kt:8)把根记录定义为跨回合的用户目标，一个委派只是其中一个工作包。但生产中唯一的 [ensureRootTask 调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:168)使用 `goal = prompt`，originMessageId 使用 runId，不是用户消息 ID；未传 acceptanceCriteria。

[Repository 按来源复用根记录](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotTaskRepository.kt:25)，同回合第二个成员加入相同根任务，也不会补充目标。整个生产树没有其他写入 acceptanceCriteria 的调用；[唤醒 prompt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakePolicy.kt:70)因此把第一个工作包作为“任务”，验收标准总是回退到“未指定”。

例如用户要求完成整个应用，第一项委派是审查图标、第二项是修复登录，进度页根标题和系统复盘目标只保存审查图标。原会话历史可能仍含完整请求，但权威根记录及自动注入内容不能代表完整目标，历史压缩／会话变更会进一步放大差异。

修复方向：从真实来源用户目标建立根任务，并绑定来源消息；显式保存验收信息，委派 prompt 仍只表示成员工作包。不要把字段存在视为生产链已经接通。

## BC05 — 自动复盘的租约恢复重新执行模型回合

Worker 的恢复有正确的保守分支：[BotDelegationCoordinator.kt:51](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:51)将中断 RUNNING 标为失败、outcomeUnknown，不重新生成。Owner 自动复盘却只有[收件箱租约](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:214)，[262 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:262)关联根任务和会话的是进程内 WakeTaskRegistry。

主 Bot 已通过工具修改文件或执行外部操作，进程在 finally 消费事件前死亡：CLAIMED 持久保留，12 分钟租约过期后 [releaseExpired](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotInboxEventDao.kt:42)改回 PENDING；启动恢复或租约计时器重新 schedulePendingGroups，[265 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:265)发起一条新的 AgentRunner.prompt。

此链没有用 wake_batch 查找上一执行句柄／checkpoint 的完成状态，也没有持久的 owner outcomeUnknown 处置。新的模型回合可能再次执行此前副作用；3 次唤醒额度仅限制次数，不提供操作幂等。不能据此声称每次恢复都必然重复同一个工具调用，但已经产生作用的复盘存在再次生成的路径。

修复方向：把复盘批次和实际 run 的身份、启动／结束结果关联持久保存。恢复先核对已有完成或未知结果，再决定后续行为；不能把租约过期直接等价为这轮从未执行。

## BC06 — 消费事件与任务转换之间有永久卡住窗口

[BotWakeDispatcher.kt:294](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:294)在 finally 先逐条消费 claimed 事件；[301 行之后](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:301)才读取根任务、更新 COMPLETED／NEEDS_USER 等状态。二者是分开的持久写入，未形成统一提交。

最后一条事件消费完成后进程死亡，或后续 DAO 更新失败，没有新委派时，根任务仍 ACTIVE／REVIEWING，而全部事件已 CONSUMED。启动恢复只扫 PENDING、过期 CLAIMED 与委派恢复记录，不扫描这种“无未消费事件、复盘已结束但根未收尾”的根任务。NonCancellable 只能处理协程取消，不能跨进程死亡或数据库异常。

修复方向：先持久保存复盘结果，再在数据库边界统一提交事件消费与根状态转换；或者提供可据持久结果补收尾的恢复步骤，避免重新执行模型来猜结果。

## BC07 — 空闲检查到真正提交之间，Busy 被当成需要用户处理的失败

[171 行的 isLocked 检查](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt:171)只是一张瞬时快照。随后多次数据库读写、消耗一次 autoRunsUsed、领取全部结果、构造 prompt，才调用 AgentRunner.prompt；没有像成员派发那样用 tryAcquireExternal 预留 Bot 执行权。processingGroups 也按 botId＋taskId 分组，不能串行同 Bot 的多个根任务。

用户在这段窗口开始同来源会话的直接聊天，或者同 Bot 的两个根任务同时唤醒同一会话时，[HeadlessChatRunner.kt:203](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:203)会返回 Busy。Dispatcher 仍把它当作普通失败，finally 消费全部事件，再写 NEEDS_USER；实际复盘没有运行，唤醒预算也已扣除。原有 VM 拒绝 Busy 的保护仍然有效，问题在调度器对该结果的不可重试处理。

修复方向：在扣额度／领取前协调 Bot 执行权；临时 Busy 应保持可继续的持久事件并等待，而不是要求用户处理并消费结果。不要持有 Bot 锁后又走一个会自等待同锁的调用链。

## BC08 — 删除与开会话分属不同的协调范围

[BotRepository.openConversation](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotRepository.kt:21)持有 conversationMutex，先读 Bot，再等待配置／解析模型并创建 session。[deleteBot](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotRepository.kt:93)既不持这把锁，也不把 clearBotFromSessions 与 deleteBot 放到同一事务。Bot 与 Session 的引用没有约束这次新插入的父对象存活。

交错一：打开新话题读到 Bot；删除清空旧 session.bot_id 并删 Bot；打开流程用旧 Bot 快照创建新 session。交错二：clearBotFromSessions 后、新 session 插入、再 deleteBot，也留下同样孤立引用。界面的[打开会话入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/bots/BotsScreen.kt:151)和[删除入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/bots/BotsScreen.kt:403)没有统一这些写入；后台通知 fallback 也可 openConversation。

后续 [ChatViewModel.kt:7723](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7723)查不到 Bot 时不会注入其身份／指令；协调工具又拒绝 missing source Bot，孤立会话的身份与实际行为不一致。现有禁用 Bot 继续查看旧会话是明确 UI 行为，未将其作为缺陷。

修复方向：删除与创建在同一实际持久边界验证父对象存活，并确保删除清空覆盖所有并发新会话；不要只给单个 UI 按钮加 loading。

## BC09 — 投递失败被吞掉，只有启动恢复再尝试

[BotDelegationCoordinator.kt:483](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:483)将 appendMessage、notifyExternalMessage、写 inbox、markDelivered 和调度整个包进 runCatching，但没有检查结果、日志、重试队列或失败回调。回执存储正确采用 idempotencyKey；问题是失败后不再驱动重试。

例如助手结果卡已保存，notifyExternalMessage 在构造／加载 VM 时抛异常，后续 inbox 没创建、deliveredAt 为空；来源用户可以看到卡片，主 Bot 自动复盘却没有事件。临时数据库投递错误也会留下终态但未送达记录。当前进程其他来源 settle 只调度非终态委派，不扫描这些终态回执；再次机会来自 App 启动恢复。

这与旧报告 F23 的“超过 32 条未排空”不同：本项一条记录即可触发，与批次上限无关。幂等写卡本身不是缺陷。

修复方向：持久投递与 UI 通知分开处理；记录失败并在当前进程安排有界重试，确保 UI 投影失败不能阻止已持久结果进入 inbox。保留已有去重，不重复执行成员。

## BC10 — 来源会话删除后，根任务仍可永久显示 ACTIVE

[ChatRepository.deleteSession](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ChatRepository.kt:148)没有协调根任务；[MinisApp.kt:447](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:447)注册的删除回调只删除 Ubuntu session 数据。[deliverReceipt 的来源缺失分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt:456)把委派标 delivered 后直接返回，不更新根状态或发布处理结果。

触发一：成员 RUNNING 时删除来源会话，成员终结后走此分支。触发二：settled 的 queued 委派恢复时发现来源消失，取消该委派，再走此分支。根任务可保持 ACTIVE／EXECUTING，所有成员均终结且没有 inbox，进度页仍显示活动；点击对应委派的来源入口只能报会话缺失。

保留任务审计记录是合理需求，但审计记录的活动状态须与无法继续执行的事实一致。本项不同于 BC06 的复盘提交窗口，也不要求删除历史记录。

修复方向：来源删除时或后续确认来源消失时，给根任务一个明确、持久的结束／需要处理状态，同时协调所属委派。不能只消费无法送达的回执。

## 对照、排除与静态验证边界

固定上游的 Android 源码树没有本轮这套 BotTask／BotDelegation／BotInbox／BotWake／BotsScreen 实现；`WebPreviewBottomSheet` 的名字含 bot 子串，与 Bot 协调无关。以上属于本地新增链的缺口，不声称可从上游直接移植现成修复。

已确认有效的边界：委派及 inbox 有持久唯一去重索引；领取／完成使用条件 SQL，不能因为没有单独 @Transaction 标记就断言单条 UPDATE 会重复领取；目标成员使用独立 session；来源 settle 屏障存在；Worker RUNNING 恢复保留结果未知；同 Bot 模型执行锁、local-only 工具权限与现有无人值守策略仍在。本轮没有发现或推断恢复 PRoot、minisd 或 broker 执行栈。

未将以下线索升级为发现：ensureRootTask 非唯一来源索引在生产调用是否会实际并发创建两根；限额查询／插入的理论并发绕过；多任务同来源会话的 WakeTaskRegistry 歧义；2048 条 inbox 扫描是否会长期饥饿；结果空白归一化对具体产物验收的影响。均需补完整真实调用／恢复条件，不能仅凭搜索计数认定。

旧 F23、F25、F38 与已有数据库报告未重复计为新增。只读查看既有测试，未运行或新增测试；现有测试不能覆盖上述多对象数据库提交窗口、真实暂停取消、Busy 竞争与进程恢复副作用。

## 后续端到端验收提纲（未执行）

| 场景 | 应保存的可重复工件与验收事实 |
|---|---|
| 复盘明确回答未通过／需要用户决定 | 本轮执行结果、回答、根状态与通知；正常结束不能覆盖验收失败 |
| 忙目标排队后暂停／取消；派工提交时停止 | 停止前后根／委派 DB 快照、执行计数、停止代次；禁止新启动和较晚复活 |
| 两个不同工作包属于同一用户请求 | 用户消息 ID、根 goal／criteria、两个委派归属、复盘输入；保留完整用户目标 |
| 主 Bot 工具已产生作用后进程中断 | wake_batch／run／checkpoint、作用计数、重启后状态；未知作用不可静默重新生成 |
| 最后一条事件消费后中断 | inbox／根状态提交证据、重启后收尾；无永久活动根、无重复副作用 |
| 领取窗口插入用户回合／两个根同会话唤醒 | Bot 锁、执行句柄、事件状态及预算；Busy 可继续，不能吞掉待复盘结果 |
| 删除 Bot 与新话题交错 | 对象删除／插入顺序与 session.bot_id；无孤立身份 |
| 卡片写入后注入通知失败／临时投递错误 | card 去重键、deliveredAt、inbox、重试日志；当前进程最终完成投递且只一份卡 |
| 成员运行／排队时删来源会话 | 根、委派、inbox、进度 UI；审计可留，根状态不再假活动 |

## 全文阅读覆盖

本轮新增全文 29 文件、3,783 行；复核此前已全文的 AgentRunner 188 行不重复计入新增。HeadlessChatRunner、ChatViewModel、MinisApp 只补充实际读取片段，不算全文。

累计全文 432／1,546 文件（27.94%），95,347 行；另 20 文件仅片段、1,094 文件尚无全文登记。全库仍未读完，目标继续有效。台账：[code-review-coverage.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

| 本轮新增全文文件 | 行数 |
|---|---|
| [androidTest/java/com/openminis/app/ui/chat/BotTurnExecutionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/androidTest/java/com/openminis/app/ui/chat/BotTurnExecutionTest.kt) | 132 |
| [main/java/com/openminis/app/agent/BotContextResolver.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/BotContextResolver.kt) | 36 |
| [main/java/com/openminis/app/agent/BotModelResolver.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/BotModelResolver.kt) | 23 |
| [main/java/com/openminis/app/data/db/BotDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotDao.kt) | 47 |
| [main/java/com/openminis/app/data/db/BotDelegationDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotDelegationDao.kt) | 84 |
| [main/java/com/openminis/app/data/db/BotDelegationEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotDelegationEntity.kt) | 56 |
| [main/java/com/openminis/app/data/db/BotEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotEntity.kt) | 16 |
| [main/java/com/openminis/app/data/db/BotInboxEventDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotInboxEventDao.kt) | 59 |
| [main/java/com/openminis/app/data/db/BotInboxEventEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotInboxEventEntity.kt) | 47 |
| [main/java/com/openminis/app/data/db/BotTaskDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotTaskDao.kt) | 81 |
| [main/java/com/openminis/app/data/db/BotTaskEntity.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/BotTaskEntity.kt) | 66 |
| [main/java/com/openminis/app/data/repository/BotDelegationRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotDelegationRepository.kt) | 140 |
| [main/java/com/openminis/app/data/repository/BotInboxRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotInboxRepository.kt) | 91 |
| [main/java/com/openminis/app/data/repository/BotRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotRepository.kt) | 113 |
| [main/java/com/openminis/app/data/repository/BotTaskRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/BotTaskRepository.kt) | 88 |
| [main/java/com/openminis/app/data/repository/MemberAvailability.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemberAvailability.kt) | 20 |
| [main/java/com/openminis/app/tools/BotDelegationCoordinator.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotDelegationCoordinator.kt) | 607 |
| [main/java/com/openminis/app/tools/BotTurnLockRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotTurnLockRegistry.kt) | 48 |
| [main/java/com/openminis/app/tools/BotWakeDispatcher.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakeDispatcher.kt) | 442 |
| [main/java/com/openminis/app/tools/BotWakePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotWakePolicy.kt) | 186 |
| [main/java/com/openminis/app/tools/runtime/BotDelegationHandler.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/BotDelegationHandler.kt) | 46 |
| [main/java/com/openminis/app/ui/bots/BotsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/bots/BotsScreen.kt) | 836 |
| [test/java/com/openminis/app/agent/BotContextResolverTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/BotContextResolverTest.kt) | 42 |
| [test/java/com/openminis/app/data/MemberAvailabilityTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/MemberAvailabilityTest.kt) | 26 |
| [test/java/com/openminis/app/scheduled/ScheduledTaskBotIdJsonTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/scheduled/ScheduledTaskBotIdJsonTest.kt) | 39 |
| [test/java/com/openminis/app/tools/BotDelegationContractTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/BotDelegationContractTest.kt) | 91 |
| [test/java/com/openminis/app/tools/BotInboxLeaseTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/BotInboxLeaseTest.kt) | 133 |
| [test/java/com/openminis/app/tools/BotWakePolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/BotWakePolicyTest.kt) | 169 |
| [test/java/com/openminis/app/tools/runtime/BotToolPermissionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/tools/runtime/BotToolPermissionTest.kt) | 19 |
