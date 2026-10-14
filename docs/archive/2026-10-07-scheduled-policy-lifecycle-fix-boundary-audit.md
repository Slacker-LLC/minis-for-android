# 定时任务只读策略、启用与执行生命周期修复复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文6文件、1386行；没有新增缺陷编号。复核旧SC01/03/04/06/07/08，确认修复及残余路径。没有改产品代码、编写或执行测试、构建、模拟器、adb、Root、设备或云任务。此前158份报告与所有旧台账保留，另建本报告。

## 修复状态

| 旧项 | 当前源码结论 |
|---|---|
| SC01 uniq输出文件位置参数 | 原反例已被拒绝，新增操作数计数及否定用例 |
| SC03 权限scope提前退出 | 仍未依据streamExited保持scope |
| SC04 ONCE禁用后丢失交接 | 原先禁用再内存交接顺序仍在 |
| SC06 时区改变未重排 | 现役时区receiver已调用rescheduleAll，原入口已修复 |
| SC07 日期选择提前一天 | DateDialog现已调用本地日期到UTC午夜的逆转换 |
| SC08 enabled但无有效触发 | create/update已校验；setEnabled仍绕过，属于部分修复 |

静态检查不代表Android真实闹钟、进程恢复或端到端执行验收。其它旧编号未在本轮完整闭环，不泛称全部修复。

## SC08残余：禁用草稿可以从启用入口进入无触发状态

