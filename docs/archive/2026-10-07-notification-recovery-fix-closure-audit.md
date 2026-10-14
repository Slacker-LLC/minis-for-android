# 通知恢复、取消与历史保留的远端复核

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文10文件、1326行；复核已有编号，不增加重复问题。仅静态阅读，没有改产品代码、编写/运行测试、发出通知、读真实通知数据库、设备授权、构建、模拟器、adb、Root或云任务。此前144份报告与所有旧台账保持原样。

## 当前修复状态

编号仅引用[通知与闹钟静态审计](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-notification-alarm-audit.md)，与另一份日历报告中的ND编号无关。

| 原编号 | 固定源码结论 |
|---|---|
| ND03 过期条目拖住整批迁移 | 原批次计数问题已修，迁移只持久化失败条目 |
| ND04 延迟通知没有开机恢复 | 已有真实开机调用与未来/刚到期/过久三类恢复策略 |
| ND05 pending删除后按ID无法取消 | cancel --id已不依赖entry存在；一小时grace也避免稍迟即清理 |
| ND06 注册表读改写互相覆盖 | 原Store读改写已加所有实例共享锁；OS与Store协调仍有残留 |
| ND07 同ID追加产生假待办 | 顺序更新同ID已替换记录、remove删除所有同ID项 |
| ND09 无新通知就无法清过期历史 | search/count新增purge；长期无读写或撤销授权时仍无定时清理 |
| ND01/ND02 旧迁移丢重复模式/不撤旧动作 | 所读现役迁移提交仍未处理，不因新分批helper就宣布修好 |

这里“已修”限定旧静态触发链。没有执行设备恢复、权限拒绝或系统Clock验收。

## ND03与旧迁移残留

