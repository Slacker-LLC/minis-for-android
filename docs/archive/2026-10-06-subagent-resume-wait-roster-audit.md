# 子 Agent 恢复、等待与名册边界静态审查

日期：2026-10-06。本轮新增独立报告，确认 5 项新问题：4 项 P2、1 项 P3。旧报告 F24/F25 只复核，不重复计数。不修改产品代码或旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，只读取既有 Git 对象，没有拉取或执行上游代码。本轮没有构建、执行测试、启动模拟器、使用 adb/Root/设备/云资源、访问账号或读取凭据，也没有启动子 Agent。以下运行顺序与竞态均是静态推演，全代码通读仍未完成。

## SA01 · P2 · 原本 wait=true 的任务恢复后没有结果接收方

[job 定义](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentJobRegistry.kt:45)持久保存 `wait`；[重载](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentJobRegistry.kt:283)只将 QUEUED/RUNNING 改成 INTERRUPTED，没有更改等待方式。[resume](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentJobRegistry.kt:242)重新标为 RUNNING/QUEUED 并保留 wait=true，[runtime 的恢复入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:305)直接 launch，不创建新的 waiter。

任务完成后，[complete](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:228)仅在 `!done.wait` 时投递后台回执，再去完成本进程的 waiter。进程重启后，[waiters](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:80)是空表，旧的阻塞调用和 deferred 已经不存在。因此一个原先 `wait=true` 的任务，在被恢复后无论完成、失败、取消还是超时，都可以只更新 job 状态，不向父会话投递结果，也没有新的阻塞调用接收它。

这与恢复入口返回的“Resumed runs report back as a new message”不一致。父会话可以主动 status 查询或查看 child，不代表完成通知已经送达；本项不声称结果文件或 job 记录被删除。

[恢复测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt:431)通过 `startAndLose()` 创建默认 wait=false 的任务，只覆盖后台恢复回执；[普通 wait=true 测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt:140)验证同一进程的 waiter，并没有覆盖 wait=true 跨重启后的接收方式。测试源码未执行。

修复方向记录：恢复时明确建立新的结果接收方式；旧阻塞调用已消失的任务应切为可投递回执，或由恢复调用重新拥有等待生命周期。不能继续仅依据旧 wait 字段抑制回执。

## SA02 · P2 · 排队 admission 先发布，waiter 后注册，完成通知可能丢失

[delegate](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:117)先 `registry.submit()`，此时排队 job 已进入全局队列并可被其它 worker 晋升；Queued 分支到 138 行才调用 `waiterFor()`。这两步没有共享的原子边界，也没有在注册后读取已有终态补发完成。

[晋升](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentJobRegistry.kt:198)受 registry 锁保护，但[waiter 注册](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:145)独立写入 ConcurrentHashMap；[完成通知](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:234)只做一次 `waiters.remove(jobId)?.complete(done)`。线程安全容器不保证这些业务步骤的整体原子性。

可成立的静态交错：父调用 B 提交 D（wait=true），D 进入 QUEUED；B 在 submit 返回后、waiterFor 前被调度暂停。另一父会话的 wait=true 子任务 A 完成，释放槽位并晋升 D；D 创建 child 或启动时快速失败，进入 FAILED，complete 找不到 D 的 waiter，跳过投递（D.wait=true）并结束。B 恢复后注册 D 的 waiter 并 `await()`，完成事件已经过去，job 已经 FAILED，deferred 却永远不会被该完成路径唤醒。仍可由外部取消中断，所以不声称进程无法停止。

该反例使用另一父会话的同步任务来释放槽位，避免依赖旧 F24 的回执等待问题；真实 runtime 使用[独立 Default scope](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt:202)，调用者与启动/完成 worker 可并发。创建/运行失败的真实异常会被 launch 捕获后调用 complete，本项不是仅在测试 fake 中存在的状态转换。

[现有队列测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt:179)覆盖默认后台任务，[同步等待测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt:140)覆盖立即启动，未验证排队的同步任务在 waiter 注册前已终结的窗口。

修复方向记录：等待者必须在 job 对其它 worker 可见之前建立，或由同一状态协调边界保证注册与终态补发不会漏通知；不要仅添加一次无锁终态检查来留下另一种竞态。

## SA03 · P2 · 用于重启执行的完整 brief 被静默截断

[参数解析](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentTask.kt:84)接受完整 task/context，[childBrief](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentTask.kt:224)将用户设定的 instructions、任务与原始材料组装为完整 brief。[保存 job](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:127)将这份完整说明交给 registry，内存中的 job 会继续持有全部内容。

