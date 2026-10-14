# 备份合并与会话身份：已有设备测试范围全文审查

目标主线 `3527ad8165f11c0f98d8a30f96098fc9280faa94`。只读已有测试，不运行 instrumentation、不访问设备或真实数据库；仅新增报告与台账。没有新增已确认产品缺陷。

## 保留较新数据的真实语义

BackupKeepNewerInstrumentedTest 使用真实导出、ZIP解包和导入入口，导出后修改消息、删除另一消息、手动提高会话时间。断言较新编辑保留，同时明确期待已经删除的消息从备份重新填入。所以这个测试所称“保留较新”并不包含保留删除状态。不能把它的存在表述为恢复不会撤销用户所有后续操作；是否需要删除墓碑属于产品恢复语义，不能仅凭断言把有意补齐行为定成新Bug。

## 合并避免级联丢消息

BackupMergeInstrumentedTest 在备份后增加设备独有消息，并将本地会话 updatedAt 人工调低。导入后断言备份标题获胜，两条消息都存在且总数为2，报告没有失败类别。这能直接针对 session REPLACE 引起消息级联删除的回归；它并非真实聊天后自然时间推进的重现，也没有模拟并发流式写入、取消、磁盘耗尽或事务中途失败。

## 身份与Bot上限

BackupSessionIdentityInstrumentedTest 验证已删除会话恢复 botId、角色名称、temperature和compact marker version；达到 MAX_BOTS 时拒绝旧Bot，解绑会话，并报告至少两项ignored。

证据范围需要精确：角色只断言名称，设置只断言temperature，marker只断言version；不能推出整个角色快照、所有覆盖项、压缩锚点以及委派图全部恢复正确。seed marker没有 lastCompactedMessageId，也没有配套历史消息，未验证重启时按锚点拼接历史。第二例以固定顺序填满上限，没有覆盖两个导入并发争抢容量。

## 环境与清理边界

三个测试使用 targetContext 的 AppDatabase 单例，导出 CHATS 类别不是隔离的 in-memory 库。测试源码有自建会话/Bot及文件清理，但这不能作为可随意在用户正在使用的数据环境运行的依据。本轮没有启动它们。未运行结果与代码断言分开：此报告只说明已有用例结构和验证范围，不声称当前设备测试通过。

## 覆盖与交付

| 全文文件 | 行数 |
|---|---:|
| src/android/app/src/androidTest/java/com/openminis/app/backup/BackupKeepNewerInstrumentedTest.kt | 65 |
| src/android/app/src/androidTest/java/com/openminis/app/backup/BackupMergeInstrumentedTest.kt | 75 |
| src/android/app/src/androidTest/java/com/openminis/app/backup/BackupSessionIdentityInstrumentedTest.kt | 118 |

三个现版文件完整读取。新增全文3文件、258行；已全文重读0文件。其它rg定位不计全文。

累计全文1366 / 1758文件，208962行；partial 4，未登记388。全库通读未完成。[新台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-09-code-review-coverage-3527ad81-backup-merge-tests.json)。

校验旧226报告/全部旧台账hash、HEAD/origin/main、全快照Git blob/SHA-256/字节/行数、tracked/staged空diff。只新增报告与台账。
