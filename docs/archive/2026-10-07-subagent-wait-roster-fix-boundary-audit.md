# 子 Agent 同步等待、恢复与名册修复边界复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文4文件、1392行；本轮不新增缺陷编号，复核已有SA01—SA05及F24。仅静态读代码和已有测试，没有修改产品代码、编写/执行测试、构建、模拟器、adb、Root、设备或云任务，没有启动子Agent。此前156份报告和旧台账全部保留，留给Claude处理。

## 远端修复状态

| 旧项 | 本轮结论 | 当前证据 |
|---|---|---|
| SA01 同步任务重启后没有结果接收方 | 仍存在 | resume保留wait=true；恢复不建立waiter；complete仍仅为wait=false投递 |
| SA02 排队任务发布早于waiter注册 | 仍存在 | submit发布之后才执行Queued分支waiterFor；complete只尝试一次remove/complete |
| SA03 完整brief持久化截断 | 新提交入口已修复 | 组装完整brief后、submit之前拒绝超过20000字符的任务 |
| SA04 进度只读落库消息 | 仍存在 | AppSubAgentPort.describeChild仍读DAO，没有读取正在运行的工具/流式文本 |
| SA05 截短后重名 | 原长名字反例已修复 | normalize和新ID备份合并均以clamped名字去重 |
| F24 回执等待堵塞取消/晋升 | 本runtime原顺序缺口已修复 | 先完成waiter、晋升队列，再独立协程投递回执 |

这些是源码结论，不是设备运行验收。SA03不追溯修复旧版本已经截短的存量brief；SA05不代表整个备份计数或所有名册异常输入均已验收。F24结论限定本runtime，不扩张到所有后台调度入口。

## 三项未闭环的旧问题

