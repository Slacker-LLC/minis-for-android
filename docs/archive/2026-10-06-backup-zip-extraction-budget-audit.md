# 备份 ZIP 解包预算静态审查

日期：2026-10-06。新增 1 项 P2。独立报告，只更新阅读台账，未改产品代码与旧报告。

本地 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读已有 Git 对象，没有拉取或执行上游。没有构建、运行测试、模拟器、adb、Root、设备、云任务、凭据或子 Agent；未创建压缩包、执行解压或耗尽资源实验。

## BZ01 · P2 · 未验证包在预览前无预算展开，失败与取消无法及时收回资源

[BackupZip.kt:320](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt:320)对每个 ZIP entry 展开；[BackupZip.kt:337](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt:337)循环 read/write 到成员 EOF，直到 nextEntry 没有下一项为止。没有解压后单成员、累计字节、条目数或工作时长边界，也没有按可用存储检查或循环内取消检查。256 KiB 共享 buffer 限制的是每次缓冲内存，不是总输出与总工作量。

[BackupZip.kt:127](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt:127)条目数上限和 [BackupZip.kt:134](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt:134)总原始字节检查只用于 archive；32 MiB DEFLATE 门槛也是 writeEntry 的导出决策。它们没有应用于接收外部备份的 extract，不能据此称解包已经有同等预算。

真实入口 [BackupViewModel.kt:607](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:607)接收 SAF 文件，先 copyTo 缓存，再调用 extractAndInspect；[BackupViewModel.kt:642](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:642)把所有条目写入 cacheDir/restore-extract，之后才在648行解析 manifest、展示类别预览。下载服务器备份最终也经 [BackupViewModel.kt:936](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:936)调用同一函数。包甚至无需最终有合法 manifest 才能进入完整展开。

密码与 manifest MAC 验证在之后真正 import 的 [BackupImporter.kt:172](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:172)，integrity 也在 [BackupImporter.kt:179](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:179)。因此后置的验证失败不能退回已经花掉的解压 CPU 和缓存空间；用户只打开备份预览、尚未点击实际恢复就可消耗这些资源。高压缩率的大成员或数量很多的普通条目都进入相同无预算循环；不需要越界路径，本项不重复旧 BF 的文件范围问题。

异常后的回收也不足：extract 不负责清理 partial 输出；[BackupViewModel.kt:622](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:622)只设置错误和结束状态。下次 extractAndInspect 开始时会 deleteRecursively，[BackupViewModel.kt:962](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:962)也能通过已有 pending 清理，但第一次提取失败尚未 setPending 时，当前 partial 输出没有被该失败路径立即删除。不能声称数据永不清理，只能确认它可能留到下一次打开或系统缓存清理。

[BackupViewModel.kt:815](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:815)取消 openJob 后，[BackupViewModel.kt:937](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:937)的 ensureActive 在整次 extractAndInspect 返回之后，当前解压循环和进度回调没有取消检查。源文件读写不会因为取消这个协程就自动变成协作取消；注释也明确承认 unzip 中途不能打断。因此 Cancel 不能作为缺少工作量预算的有效补救。本项把取消与失败清理记为同一解包资源生命周期的影响，不另加问题数。

限制：空间不足会最终使写入失败，Android 也可能回收缓存，但失败前仍可占满 App 所在存储的可用空间并干扰其它写入。没有实测磁盘耗尽、耗时或界面卡顿；导入应用数据尚未执行不等于没有缓存资源影响。合法的大迁移包本身也可能很大，修复不能凭任意低固定限额静默遗漏其成员。

此前 BF 报告261行只将“解压缺少总体预算”列为待核候选，本轮追到 SAF/服务器打开、预览及认证时序才登记。固定上游 [BackupZip.kt:320](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt#L320)同一循环；[BackupViewModel.kt:421](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt#L421)同样先解压再读 manifest，785/786 行才在完成后检查取消。共享缺口。

修复方向记录：为实际展开字节、数量与可用空间制定可核验的导入预算，在循环中检查边界及取消；失败/取消立即清理本次暂存内容。为正常大备份提供明确的完整迁移行为，不能用截断/跳过代替错误。预检包元数据只能辅助，最终计数仍应以实际写入为准。本轮不实现或运行验证。

## 全文阅读与其他观察

已全文读完 ZIP 的内容探测、压缩比例判断、STORED CRC 双读取、DEFLATE、extract、readEntry、listEntries、wrapper root 与 safeResolve。路径 canonical 校验和拒绝绝对路径/.. 已实现，不误报为缺失。STORED 的双读取发生于内部 staging；不能直接套用上一轮 BK01 的活动共享源文件交错。

两份测试源码覆盖压缩决策、混合方式往返、路径遍历拒绝、wrapper 和导出条目计数边界，不覆盖真实打开入口的解压预算、取消和失败清理。未执行这些测试。

ZIP64 总长度检查只统计 payload、不计 header/central directory 的观察尚需对照设备 ZIP 实现及上游读取限制，不升级为本轮确定问题。readEntry/listEntries 没有本地生产调用，相关扫描开销不单独编号。

## 覆盖与核验

本轮全文 3 个文件、787 行：

- [src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt)：443 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupZipStoreByContentTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupZipStoreByContentTest.kt)：161 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupZipTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupZipTest.kt)：183 行。

累计全文 971 / 1546 个文件、197018 行；partial 14，not_registered 561，excluded 124。全仓通读尚未完成。ViewModel 与 Importer 调用片段精确追加到 partial，未冒计全文；PackageReader 此前已全文，复核不重复增加。

所有 1670 tracked 路径、SHA256、字节数与正整数文本行数重新核对，67 份旧报告摘要保持不变；HEAD 固定，tracked/staged diff 为空；新报告引用行号及台账批次总数核验。本轮源码和测试全文输出均完整，无截断冒计。没有真实资源消耗或备份验证证据。
