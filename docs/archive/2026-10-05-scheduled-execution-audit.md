# 定时任务与无人值守执行静态审计

审计日期：2026-10-05。当前提交：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。固定 openminis 对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`（`/tmp/openminis-review-20261004`）。

本轮新增 9 项发现。只创建本报告、更新阅读台账；未修改产品代码和旧报告，未运行构建、测试、模拟器、设备、Root 或云任务。以下触发与后果是源码及平台文档的静态推导，未做运行复现。P1 表示只读执行边界存在可达缺口；P2 表示权限生命周期、任务恢复、身份或调度错误。

| 编号 | 优先级 | 问题 |
|---|---|---|
| SC01 | P1 | READ_ONLY 允许 `uniq` 通过位置参数写普通工作区文件 |
| SC02 | P1 | 白名单命令在原持久 shell 中解析，可被函数或 PATH 中的程序替代 |
| SC03 | P2 | 执行尚未确认退出，就撤掉定时任务的只读和无人值守状态 |
| SC04 | P2 | 一次性任务先禁用、后交接，交接中断后没有持久恢复入口 |
| SC05 | P2 | RPC 可保存归属 Bot 与目标会话 Bot 不一致的定时任务 |
| SC06 | P2 | 修改时区没有重排闹钟，本地时间预览与实际触发可不一致 |
| SC07 | P2 | 日期编辑器把本地午夜传给 UTC 日期 API，再次确认可提前一天 |
| SC08 | P2 | 编辑器和中央管理器允许保存已启用但没有有效触发日期的任务 |
| SC09 | P2 | Bot 同步运行自己的另一会话定时任务，会与自己的回合锁相互等待 |

## SC01 — 命令名称只读，不代表所有参数只读

[ScheduledReadOnlyPolicy.kt:21](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledReadOnlyPolicy.kt:21)把 `uniq` 放进只读白名单。[参数检查](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledReadOnlyPolicy.kt:247)对 sort、date、find、sed、git 做额外限制，但没有检查 uniq 的第二个位置参数。GNU 明确规定 `uniq [option]... [input [output]]`，第二个文件操作数就是输出文件。[GNU uniq 文档](https://www.gnu.org/s/coreutils/manual/html_node/uniq-invocation.html)

因此 `uniq /workspace/input.txt /workspace/output.txt` 可通过这套策略，却会写入普通工作区。它不使用 `>`，不会进入 [redirectPaths 检查](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:225)。随后 [LinuxShellHandler](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:251)只对 Git 做命令加固，其他命令按原字符串执行。

这违反只允许向对应 session 临时/offload 目录写入的 READ_ONLY 约束。影响是 guest App UID 可写的数据，不是 Android Root 提权。现有 ScheduledReadOnlyPolicyTest 全文没有覆盖 uniq 的输出操作数。修复方向：按实际命令语法约束写入参数；只校验重定向不足以建立只读边界。

## SC02 — 策略检查的名称与实际运行程序没有绑定

[ExecutionCoordinator.kt:101](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt:101)复用对应会话的 shell，并在 [108 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ExecutionCoordinator.kt:108)注入用户环境变量。[RootPersistentShell.kt:181](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/RootPersistentShell.kt:181)直接把原命令写入同一个 Bash，没有为只读执行重置函数、命令查找状态或限定程序路径。Bash 对无斜杠名称先找函数，再找内建命令，之后才查 PATH。[GNU Bash 命令查找规则](https://www.gnu.org/software/bash/manual/html_node/Command-Search-and-Execution.html)

明确前提：同一会话此前获准的普通/FULL 回合定义了名为 `ls` 的 shell 函数，或配置的 PATH 中存在同名程序。后续 READ_ONLY 的 APPEND_TO 任务提交 `ls` 时，名称检查允许，但实际执行的是已存在的函数或替代程序，其动作不受这一字符串白名单约束。任务本轮不需要提交函数定义即可触发。Git 加固仍通过未限定路径的 `git` 调用，不能单靠 Git 参数消除命令解析差异。

这是保留 shell 状态与新增只读策略组合后的边界缺口，不意味着默认干净会话执行 ls 就会修改数据。修复方向：让只读执行绑定可信程序及受控环境，避开先前会话的函数/程序替换；同时保留正确 session workspace 语义。

## SC03 — 返回超时不等于执行已经退出

[HeadlessChatRunner.kt:213](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:213)在等待超时后请求取消，只再等待 5 秒，仍可返回 `streamExited=false`。retry 路径的 [310 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt:310)也单独报告是否真正退出。

然而 [ScheduledAgentRunner.kt:150](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:150)和 [170 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:170)都在 dispatch 返回时退出 withUnattendedSession，不读取 `streamExited`。[OffloadPermissionManager.kt:581](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt:581)的 finally 随即减少档位与无人值守计数；没有其他活动计数时，tierFor 返回 null。

可达状态是：底层退出尚未确认，但它对应的定时权限已撤销。延迟取消或清理中的后续检查不能再依赖这个档位。源码足以确认生命周期不匹配；本轮不声称普通取消路径必然继续发新工具，也不声称已复现系统授权绕过。修复方向：把权限 scope 绑定实际执行句柄的终止，清理期间继续保留约束，取消等待超时单独记录。

## SC04 — 一次性任务存在禁用后尚未交接的丢失窗口

[ScheduledTaskAlarmReceiver.kt:62](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskAlarmReceiver.kt:62)先调用 rescheduleNext。[ScheduledTaskManager.kt:140](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:140)遇到 ONCE 就持久写成 enabled=false。然后才进入 [ScheduledAgentRunner 的会话解析](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:139)，并在 [168 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:168)启动内存中的 bgScope 作业。

禁用之后、解析/启动完成之前，如果广播交接超时或进程死亡，既没有待执行记录，也没有运行记录。异常分支 [111 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskAlarmReceiver.kt:111)只写日志；重启重排 [125 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:125)跳过禁用任务。因此一次性任务可能直接消失在执行交接阶段。

修复方向：先持久保存一次触发的执行身份和待交接状态，再安排进程内工作；恢复时区分未启动、已经启动和结果未知。不能通过让广播等待整个 Agent 回合来消除窗口。这个问题独立于旧报告的 JSON 写入竞争。

## SC05 — 归属身份校验只存在于部分入口

原生编辑器 [232 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTaskEditScreen.kt:232)要求选中的 Bot 与跟进/重跑目标会话相符；CLI [152 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ScheduledTaskOffloadHandler.kt:152)也检查目标 Bot。

RPC 的 [targetMode 解析](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ScheduledTaskRpcMethods.kt:143)只检查语法，[botId 检查](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ScheduledTaskRpcMethods.kt:170)只检查 Bot 存在。中央 create/update [43 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:43)只验证档位与数量。执行时 [resolveSessionId](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:256)确认目标会话存在，却不核对 Bot 身份。

因此 RPC 可保存 botId=A、APPEND_TO 或 RERUN 指向 Bot B 会话的任务。启动前按 A 的存在和启用状态判断，列表/数量/删除按 A 归属，实际回合则通过目标会话的 B 身份运行（[ChatViewModel.kt:4210](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4210)）。这造成身份与管理行为不一致；不将其扩大成跨账号访问漏洞。修复方向：保存与执行两个边界统一核对归属，避免校验只依赖编辑器和 CLI。

## SC06 — 时区变化只更新 guest，没有重算日历任务

[nextTriggerMs](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTask.kt:216)按当前本地时区计算“每天 09:00”。[registerAlarm](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:174)用 RTC_WAKEUP 保存绝对触发毫秒。[Android AlarmManager 文档](https://developer.android.com/reference/android/app/AlarmManager)将这一时钟定义为基于 UTC 墙钟的时间。

应用唯一的时区广播处理 [MinisApp.kt:1090](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/MinisApp.kt:1090)只调用 broadcastTimezoneChange 更新执行环境，没有 rescheduleAll；全文搜索中的重排调用只有开机和备份导入。Manifest 也没有定时任务时区重排入口。

例：UTC+8 下注册次日 09:00，然后改成 UTC+9；旧闹钟仍在原绝对时刻触发，相当于新时区 10:00。页面再计算的下一次时间却按新时区 09:00 展示。修复方向：接收日历/时区变化并统一重算仍启用的任务，预览与已注册触发时刻须一致。设备系统调度细节未实测。

## SC07 — 日期选择器的输入输出转换不对称

[DateDialog:954](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTaskEditScreen.kt:954)把保存的 initialMs 直接交给 rememberDatePickerState；确认时 [959 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTaskEditScreen.kt:959)却将 UTC 午夜转换成对应日期的本地午夜后保存。[Material3 DatePicker API](https://developer.android.com/reference/kotlin/androidx/compose/material3/package-summary)明确日期状态使用 UTC 日期，初始毫秒也按 UTC 日期解释。

例如 UTC+8 的 2026-10-05 本地午夜对应 UTC 的 10 月 4 日 16:00。重新打开已有日期时会按 10 月 4 日初始化；不修改而直接确认，就保存成本地 10 月 4 日，任务起止日期提前一天。页面外的 dateLabel 按本地时间显示，不能修正弹窗内部的解释。修复方向：打开选择器时把本地年月日转换为同年月日的 UTC 午夜，与保存转换配对。

固定 openminis 的 ScheduledTaskEditScreen 同样直接传入 initialMs，并在保存时转换本地午夜：这是继承的问题，而非 Android 本轮新增逻辑。

## SC08 — 保存成功但从来不会获得闹钟

[canSave:234](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTaskEditScreen.kt:234)只检查目标、Bot 身份和 prompt，没有 CUSTOM 至少一天、起止日期顺序的校验。用户可以把自定义星期全部取消，或选择结束日期早于开始日期。中央管理器 [51 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:51)先保存，再注册；它也没有对应校验。

[nextTriggerMs:240](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTask.kt:240)对空 CUSTOM 返回 null；候选日期越过结束日期时 [262 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTask.kt:262)同样返回 null。[registerAlarm:168](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt:168)只记日志然后退出，调用方仍得到保存成功。结果是 enabled=true 的任务没有下一次触发，也没有明确错误状态。

RPC 已有 [CUSTOM 校验](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ScheduledTaskRpcMethods.kt:136)和 [日期顺序校验](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/debug/ScheduledTaskRpcMethods.kt:161)，说明现有入口行为不一致。修复方向：在管理器维护共同调度约束；对启用且无未来时段的任务返回明确状态，编辑器提前解释原因。禁用草稿是否允许不完整配置可单独定义。

## SC09 — 同步 CLI 回调形成同一 Bot 的锁等待环

[ChatViewModel.kt:6885](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:6885)在整个 Agent loop 外持有 Bot 回合锁。[BotTurnLockRegistry.kt:19](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/BotTurnLockRegistry.kt:19)串行化同 Bot 的不同会话。

明确触发：Bot A 的普通/FULL 回合通过 linux.shell 调用 `minis-scheduled run --id <自己的任务>`，任务目标为 NEW_SESSION（或 A 的另一会话）。[GuestCommandBridge.kt:233](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:233)同步调用 handler；[handleRun:192](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ScheduledTaskOffloadHandler.kt:192)用 runBlocking 等待 ScheduledAgentRunner 完成。新会话 [319 行](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt:319)仍绑定 Bot A，因此新回合等待同一个锁。

父回合持锁并等 shell 返回；shell 等 CLI 完成；CLI 等新回合完成；新回合等父回合释放锁。[桥接协议](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/GuestCommandBridge.kt:342)也明确客户端等结果才退出。没有正常完成路径，直到某层超时或用户取消。具体持续时间取决于工具和调度预算，不断言总是十分钟。READ_ONLY 本身不允许这个 CLI，因此不把它列为只读绕过。

修复方向：这种嵌套入口异步提交并返回持久执行身份，或明确拒绝同 Bot 的同步嵌套等待。旧 F24 记录的是子 Agent 取消与父回合等待；本项是不同的定时 CLI 生产入口，记录其独立触发链。

## 对照与去重

- openminis 参考只用于确认来源和设计差异，不覆盖当前源码事实。日期弹窗问题为继承；READ_ONLY 策略及本地 Bot 归属/锁问题需要按当前新增边界分析。
- 上游 alarm receiver 的条件重排返回值包含相对触发的防重复处理，不能据此宣称日历 ONCE 的持久交接已解决。
- 已有 F18 的重跑全局流状态、F21 的 Busy/Cancelled 成功误记、N04 的任务 JSON/旧编辑器覆盖、N05 的 PendingIntent ID 碰撞，本轮核对到但不再计入新增数。
- FULL“立即运行”的确认语义没有在本轮认定为新漏洞；合同对新增/提权的限制与已有 FULL 任务手动运行需区分。跨会话委派不继承定时档位是明确合同，不记为缺陷。

## 阅读与验证范围

本轮新增全文登记 28 个文件、5,976 行：定时模型/策略/存储/管理/广播/执行、CLI/RPC、编辑器和列表、现有相关测试，通知存储/接收器、开机接收器、HeadlessChatRunner 和工具超时策略。HeadlessChatRunner 复用此前已读且 SHA 未变的 1–265 行，补齐其余全文后升级登记；全库完整阅读行数按完整文件计算。另全文复核 BotTurnLockRegistry 48 行。仅片段复核 ChatViewModel、MinisApp 等，不把它们计作全文。

累计全文 460 / 1,546 个范围内文件，101,323 行；19 个片段阅读，1,067 个尚未登记。tracked 总数 1,670，排除一般文档/二进制/批量数据 124。逐文件状态、SHA 和范围见[阅读台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。这不是全库审计完成声明。

待授权后的端到端验证应留下可重复的任务记录、拒绝日志、实际文件状态和调度时间证据，覆盖只读输出操作数、持久 shell 替换、延迟退出、ONCE 交接中断恢复、身份不匹配拒绝、时区变化、日期往返、无有效时段保存以及同 Bot 嵌套调用。本轮没有执行这些验证。
