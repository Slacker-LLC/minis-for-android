# 技能安装事务、互斥与恢复日志静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文4文件、1707行；确认1项P2。只静态阅读，未修改产品源码，未编写或运行测试、构建、模拟器、adb、Root、设备或云任务；未读取账号、凭据、真实技能文件或数据库。独立新报告，既有报告不改。

## STE01 / P2：写入未限制单文件4 MiB，下一次合并先读取全部旧文件而拒绝任何修改

[SkillPackageInstaller.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillPackageInstaller.kt:594) checkStagedRequest校验路径、碰撞、512条目与16 MiB总量；第631—633行累计bytes检查总量，缺少SkillArchiveReader.MAX_ENTRY_BYTES单文件4 MiB限制。[SkillPackageInstaller.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillPackageInstaller.kt:486) stageFiles直接将每个payload交store.writeFile，[SkillTransactionStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillTransactionStore.kt:76) 使用WorkspaceFileClient.writeBytes；[WorkspaceFileClient.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:23) 文件客户端上限50 MiB，因此5 MiB文件不会被下层限制拒绝。

真实编辑入口 [SkillsManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SkillsManagementScreen.kt:1206) 调SkillRepository.writeSkillFile；第1226行onValueChange直接接收文本，无此处4 MiB检查。[SkillRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1301) 将content编码成bytes，以PRESERVE方式提交，当前技能已有小SKILL.md、其它内容小，总量低于16 MiB时，写入一个5 MiB的scripts/example.txt能通过这些预算判断。此静态条件不要求压缩归档绕过校验，也没有生成该负载或实际保存。

下一次同技能的PRESERVE操作在 [SkillPackageInstaller.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillPackageInstaller.kt:522) 先readInstalledSkillFiles，再第530—531行过滤将要替换的旧条目。[SkillPackageInstaller.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillPackageInstaller.kt:574) 对每个旧文件以4 MiB上限读；[SkillTransactionStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillTransactionStore.kt:80) 有界读失败返回null，collectInstalledFiles随即抛IOException，stageMergedPayload在第524行返回IO_ERROR，尚未执行过滤/新payload staging。因此这个已经允许保存的5 MiB文件使后续任意PRESERVE写入失败，甚至用户将同一文件内容缩减为几字节也不行；renameSkill第1276行同样用PRESERVE。

这是写入和后续读取预算不闭合，影响该技能的编辑与重命名。不是宣称所有技能失效，也不是删除或完整REPLACE安装都无法恢复。原始文件仍在；4 MiB读拒绝本身是有界保护，不能靠取消它来解释为修复。编辑页保存回调还忽略writeSkillFile的Boolean结果并退出，但该类失败反馈问题与旧报告可能重叠，不另计一项。

修复方向：在统一staged request校验中先兑现单文件预算，使所有生产者遵守同一限制；另外明确已有超界条目如何替换/恢复，当前“读全部再过滤”无法让用户直接把该条目缩小。需要保留路径校验、整包总量和事务一致性。未实施修复。

## 互斥、文件适配与恢复链核对

SkillMutationLock全文236行：进程Mutex加文件锁，同Job/同root重入；获取锁后先恢复pending operations，再执行调用。锁文件要求父目录与文件为普通节点，并在打开后再次拒绝符号链接。与单纯JVM锁不同，文件锁负责跨进程互斥；未通过设备/多进程运行验证。

SkillTransactionStore全文184行：生产适配器使用WorkspaceFileClient的受控guest文件API；空session参数用于全局skills路径，不能仅凭这个参数声称会话workspace被绕过。copy/move/mkdir在下层异常时抛出，返回JSONObject没有被读取不等于执行失败被忽略。delete的false表示目标不存在，适配器的幂等删除语义与此对应。

SkillPackageInstaller全文734行：REPLACE预先staging，PRESERVE在锁内读/合并；确认目标、capture registry snapshot、写journal、移动旧目标备份、移动新目标、更新registry、清journal。提交与删除使用NonCancellable，失败进入rollback；恢复失败保留operation供下一次拒绝/恢复。路径验证含NFC/大小写碰撞、目录/文件冲突、相对路径与深度校验。

SkillRecoveryJournal全文553行：读journal有256 KiB上限，验证version/records/flags/registry JSON，恢复保留backup并先复制到recovery staging，恢复文件后由调用方恢复registry，随后清journal与operation。重复pending skill id拒绝。没有把这些静态顺序等同fsync/突然断电实证。

核对 [SkillRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:2018) registry恢复读取body，进一步追到第2143/2233行使用直接WorkspaceFileClient.readAllBlocking，没有再次调用SkillMutationLock；不能以同步读取/runBlocking就声称恢复必然锁重入死锁。rescan缺文件分支调用delete时沿原Job进入锁的重入路径，未确认该正常链死锁。

取消异常被部分外层Exception处理转换为Failure、无journal staging残留、外部文件替换与恢复递归深度等候选仍需明确实际调用/失败前提，未扩展为本轮已确认缺陷。没有用这些候选推断旧minisd/PRoot重新进入生产。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。其tree没有本轮4个独立事务模块；上游SkillRepository第928—945行readSkillFile/writeSkillFile直接File.readText/writeText，不存在本地“先按4 MiB读全树再合并”的路径。STE01是当前本地新增事务的读写限制不一致，不能因为上游读写无界就要求退回旧存储方式。对照取自固定Git对象，不依据移植注释推定代码相同。

## 覆盖与完整性复核

| 新增全文文件 | 行数 |
|---|---:|
| [SkillMutationLock.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillMutationLock.kt:1) | 236 |
| [SkillTransactionStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillTransactionStore.kt:1) | 184 |
| [SkillPackageInstaller.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillPackageInstaller.kt:1) | 734 |
| [SkillRecoveryJournal.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRecoveryJournal.kt:1) | 553 |

锁/存储全文完整；安装器分1—245、246—488、489—734；journal分1—190、191—385、386—553。相关repository/UI/WorkspaceFileClient/SecureFileAccess只作调用闭环片段复读，不重复增加覆盖。一次初始查询使用错误客户端路径，随后通过rg --files找到runtime/files位置并完整读取相关段，错误路径没有成为证据。

累计全文 **1343 / 1546文件、261291行**；partial13、not_registered190、excluded124，全仓通读尚未完成。交付核验全部1670 tracked路径SHA-256/字节数/正数文本行数、两仓固定HEAD、tracked/staged空diff、此前135份报告哈希、新报告绝对链接行号、全文批次总计。仅新增本报告并更新覆盖台账。
