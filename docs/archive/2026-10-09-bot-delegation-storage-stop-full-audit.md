# Bot委派存储与停止领取：现版全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读三个文件，未运行数据库、测试或真实委派；仅新增报告和台账，没有新增已确认缺陷。

## 已落实的持久停止保护

BotDelegationDao.claim 在同一个 UPDATE 中要求 source_turn_settled=1、状态为 QUEUED/WAITING_TARGET，并通过 NOT EXISTS 拒绝根任务处于 PAUSED/CANCELLED/FAILED/BUDGET_EXHAUSTED/COMPLETED。因此不能再把“派发器先查根状态后领取之间完全没有数据库保护”作为当前事实。该语句保护领取发生前的停止，不代表已经 RUNNING 的进程已经退出。

BotDelegationStopTest 使用独立 in-memory Room 库，断言活动任务可领取、五种停止状态拒绝、无根任务仍可领取、派发器检查后停止再领取拒绝，以及非终态成员枚举。它以顺序调用构造窗口，没有实际两个并行数据库事务，也没有启动／停止真实Agent执行；不声称全部取消与副作用已经端到端验证。测试 SQL 状态集与 coordinator 常量相等断言是有用的一致性保护。

## 仓库与状态边界

enqueue 去掉空白、限制prompt12000字符、限定depth=1；有sourceToolId时先查询并在insert conflict后查回，避免只依赖先查的竞态窗口。实际唯一约束仍应由实体/迁移单独核对，本轮不据局部仓库宣布所有幂等已证明。

分页按 created_at/id 二元游标、limit限制1—100，查询派发集合与未投递终态集合分别明确状态。markWaitingTarget、claim、finish、cancel具有源状态条件，返回更新行数供上层判定；终态不会被普通finish再次覆盖。结果与错误分别限制24000和2000字符，截断行为明确，不能等同完整结果存档。

markDelivered只检查 delivered_at IS NULL，没有终态条件；这需要上层严格使用终态投递集合，但仅凭DAO可接受其他行不能判真实提前投递缺陷。claim 对非空但不存在的root_task_id也通过 NOT EXISTS；是否允许孤立根引用需结合写入验证、根删除和恢复调用方，不新增绕过结论。

listNonTerminalForRootTask只按created_at排序，同时间行顺序不保证；测试同时间插入后期待 q/r 排序不能证明跨数据库计划稳定。分页接口另外加id排序，不把二者混为一个缺陷。

## 待继续追踪

数据库领取拒绝后上层如何处置、运行中停止如何等待执行终止、投递确认与父消息持久化如何协调，仍需按 coordinator 实际调用链复核。DAO原子状态保护不能替代这些运行时证据。本轮不重复登记历史Bot协调缺陷，也不宣布全族问题已修复。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/main/java/com/openminis/app/data/db/BotDelegationDao.kt | 100 |
| src/android/app/src/main/java/com/openminis/app/data/repository/BotDelegationRepository.kt | 145 |
| src/android/app/src/androidTest/java/com/openminis/app/data/db/BotDelegationStopTest.kt | 94 |

三个现版文件完整读取。新增全文3文件、339行；已全文重读0文件。其它rg定位不计全文。

累计全文1372 / 1758文件，209513行；partial 4，未登记382。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-delegation-storage.json)。

校验旧228报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