但[Prefs 编码](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/PrefsSubAgentJobStore.kt:30)对 brief 无条件 `.take(20_000)`，没有截断标记、原始长度或“不允许恢复”的状态。[加载](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/PrefsSubAgentJobStore.kt:40)照常解码；QUEUED/RUNNING 会被转为可恢复的 INTERRUPTED。[启动恢复任务](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:165)在没有 child 的情况下直接使用持久的 `job.brief` 创建新任务，不请求补全或重新校验。

触发窗口：完整 brief 超过 20,000 字符的任务已被接受但尚未创建 child，进程被系统终止，重开并 resume。恢复的新 child 得到的是前 20,000 字符，而非原本接受的任务。若背景资料后面还有返回格式、排除范围、必须核对的路径或其它结尾约束，它们会无提示消失。这些长字符串在解析层没有相应拒绝条件。已有 child 的恢复使用原会话和 resumeNotice，因此本项特别限定在未创建 child 的任务。

[持久化长度测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/PrefsSubAgentJobStoreTest.kt:20)只验证 resultText 被裁短；[无 child 恢复测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt:469)使用很短的任务，不能证明超长 brief 仍能完整恢复。结果文本展示有截断标记，不等于执行说明截断也被告知。

修复方向记录：保留有界存储，但可重新执行的 spec 必须完整；超预算要在 admission 前明确拒绝、转到已有适用的完整 spec 存储，或保留显式不可恢复状态，不能把无标记的前缀当成完整任务执行。

## SA04 · P3 · 进度查询只读已提交消息，看不到当前正在执行的工具

[describeChild](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt:93)从 DAO 加载历史消息，再根据最后一条已持久化 assistant 的 toolUse 和历史结果 ID 推断当前工具，最新正文也只来自已经写入的 assistant 行。

真实 loop 在[执行工具循环](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9229)运行工具，直到这一轮工具执行结束才[提交 assistant+tools](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9589)，随后提交结果。当前长时间运行的第一项工具通常还没有对应 assistant 行，live ToolBlock、流式正文和 SessionActivityTracker 的状态不在 describeChild 的读取来源内。

例如 child 第一项工具持续运行一分钟，progress_report=frequent：15 秒后查询可能只有 user 行，currentTool 和 lastText 都为空；[ticker](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:210)以二者作为 signature，仅能投递一次没有当前工具内容的 running 头部。工具执行期间输出继续变化也不会改变这份 Room 查询结果，不能实现约定的当前工具和最新消息进度。不是把“未调用工具时 currentTool=null”的正常行为当成问题。

[现有进度测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt:532)手动给 FakePort 填入 `ChildProgress("shell_execute", "reading files")`，没有验证真实 AppSubAgentPort 从何处取得它。本轮未运行测试。

修复方向记录：复用现有受界定的 live 状态/事件投影读取当前工具和最新正文，以已提交消息作为恢复后备；不要为进度显示重复复制一套 Agent 状态。

## SA05 · P2 · 名册先检查原始名字重名，后截短，归一化后仍可能重名或丢配置

[clamp](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt:71)把名字截到 40 字符；[normalize](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt:176)却先对原始 `def.name` 计算 nameKey 并去重，再把 `def.clamped()` 加入结果。两个长度 41 的名字只要前 40 字符相同、末字符不同，原始去重会同时通过，最终返回两个同名 Agent。[resolve](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt:199)只返回首个匹配，因此后一个不能按名字被唯一寻址。

真实数据入口存在：[备份映射](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupSubAgents.kt:66)接受这些非空长名字，[合并](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt:227)在原始名字上检查冲突，再 normalize。[导入调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:913)随后又将已归一化的 roster 交给 `SubAgentStore.save()`；[save](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentStore.kt:44)第二次 normalize，此时名字已截成相同的 40 字符，后一个配置会被丢弃。

具体反例：备份有不同 id 的两个自定义 Agent，名字分别为 `"a"×40 + "1"` 和 `"a"×40 + "2"`，各有不同 instructions/model pin，local 只有内置 Agent。merge 返回 written=2，并返回两个同名项；save 再处理时只保存第一项，而导入报告仍按 merged.written 加 2。没有提示第二份配置因截短碰撞而未保留。这是合法类型、非空名字的输入路径，不需要运行模型。

