# 备份历史持久化与设备存储静态审查

日期：2026-10-06。新增 1 项 P2。只新建报告和更新阅读台账，未修改产品代码或旧报告。

本地 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 对照 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读既有上游 Git 对象，没有拉取、构建、执行测试、模拟器、adb、Root、设备、云任务、凭据或子 Agent 操作。未执行备份、删除或故障注入。

## BH01 · P2 · 历史写入失败仍发布成功内存状态，中断覆盖可能丢失全部历史

[BackupHistory.kt:134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupHistory.kt:134)先将 cache 设为新列表，再直接 writeText 覆盖 records.json。异常只记录 AppLogger，persist/upsert/remove 都不返回失败。同步锁可以防止同实例并发修改，但不能提供文件提交的原子性。

真实备份入口 [BackupViewModel.kt:276](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:276)先 upsert RUNNING，再立即把 history.records() 发布到界面；[BackupViewModel.kt:433](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:433)结束时也是同样顺序，[BackupViewModel.kt:449](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:449)还逐次发布进行中记录。读取优先返回 cache，所以界面可显示更新已经完成，而磁盘并未可靠提交。

两条静态失败链：

- 文件未能成功打开或提交时，旧磁盘内容可能保留，界面显示的新状态重启后消失。例如进行中记录变为完成只在内存生效，重新启动后 reconcileInterrupted 可将旧 RUNNING 标为 FAILED。删除历史的 [BackupViewModel.kt:509](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:509)也有内存删除、重启恢复旧记录的同一边界。
- 写入已截断文件、尚未完成时进程终止或发生 IO 错误，可留下无效 JSON。[BackupHistory.kt:122](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupHistory.kt:122)吞掉读取/解析异常并替换成 emptyList，而不是保留可恢复版本或显式报告存储错误。此后创建新备份会在这个空列表上写新记录，原历史无法由该存储恢复。

影响限定：丢失的是备份历史、目的地与失败记录，不据此断言实际备份包也被删除。只有实际发生对应写入失败/中断才走上述链；正常写入仍能保存。日志可以留下写入异常，因此这里是用户可见结果与可靠落盘脱节，不是完全没有日志。

固定上游 [BackupHistory.kt:134](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupHistory.kt#L134)采用相同 cache-first、writeText 与吞异常模式，属于共享缺口。

[BackupHistoryRecordTest.kt:154](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupHistoryRecordTest.kt:154)通过手动 copy 构造 interrupted 结果；保留期测试也只比较时间戳。测试未实例化真实 BackupHistory 存储，不能证明磁盘失败后历史仍可恢复。[BackupRecordRoundTripTest.kt:145](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupRecordRoundTripTest.kt:145)明确使用记录构造的镜像，不能拿 JSON 模型往返替代实际存储证据。本轮只阅读测试源码。

修复方向记录：复用项目已有的可靠文件提交方式，保留完整旧版本直到新版本提交成功；让失败参与调用结果与界面反馈，读取损坏不能静默作为有效空库继续覆盖。真正修复时再通过正常历史入口核验提交失败、中断重启和删除失败，保留可重复工件。本轮不改代码、不执行验收。

## 其他核查与去重

BackupRestoreFiles 已全文阅读。暂存文件 rename 失败的 Boolean 被吞掉及未汇总文件失败，旧 BF06 已记录，不重复编号。索引 sha 来源范围约束和摘要/复制分开读取对应旧 BF01、上一轮 BK01，也不重复。

BackupDeviceStorage 已全文阅读 MediaStore、旧公共目录写入、大小复核、异常删除、名称约束与冲突改名。未发现本轮可以升级为确定新问题的独立缺口。MediaStore 的真实设备行为未验证；InstrumentedTest 的源码存在不等于设备验证通过。

BackupHistory 的 30 天清理只在首次加载发生，缓存命中时不再次按时间过滤。当前注释明确写 first read，本轮保留为行为观察，不扩展成已承诺持续清理的确定 Bug。

## 阅读覆盖与核验

本轮全文 8 个文件、1239 行：

- [src/android/app/src/androidTest/java/com/openminis/app/backup/BackupDeviceStorageInstrumentedTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/androidTest/java/com/openminis/app/backup/BackupDeviceStorageInstrumentedTest.kt)：94 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupDeviceStorage.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupDeviceStorage.kt)：197 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupHistory.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupHistory.kt)：210 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupRestoreFiles.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupRestoreFiles.kt)：162 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupDeviceStorageTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupDeviceStorageTest.kt)：50 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupHistoryRecordTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupHistoryRecordTest.kt)：173 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupRecordRoundTripTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupRecordRoundTripTest.kt)：178 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupRestoreFilesTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupRestoreFilesTest.kt)：175 行。

累计全文 960 / 1546 个文件、194737 行；partial 14，not_registered 572，excluded 124。全仓通读尚未完成。BackupViewModel 只记明确片段，不计全文。

合并检索与测试输出曾截断，随后单独完整重读 BackupRestoreFilesTest 与 BackupDeviceStorageTest；上游历史关键段也单独重读。未依赖被截断的测试开头登记全文。

全仓 1670 个 tracked 路径、SHA256、字节数与正整数文本行数重新核验，64 份旧报告哈希保持不变，HEAD 与 tracked/staged diff 检查通过；本报告本地引用行号及台账批次总数核验。没有运行时证据。
