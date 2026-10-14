# 备份跨操作互斥与消息合并审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2 问题**，补齐 4 个文件、2016 行全文阅读。仅静态审查；没有修改产品代码、运行构建或测试、启动模拟器、访问设备或云资源。旧报告保持不变。

## BM01 / P2：导出与恢复各自创建锁，无法互相等待

导出在 [BackupExporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:89) 使用 activityLock，但锁是 Exporter companion 自己创建的 Mutex（[BackupExporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:874)）。恢复在 [BackupImporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:135) 使用另一个 Mutex（[BackupImporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1336)）。后者注释声称与 exporter 共享，实际对象完全不同。两个导出之间、两个恢复之间可以互斥，导出与恢复之间不能。

同一个 BackupViewModel 的运行状态可以挡住通常的重复点击，因此不据此声称同页必然并发。可达候选链是：开始较大的备份后返回，重新进入备份页并恢复。[BackupAndRestoreScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt:145) 取得页面作用域 ViewModel；[BackupAndRestoreScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt:162) 的返回入口没有运行状态限制，导航 [AppNavigation.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:978) 会弹出页面。旧实例 scope 被取消，并不等于其中同步文件复制已被中断；[BackupExporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:465) 的 walkTopDown/copyTo 没有协程取消检查。新页面实例的运行状态不会等待旧实例，两个不同锁也不能协调它们。

在旧导出仍读文件时，新恢复可能开始覆盖同一记忆目录等数据，导出得到混合时点内容。此为源码可见的互斥缺口及静态交错条件，尚未做设备复现，不声称所有取消都会产生交错。Android 官方说明 [viewModelScope 随所属 ViewModel 清除而取消](https://developer.android.com/topic/libraries/architecture/coroutines#viewmodelscope)，不能据此推导同步 copyTo 立即终止。

修复方向：使用真实共享的进程级备份活动互斥对象，并让生产导出、恢复都持有它；核对离开页面时阻塞任务的生命周期。这里仅记录，没有实现。此前停止按钮与恢复事务问题不重复计数。

## BM02 / P2：保留较新会话，却仍用旧备份无条件替换其消息

[BackupImporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:385) 比较 session.updatedAt，遇到本地会话较新时跳过元数据写入，但仍把 id 加入 restoredSessionIds。随后消息循环没有读取现存消息或比较更新时间，直接在 [BackupImporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:476) 调用 insertMessage；[ChatDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:263) 的冲突策略是 REPLACE，并在 [BackupImporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:489) 将消息 updatedAt 设回 createdAt。

静态反例：旧备份包含会话 S 和消息 M；本地之后修改同 id 的 M，且 S 的 updatedAt 晚于备份。恢复时 S 被保留，但 M 的旧 parts、reasoningContent 等仍覆盖本地新内容。消息不是只增不改：[ChatDao.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatDao.kt:403) 提供原位 parts 更新，[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5494) 的工具块重跑会裁剪保留的 assistant 消息，[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7229) 的历史修复也会改写已持久化内容。恢复旧包可能撤销这些本地改写。

[BackupExporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:353) 的 messageRecord 只导出 createdAt，没有真实的消息 updatedAt，当前格式也不足以直接按消息修改时间合并。恢复界面 [BackupAndRestoreScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt:901) 展示的说明来自 [strings_backup.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values-zh/strings_backup.xml:111)，明确承诺仅较新备份副本替换现存条目，与消息路径不符。

这不同于旧报告 F34：此反例特意让本地 session 较新，insertSession 不执行，没有父记录 REPLACE 级联删除；覆盖发生在消息自身。也不依赖取消、中途异常或损坏包，不重复 F35。修复方向需先明确旧格式冲突策略，再保护本地较新消息；仅给 session 增加时间比较不足以解决。

## 固定 OpenMinis 对照

只读已有 `/tmp/openminis-review-20261004` Git 对象，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。

| 问题 | 固定上游 Android 源码 | 判断 |
|---|---|---|
| BM01 | BackupExporter.kt 88、731；BackupImporter.kt 135、1139：两个 companion 分别创建 Mutex | 共享锁缺陷；不推定上游页面生命周期与本地完全一致 |
| BM02 | BackupImporter.kt 480—526：消息直接 insertMessage；ChatDao.kt 253—254：REPLACE | 共享消息冲突处理缺口 |

## 覆盖和复核

| 新增全文文件 | 行数 |
|---|---:|
| [BackupImporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt)，原 partial，已补齐所有分段及截断重读 | 1409 |
| [strings_backup.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values/strings_backup.xml) | 207 |
| [strings_backup.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values-zh/strings_backup.xml) | 200 |
| [strings_backup.xml](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values-zh-rTW/strings_backup.xml) | 200 |

累计全文 **1000 / 1546 个文件、205911 行**；partial 13，not_registered 533，excluded 124。全仓通读尚未完成。BackupAndRestoreScreen 与 ChatViewModel 仅登记补读片段，继续 partial；此前 full 文件片段复核不重复计数。没有运行已有测试，也没有新写测试。

完成时核对全仓 1670 个 tracked 路径、SHA-256、字节数和正数文本行数；本仓与固定上游 HEAD、tracked/staged 空 diff；此前 73 份报告原哈希；本报告文件链接行号；台账状态与批次全文总计。