SA01：[SubAgentJobRegistry.kt:242](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentJobRegistry.kt#L242) 两个resume分支仅改state/time/resumed，没有更改wait。[SubAgentRuntime.kt:348](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L348) 恢复Started直接launch，Queued也不注册等待者。[SubAgentRuntime.kt:250](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L250) complete只有waiters.remove的一次通知，[SubAgentRuntime.kt:252](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L252) 仍使用!done.wait判断是否投递。重启前的同步调用和deferred已经消失，旧wait=true完成后仍无新的接收方，尽管resume第356行承诺新消息回执。status能查终态不等于主动结果通知。RuntimeTest的startAndLose默认wait=false，恢复测试仍未覆盖此反例。

SA02：[SubAgentRuntime.kt:131](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L131) submit先将job发布；[SubAgentRuntime.kt:152](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L152) 才为Queued注册waiter，[SubAgentRuntime.kt:159](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L159) 盲建deferred，没有终态补发。另一worker可在这个窗口晋升并快速结束该排队任务，第250行找不到waiter；随后注册的await只能等外部取消。新的complete顺序没有把发布与注册变成原子操作，因此原SA02时序仍成立。RuntimeTest立即启动wait=true与后台队列分别覆盖，并不证明排队同步任务不会漏通知。

SA04：[AppSubAgentPort.kt:93](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/AppSubAgentPort.kt#L93) describeChild仍从loadMessages获取最后assistant及历史result IDs。[SubAgentRuntime.kt:233](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L233) ticker消费这份投影；第一项长工具尚未提交assistant时，无法显示当前工具。测试手动设置FakePort.progress，只验证ticker去重/格式，不验证真实AppSubAgentPort如何取得live状态。此处相关持久化/loop事实沿用旧SA04完整分析，本轮未重新全文读取ChatViewModel，不重复增加覆盖。

## 两处确认改善与测试范围

[SubAgentRuntime.kt:119](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L119) brief先由真实instructions、task、context组装，[SubAgentRuntime.kt:123](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L123) 用持久层MAX_STORED_BRIEF_CHARS拒绝过长说明，尚未进入registry.submit。RuntimeTest已有brief_too_long否定用例，断言job与child均为空。这里只读，未执行。拒绝依据是最终brief长度，不只task原字段长度。

[SubAgentDefinition.kt:189](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt#L189) normalize先clamped再nameKey去重；[SubAgentDefinition.kt:246](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt#L246) 新ID合并也比较最终存储名。RosterTest新增长名字碰撞、normalize幂等、合并written/skipped三项，覆盖原SA05两个41字符名字反例。nameKey还使用NFD去组合标记及lowercase，使解析与去重采用一致形式。

**SA05修复结论的边界：** [SubAgentDefinition.kt:242](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt#L242) 同ID且较新分支直接替换并written++，不检查新名字与其它ID是否冲突；[SubAgentDefinition.kt:255](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt#L255) 最后normalize可能把重名项丢掉。另有新增超过名册容量时先written++后normalize裁切的情况。已有测试标题说“counts only what survives”，实际只测试两个新ID的截短碰撞，不能推及这些分支。比如本地不同ID的alpha/beta，较新备份把beta改名alpha；合并written计1而normalize最终只保留同名中的一项。导入入口[BackupImporter.kt:1021](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt#L1021) 仍累计merged.written。这属于旧SA05“归一化与导入报告不一致”的残余边界，不再新编号；不声称正常编辑器操作一定触发。

## 回执与生命周期

[SubAgentRuntime.kt:247](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L247) complete已先registry.finish、清cancel标记、唤醒waiter、promoteNext，再deliverInOrder。RuntimeTest新增“父会话忙时排队取消及时返回”和“完成先释放槽位”两项，FakePort.parentBusy阻塞投递，分别断言取消返回和下一child启动。它们针对旧F24原根因，源码实现相符，未运行测试。

[SubAgentRuntime.kt:258](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L258) 每父会话Mutex串行投递，但协程launch后才竞争锁。串行性不能单独证明严格按registry.finish顺序送达：A晋升B后才安排自身投递，B可先运行完并竞争锁。当前不把注释宣称的顺序当验收证据；顺序语义、取消scope后回执投递及deliveryLocks清理留作后续核对，不强行增加缺陷。进度投递直接调用port，没有经过此Mutex，也不能据此宣称所有消息全序。

取消链读取到requestCancel与launch之间仍存在时序窗口，旧F20相关问题不重复编号。runtime不等于实际child engine；没有把FakePort.cancelChild成功当作真实工具/进程已停止的证明。

## OpenMinis对照

固定上游HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`；GIT_NO_LAZY_FETCH=1，仅现有Git对象。树中是agent/jobs/HelperRunner与AgentJobRegistry，不存在本地agent/subagents这套runtime/store/registry路径。数据model/SubAgentDefinition的normalize相关片段仍先对custom数量限额；不能据此认定本地同步等待通知或本轮全部残余问题在上游已解决。此前上游独立回执dispatcher的证据保留在旧报告，本轮没有重复全文读取该大文件。当前F24以本仓新complete实现为准，不建议直接搬上游架构。

## 全文覆盖与文件保留

| 本轮新增全文 | 行数 |
|---|---:|
| [SubAgentRuntime.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/agent/subagents/SubAgentRuntime.kt#L1) | 369 |
| [SubAgentDefinition.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/model/SubAgentDefinition.kt#L1) | 257 |
| [SubAgentRosterTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRosterTest.kt#L1) | 144 |
| [SubAgentRuntimeTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/agent/subagents/SubAgentRuntimeTest.kt#L1) | 622 |

Runtime分1—195/196—369、Definition分1—130/131—257，RuntimeTest分1—220/221—430/431—622，RosterTest全文144行，均完整无截断。Registry、AppSubAgentPort、PrefsStore、BackupImporter仅核对相关片段，已有full不重复计数。

新台账：[2026-10-07-code-review-coverage-37158d18-subagent-fixes.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-subagent-fixes.json)。累计全文 **1230 / 1652文件、200618行**；partial4、not_registered418、excluded124，tracked1776。全库通读尚未完成。

交付校验此前156份报告与所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、1776快照blob/hash/bytes/lines及固定证据行号。只新建本报告与本轮台账，不修改任何旧文件。