[GhostAlarmMigration.kt:22](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/GhostAlarmMigration.kt#L22) GhostAlarmMigration.run逐条区分expired、submit成功、失败；只有失败进入remaining。[MinisApp.kt:1169](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L1169) 真实启动迁移使用这个helper，并仅持久化remaining。旧“一个过期项使所有成功项每次启动重交”的分支已去掉。测试调用生产helper，覆盖部分失败/throw、过期一次性与重复仍有效、第二次只处理剩余项。

但[MinisApp.kt:1190](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/MinisApp.kt#L1190) submitGhostAlarm构造SET_ALARM仍只带小时、分钟、message，未带repeatMode对应EXTRA_DAYS；也没有撤销原AlarmOffloadManager token。AlarmReceiver非boot分支即使原记录已没有，仍构造并post旧提醒。ND01/ND02按原条件继续存在，未另计新项。submit成功仅表示startActivity返回，Clock实际创建和后台启动行为仍需真机证据；不能用假的submit lambda成功证明系统交接完成。

## ND04、ND05、ND07的现役链

[AlarmReceiver.kt:60](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/AlarmReceiver.kt#L60) AlarmReceiver在BOOT_COMPLETED分支调用ScheduledNotificationReceiver.restoreAfterBoot，Manifest第510行receiver有BOOT_COMPLETED filter且不export。[ScheduledNotificationReceiver.kt:124](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationReceiver.kt#L124) 未来记录重新setExactAndAllowWhileIdle，已到期但不超过一小时的立即deliver，过久的删除。SecurityException只日志记录，future entry保留，不宣称系统拒绝时已经恢复。

[NotificationOffloadHandler.kt:291](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt#L291) cancel --id无论store.get是否为空都按同一requestCodeFor(id)撤销AlarmManager和PendingIntent，再移除可找到的entry及已显示notification。稍迟到不会立即sweep：Store.isLost采用一小时grace。旧按ID“查过pending就无法取消”链已修；取消已经进入接收/投递过程的广播仍需另外的状态协调，receiver不检查取消标记，不能声称所有取消竞态已解决。

[ScheduledNotificationStore.kt:28](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationStore.kt#L28) add在共享LOCK内删除同ID旧项再放新项，remove删除所有同ID项，处理旧重复数据；相同ID顺序调度后只留最新记录。系统token仍用id.hashCode的31位值，不同ID碰撞归已有N05家族，不在本轮重复编号。

## ND06残留：Store原子操作不等于系统调度事务

Store.add/remove/clear/sweep使用类级共享LOCK，跨实例读改写丢条目的旧交错被阻止。[NotificationOffloadHandler.kt:232](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt#L232) scheduleDeferred仍先创建/更新token并setExactAndAllowWhileIdle，随后第247行构造entry、第254行store.add；锁只在后一步内部。

可复核静态交错：两个命令共用ID，A先注册时刻/内容A → B注册B → B持久化B → A持久化A。系统保留B，pending持久化A；重启恢复会按A重新注册。这里不需要hash碰撞，虽然Store没有重复项，仍可能不对应实际闹钟。cancel --all也先loadAll快照、逐项cancel再clear，与并发新增不是同一事务；快照后新增可留下系统动作而记录被clear。归入原通知状态协调ND06残留，不把“共享锁已加”当作整条链已修。

方向记录：用现有同ID/通知操作协调边界覆盖OS注册/撤销与持久状态，并考虑receiver消费的版本；未实现。不能只把更多Store方法加synchronized就宣称提交顺序统一。

## ND09部分修复与授权边界

[NotificationHistoryRepository.kt:30](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/NotificationHistoryRepository.kt#L30) purgeExpired真实DELETE过期行，search第64行先purge，count第102行也purge。旧“查询只过滤而不删库”已改变。RetentionInstrumentedTest直接使用真实repository与独立数据库名，推进传入purge时刻验证删行，不是镜像算法；本轮未运行。

现役全库purgeExpired引用只在Repository定义、search、count；record仍在事务内按当前时间删除。监听器断开/销毁只清connected，未清过期行或注册定时任务。[NotificationTools.kt:95](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/tools/NotificationTools.kt#L95) search先检查用户授权再创建repo，撤销授权后工具不会抵达search清理。因此最后一次读写后长期无通知/无查询，或撤销访问时，已存原始内容仍可能超过七天留在SQLite，ND09只算部分改善，不宣称self-expiring已经按墙钟自动兑现。

旧offload监听器isEnabled仍按包名判断任一授权组件，新notifications监听器按具体组件查询；ND08原组件混淆未因Repository改动解决。只复核相关片段，不升级已全文文件或新增编号。

## 测试与其它核对边界

StoreInstrumentedTest覆盖同ID顺序替换、跨实例并发add、add/remove、五分钟迟到保留和三小时drop；不调用AlarmManager、Receiver、handler，也不覆盖OS注册与Store交错或cancel --all并发。RestoreTest仅验证restoreAction/isLost纯决策，没有真实重启。AlarmReceiver使用goAsync/线程避免同步重活，pending.finish在finally；未把这视为广播生命周期时限或OEM恢复已通过。

Receiver.deliver先删entry再notify，拒绝post仅日志，投递失败持久恢复线索沿用旧报告，未另编号。NotificationOffloadHandler.parseIso仍用宽松SimpleDateFormat，超大list max和无界通知文本属于待核对输入/输出预算候选，本轮未制造输入或读取内容，不凭helper单段推断实际OOM。Store未知trigger时间可能算术溢出的形状需找到真实产生方，未计缺陷。

固定OpenMinis对照仍以旧报告已读取的 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 对象为版本基准：旧通知恢复/Store及Clock迁移缺口曾存在。新helper与本地修复按最终源码确认，没有fetch上游，也没有把上游旧实现当修复后的事实。

## 全文覆盖

| 新增全文文件 | 行数 |
|---|---:|
| [NotificationHistoryRetentionInstrumentedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/data/repository/NotificationHistoryRetentionInstrumentedTest.kt#L1) | 43 |
| [ScheduledNotificationStoreInstrumentedTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/androidTest/java/com/openminis/app/offload/ScheduledNotificationStoreInstrumentedTest.kt#L1) | 87 |
| [NotificationHistoryRepository.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/data/repository/NotificationHistoryRepository.kt#L1) | 134 |
| [AlarmReceiver.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/AlarmReceiver.kt#L1) | 127 |
| [GhostAlarmMigration.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/GhostAlarmMigration.kt#L1) | 37 |
| [ScheduledNotificationReceiver.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationReceiver.kt#L1) | 159 |
| [ScheduledNotificationStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/offload/ScheduledNotificationStore.kt#L1) | 111 |
| [NotificationOffloadHandler.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/runtime/guest/NotificationOffloadHandler.kt#L1) | 524 |
| [GhostAlarmMigrationTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/offload/GhostAlarmMigrationTest.kt#L1) | 68 |
| [ScheduledNotificationRestoreTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/offload/ScheduledNotificationRestoreTest.kt#L1) | 36 |

handler按1—270、271—524完整读取，其余9文件完整输出。旧报告合并输出与一次本地搜索截断，不作为新增全文依据；远端引用检索及关键调用片段随后独立核对。MinisApp、Manifest、listeners与tools只片段复核。

新累计台账：[2026-10-07-code-review-coverage-37158d18-notification-recovery.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-notification-recovery.json)。全文 **1174 / 1652文件、189368行**；partial4、not_registered474、excluded124，tracked1776。全库通读尚未完成。

交付核验旧144份报告与所有旧台账hash，固定本地/远端/上游HEAD与空tracked/staged diff，远端1776个快照Git blob/hash/bytes/lines及本文证据行号。仅新增两文件。