[编辑器长度限制](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SubAgentsScreen.kt:199)能约束正常手工输入，所以不声称在编辑器直接输入第 41 字符就会触发；问题范围是备份、旧/受损持久配置等走归一化的数据入口。[现有 clamp 测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRosterTest.kt:84)只检查单条过长名字，[备份重名测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupSubAgentsTest.kt:76)只覆盖原始名称已经相同的情况，均未覆盖截短后的碰撞。

修复方向记录：在最终规范化名字上做唯一性判定，保持 normalize 的幂等性；导入应明确报告冲突及实际保存数量，而不是把丢弃的配置也计为已导入。

## 旧问题复核与 OpenMinis 对照

旧[F24](/home/jiale/projects/minis-for-android/docs/archive/2026-10-04-project-audit.md:476)的回执等待与队列停顿仍能从当前 complete/deliverToParent 读出。本轮补充的触发是：父会话等待队列中的 wait=true 子任务，而其它背景子任务完成后卡在向该父会话投递结果，尚未调用 promoteNext；它是旧 F24 的同一顺序缺口，不重复列新项。旧[F25](/home/jiale/projects/minis-for-android/docs/archive/2026-10-04-project-audit.md:495)的后台输入所有权问题也不重复列项；本轮没有修改旧报告。

固定 OpenMinis 的[AgentJobRegistry.finish](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/agent/jobs/AgentJobRegistry.kt#L546)走统一 job 完成链并在终态后 drainQueuedDelegations；[MinisApp 回执 dispatcher](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L499)通过独立 coroutine 投递。该对照再次支持 F24 的职责分离方向，但不能据此断言本轮新增的五项在上游全部已修复。

上游有 `agent/jobs/HelperRunner.kt` 与 `AgentJobRegistry.kt`，没有本地这套 `agent/subagents/AppSubAgentPort.kt`、`SubAgentRuntime.kt`、`SubAgentJobRegistry.kt`、`PrefsSubAgentJobStore.kt`。名称和总体委派能力相似，不代表等待、持久化和回执代码相同。这里未将上游架构整体替换本地实现，也没有把注释当实现事实。

当前保护也已核对：状态变更在 registry 锁内，终态首次写入胜出；运行数/队列数有全局上限；job lookup 受 parentSessionId 限制；真实 tool handler 检查 child source 并拒绝嵌套委派；用户明确 pin 的模型不可用时拒绝，不随意改为另一模型。它们不替代本报告指出的等待通知、完整 spec 和最终名字边界。

## 阅读范围与完整性

本轮新增全文阅读 17 个文件、3,112 行；其中 AppSubAgentPort 从 partial 补完为 full，其余 16 个原为未登记：

| 文件 | 行数 |
|---|---:|
| [AppSubAgentPort.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt:1) | 219 |
| [PrefsSubAgentJobStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/PrefsSubAgentJobStore.kt:1) | 43 |
| [SubAgentJobRegistry.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentJobRegistry.kt:1) | 303 |
| [SubAgentRuntime.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt:1) | 342 |
| [SubAgentStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentStore.kt:1) | 69 |
| [SubAgentTask.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentTask.kt:1) | 266 |
| [SubAgentToolSchema.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentToolSchema.kt:1) | 42 |
| [PrefsSubAgentJobStoreTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/PrefsSubAgentJobStoreTest.kt:1) | 35 |
| [SubAgentJobRegistryTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentJobRegistryTest.kt:1) | 191 |
| [SubAgentRosterTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRosterTest.kt:1) | 108 |
| [SubAgentRuntimeTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt:1) | 565 |
| [SubAgentStoreTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentStoreTest.kt:1) | 49 |
| [SubAgentTaskTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentTaskTest.kt:1) | 142 |
| [SubAgentDefinition.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt:1) | 252 |
| [SubAgentsScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SubAgentsScreen.kt:1) | 309 |
| [BackupSubAgents.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupSubAgents.kt:1) | 89 |
| [BackupSubAgentsTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupSubAgentsTest.kt:1) | 88 |

BackupImporter 的 899–925 行补入 partial 范围。AgentRunner、ToolRegistry 及 ChatViewModel 的相关调用片段核对当前版本，复读部分不重复计数。初次 SubAgentToolSchema 输出中的局部截断已补读。

累计 tracked 1,670，纳入范围 1,546，排除 124；full 894、partial 13、未登记 639；全文阅读行数 185,761。测试只作为源码证据，未执行，也未新增测试。

所有 tracked 路径、SHA256、字节数及有文本行数记录的行数均重新核验；HEAD 保持固定版本，tracked/staged diff 为空。此前 57 份报告哈希不变。新报告本地链接、行号和固定上游对象在保存后核验。
