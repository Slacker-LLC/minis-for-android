# 备份停止与历史目的地定位静态审查

日期：2026-10-06。当前固定 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2 问题**，新增全文阅读 4 个文件、1701 行。独立新报告，旧报告未修改。只静态审查，没有修改产品源码，没有执行测试、构建、模拟器、设备或真实传输。

## BHCTL01 / P2：“停止备份”没有停止远程发送，还会继续发送到后续目的地

现役可达入口是 [停止备份按钮](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt:395)，调用 [stopExport](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:77)。该函数仅 job.cancel()。主流程在 [远程发送阶段](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:322)进入 withContext(Dispatchers.IO)，同步调用 deliverToRemotes。

[目的地循环](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:561)没有检查协程取消，调用 upload 时只传 packageFile、remote、backupId 和进度回调，没有传 isCancelled。[upload 的默认取消函数](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:104)始终返回 false，实际 copyfile 是阻塞 RPC；没有 async job/stop。即便用户停止，上传仍能完成大小校验和 movefile 提交，也能重试失败请求。循环随后仍会给后续远程发送。

静态反例：配置 A、B 两个启用服务器，在 A 的慢速上传开始后点击“停止备份”。只要 RPC 最终正常完成、进度回调没有额外异常，A 的文件会完成提交，B 的上传仍开始；当前 _isRunning 要到整个 IO 调用结束才在 finally 清除。等待时间可能是多个远程请求及重试的总时间，不是一次安全写入结束就停止。

还有结果不一致：取消后 withContext 返回时会丢弃结果并抛 CancellationException。[取消异常记录](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:399)将原本尚未写入 summary 的 record 标成 FAILED/stopped；backupId 仍空，packageName/目的地结果未填入，即使这轮已经完成远程副作用，也没有对应的成功目的地记录。publishRunning 可以保留进度日志，不能说完全无痕。

平台证据与本仓库固定依赖一致：[build.gradle.kts](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:582)使用 kotlinx-coroutines-android 1.9.0；[该版本 withContext 源码](https://github.com/Kotlin/kotlinx.coroutines/blob/1.9.0/kotlinx-coroutines-core/common/src/Builders.common.kt#L107)定义切换 dispatcher 后的返回取消保证。这里不能把 job.cancel 当作阻塞 Go RPC 的中断信号。本轮未运行取消或网络实验。

修复方向：复用已有 isCancelled 与单文件下载的异步 rclone job/stop 模式，将外层取消贯穿每个目的地、重试和传输；已完成的目的地应保留真实结果。不能仅改按钮文字或在全部发送结束后把记录标为 stopped。

这补齐了上一轮只确认“upload 的取消参数未接入”的候选：本轮找到真实可点击的总备份停止入口和完整调用链。上一轮的旧分片取消候选仍未独立计数；这里审查的是现役新备份发送，范围不同。

## BHCTL02 / P2：历史文件删除仅按可复用的服务器名字定位，可能操作新目的地

[历史详情删除文件选项](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupHistoryDetailScreen.kt:227)触发 [removeHistoryRecordWithFiles](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:471)。记录保存了交付时的 backend 和 path，但 [定位服务器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:491)只比较 outcome.name 和当前 store.remotes 的 name，随后直接 deletePackage(remote, packageName)。[deletePackage](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:264)使用当前 remote.join(packageName)，没有使用历史 path，也没有验证当前服务器是否仍是当年的目的地。

这里不假设存在直接编辑目的地功能。可达变更路径是：[remove](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:158)移除旧配置，然后 [add](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:133)用相同名字重新注册不同服务器或不同目录。add 只拒绝当前列表中的重名；移除后该名字可用，旧历史不随配置删除。新 Remote 虽有新的 createdAt，删除函数也没有检查它。

静态反例：记录 R 对应 nas 的 `old-folder/backup-X.minisbak`。用户移除 nas，再以 nas 注册 new-folder。之后在 R 中点“删除记录和文件”，执行的目标是 new-folder/backup-X.minisbak，old-folder 的真实历史包留下。若新位置恰好有同名文件，它会被删除；若新位置没有同名文件，请求可能失败，但旧文件仍不受影响。不扩大为任意文件删除，也不声称已发生真实误删。

当前实现还会在删除失败时仅写 AppLogger，然后继续 [移除记录](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:504)；详情入口 [AppNavigation](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1014)立即返回上一页。历史中的定位信息可能丢失，用户也没有看到按目的地汇总的失败结果。这里作为同一历史删除链的影响记录，不再拆成额外新问题。其 KDoc 明确选择 best-effort 删除后忘记记录，但这不能证明按新位置删除符合历史文件的目标。

修复方向：历史删除必须先确认目的地身份与历史位置相符；无法确认或原服务器已移除时，保留记录并报告无法删除，不能静默用同名新配置代替。无需为排查修改存储结构或保存额外凭据。历史详情点击目的地也仅传名字，应在实际修复时一起核对。

## 固定 OpenMinis 对照

只读已有 `/tmp/openminis-review-20261004` 对象，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。没有 fetch、构建或执行上游程序。

| 问题 | 固定上游证据 | 判断 |
|---|---|---|
| BHCTL01 | BackupViewModel.kt 88 调 runner.stop；BackupRunController.kt 220—229 传入 Job 状态；453—473 在目的地之间检查并向 upload 传取消；RcloneChunkedUpload.kt 126—178 使用异步 copy job | 上游已改进主发送链，本仓库尚未接入；不能声称每个极端取消时序均已验证 |
| BHCTL02 | BackupViewModel.kt 321 仍只按 outcome.name 查当前配置，346 删除当前 remote 的同名包；365—367 删除失败时返回 Failed 并保留记录 | 同名新目的地定位仍是共享问题；失败反馈与保留记录已有上游改进 |

上游存在 local-folder 等本仓库没有的分支，本轮没有直接建议整段移植，也没有把上游功能差异自动算作缺陷。

## 阅读与证据限制

| 新增全文文件 | 行数 |
|---|---:|
| [BackupViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt)（原 partial，补齐） | 1042 |
| [BackupHistoryDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupHistoryDetailScreen.kt) | 439 |
| [BackupDestinationSaveStateTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupDestinationSaveStateTest.kt) | 145 |
| [BackupLocalCleanupTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupLocalCleanupTest.kt) | 75 |

累计全文 **992 / 1546 个文件、202455 行**；partial 15，not_registered 539，excluded 124。全仓通读尚未完成。BackupViewModel 原已读片段的哈希保持一致，本轮补齐其余区间后才提升 full；不是把调用链摘读算为全文。

两个现有测试分别模拟保存页面状态和本地删除谓词，未触达本轮远程取消、历史同名目的地变化；没有执行测试。BackupHistoryDetailScreen 与 BackupViewModel 的合并输出有过截断，已单独补读遗漏部分。BackupAndRestoreScreen 只追加 370—425 的真实读取片段，仍 partial；AppNavigation、RemoteStore 与 RcloneChunkedUpload 此前 full，本轮复核不重复计数。

所有 1670 个 tracked 路径、SHA-256、字节数与正数文本行数、固定 HEAD、tracked/staged 空 diff 已复核；之前 71 份报告哈希不变。覆盖台账统计与批次一致，文件链接及行号已检查。未读取真实账户、凭据或远程文件。

下一步候选：历史详情立即 pop 后其局部 ViewModel 的协程存续，可能影响“删除文件后再删除记录”的完成路径；还需要核对导航生命周期，暂未计为已确认问题。源文件中非实际影响的注释、任务标签和样式不计新问题。