[ScheduledTaskPolicy.kt:12](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskPolicy.kt#L12) schedulingProblem首先允许disabled草稿；enabled时拒绝CUSTOM空星期、结束早于开始、nextTriggerMs为空。[ScheduledTaskManager.kt:50](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt#L50) create和第77行update已调用它。

但[ScheduledTaskManager.kt:85](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt#L85) setEnabled只get→copy(enabled)→upsert→registerAlarm，没有校验。可达例：保存disabled且CUSTOM空星期的草稿，随后从RPC toggle或CLI enable开启；[ScheduledTaskRpcMethods.kt:82](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/ScheduledTaskRpcMethods.kt#L82) toggle调用此方法，返回ok=true；[ScheduledTaskOffloadHandler.kt:181](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/guest/ScheduledTaskOffloadHandler.kt#L181) CLI同样返回exit0、enabled=true。registerAlarm因nextTriggerMs为空只记日志退出。仍产生enabled=true但不会触发的行。这是旧SC08的其它入口，不重复编号。

已有ScheduledTaskTimingTest明确验证disabled草稿允许不完整配置，因此不能以“这类数据不会保存”排除此路径。测试仅验证纯schedulingProblem，没有调用manager.setEnabled或RPC/CLI enable。本轮未执行。

Manager新增MUTATION_LOCK保护create/update/delete/deleteAllForBot；setEnabled、markFired、rescheduleNext仍是读取整行再回写，未同锁。旧N04的管理并发/旧值覆盖类问题不能只因部分入口加锁就宣布全解决。此处不另增重复问题，也未静态捏造实际竞争发生频率。

## SC03与SC04仍需处理的执行生命周期

[ScheduledAgentRunner.kt:150](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt#L150) 同步dispatch和第172行后台dispatch均放在withUnattendedSession内；返回后只取responseText/completed，不取streamExited。[HeadlessChatRunner.kt:217](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/debug/HeadlessChatRunner.kt#L217) prompt超时取消后最多再等5秒，仍允许streamExited=false返回。[OffloadPermissionManager.kt:581](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/OffloadPermissionManager.kt#L581) finally随即撤销tier与unattended计数。权限scope与实际退出仍未绑定，旧SC03保持；不声称已复现超时后执行新工具或越权。

[ScheduledAgentRunner.kt:239](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt#L239) dispatch使用runCatching，将包括异常在内转成Error PromptResult；协程取消传播语义需要更完整调用者核验。本轮不将catch广度另计成真实重复执行。

[ScheduledTaskAlarmReceiver.kt:62](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskAlarmReceiver.kt#L62) 仍先rescheduleNext；[ScheduledTaskManager.kt:144](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt#L144) ONCE先持久enabled=false，再会话解析及bgScope.launch。因此进程在中间消失仍没有本路径的持久交接记录，旧SC04尚在。Bot disabled分支会再enable，只处理该明确返回值；不能覆盖进程死亡窗口。

Runner已不预启动FGS，注释明确由真实ChatViewModel active-turn拥有。不能继续套用receiver旧注释“run返回前已经启动服务”推导唤醒锁已持有。后台分支返回sessionId时dispatch协程可能尚未执行；RunOutcome.started只表示sessionId非空，Busy/Cancelled/Error仍可能随后写失败runHistory。与旧F21相比，本轮记录使用result.completed，不能再声称任意非timeout状态都计成功。

## 只读策略与修复测试

[ScheduledReadOnlyPolicy.kt:266](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledReadOnlyPolicy.kt#L266) uniq的第二文件操作数被拒绝；uniqPositionals处理-f/-s/-w单独参数值、--选项结束及普通文件操作数。测试覆盖原uniq in.txt out.txt及带-c/-f/--，同时保留单输入和管道。仅静态检查，不运行Shell。

策略还限制8192字符、128段、控制字节/混淆符，拒绝替换/反引号/反斜杠/输入重定向；输出路径只能是/tmp或/var/minis/offloads下的字面规范路径。词法限制与真实session路径/符号链接检查是不同层，本轮未全文再审SecureFileAccess，不凭纯策略宣布所有文件路径安全。

hardenGitInvocation禁用若干Git helper和配置源，并引用参数/重定向；它仍使用名为git的命令。旧SC02的函数/PATH替代问题并不由这些Git选项单独消除，本轮不重复编号，也未重新完整验证全部shell调用方。原文quote到token的处理会丢失引号来源，一些字面操作符参数可能被当语法标记，暂留候选，不强行扩成新写入绕过。

## 时间修复的范围

[MinisApp.kt:1101](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L1101) 时区receiver在更新guest时区之外独立try调用ScheduledTaskManager.rescheduleAll；guest更新失败也不阻断重排，旧SC06该入口已修复。应用退出、广播注册/OEM投递和实际RTC触发未在本轮验证。

[ScheduledTaskEditScreen.kt:955](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/scheduled/ScheduledTaskEditScreen.kt#L955) DateDialog把本地日期转换为同年月日UTC午夜，再由startOfLocalDay转换保存。ScheduledTaskTimingTest读取生产两个helper，在UTC、上海、洛杉矶、Kiritimati、Pago_Pago五时区验证日期及往返；不是仅在测试里重复公式。尚未运行Compose弹窗，不声称界面/闹钟端到端验收。

ScheduledReadOnlyPolicyTest还包含同文件的permission-tier JSON用例：新建默认READ_ONLY，缺字段的历史数据保留FULL，格式错误/未知字段回落READ_ONLY。历史FULL是显式兼容策略，不能仅凭缺字段行为新增一项越权报告。测试不覆盖真实工具权限scope的终止时序。

## OpenMinis对照

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，GIT_NO_LAZY_FETCH=1，只读现有对象。ScheduledTaskManager相关片段有relative/on-completion trigger、anchor及update合并bookkeeping，产品数据模型与本地不同；不能直接照搬来解决本轮scope或启用校验。上游setEnabled也有特定trigger重置分支，未据此认定当前本地SC08反例已被上游解决。

## 全文覆盖

| 本轮新增全文 | 行数 |
|---|---:|
| [ScheduledAgentRunner.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledAgentRunner.kt#L1) | 370 |
| [ScheduledReadOnlyPolicy.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledReadOnlyPolicy.kt#L1) | 418 |
| [ScheduledTaskManager.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskManager.kt#L1) | 229 |
| [ScheduledTaskPolicy.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/scheduled/ScheduledTaskPolicy.kt#L1) | 79 |
| [ScheduledReadOnlyPolicyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/scheduled/ScheduledReadOnlyPolicyTest.kt#L1) | 193 |
| [ScheduledTaskTimingTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/scheduled/ScheduledTaskTimingTest.kt#L1) | 97 |

Runner分1—220/221—370，ReadOnlyPolicy分1—225/226—418，Manager/TaskPolicy和两个测试全文读取。初次合并输出截断均已独立补读；截断旧报告搜索不计覆盖。调用方只相关片段，不升级全文。

新台账：[2026-10-07-code-review-coverage-37158d18-scheduled-policy.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-scheduled-policy.json)。累计全文 **1242 / 1652文件、203123行**；partial4、not_registered406、excluded124，tracked1776。全库通读未完成。

交付核验此前158份报告及所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines与证据行号。仅新增本文和新版台账。
