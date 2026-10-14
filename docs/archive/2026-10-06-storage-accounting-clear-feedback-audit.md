# 文件占用统计与清理反馈静态审查

日期：2026-10-06。本仓固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2、1 项 P3**；全文阅读 3 个文件、684 行。只读源码与已有测试，不改产品代码，不运行测试、构建、模拟器或设备命令，不使用云资源。独立报告，旧报告不改。

## ST01 / P2：媒体删除返回失败时，清理页仍把占用显示为零

清理入口 [StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt:324) 先在 IO dispatcher 清理 guest 文件，再在 [StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt:329) 调用 deleteSessionMedia。该 helper 在 [StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt:427) 遍历媒体目录，对同名 session 目录调用 deleteRecursively，却不检查其 Boolean 返回值（[StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt:432)）。因此返回 false 不会进入外层 runCatching 的失败分支；[StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt:331) 仍把 minisSize 与 mediaSize 都设为零。

[Kotlin deleteRecursively 官方合同](https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.io/delete-recursively.html)说明删除失败返回 false，并可能已经发生部分删除。这是返回结果处理缺口，不以“没有抛异常”作为全部文件已删除的证据。静态反例：guest 清理完成，媒体目录因父目录不可写或删除时发生文件系统失败而只删除一部分，helper 返回 Unit，界面仍显示无媒体文件、零占用，且清理按钮因 hasFiles=false 被禁用。重进页面可能重新测得残留，当前操作没有给出失败反馈。

真实目录结构由 [MediaStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/storage/MediaStore.kt:28) 写为 media/yyyy/MM/dd/sessionId/file，与 helper 按目录名匹配的入口相符。此项不声称正常可写 App 私有媒体必然删除失败，不把操作系统失败本身计为产品错误；确认的是失败时的结果被隐藏。

媒体遍历/删除还在 withContext(IO) 返回后执行，可能延长界面线程工作，但本轮没有实测卡顿，作为同一入口的后续核对点，不另计新问题。guest 删除走 WorkspaceFileClient 与 SecureFileAccess，不能把媒体 Boolean 缺口泛化成所有 guest 删除均静默失败。

修复方向：检查媒体删除结果，重新测量残留并呈现部分失败；不要无条件将占用置零。本轮没有实现或删除任何文件。

## ST02 / P3：“Minis 占用”总量未包括全局持久用户目录

[FilesHubScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/FilesHubScreen.kt:181) 把 StorageSnapshot.total 显示在“Minis 占用”标题旁。[StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt:81) 的 total 仅为 shellSize + dbSize + sessionsTotal；[StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt:90) 的采集仅测 rootfs、数据库和 listSessions 返回会话的四个 session guest 根及 media。

现役 [UbuntuPaths.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:102) 把全局 memory、skills、shared、mcp-servers 设在 filesDir/minis-global 下，home 在 filesDir/minis/home；它们不是 minis-sessions 子树，也不是 minis.db 或 Root-owned rootfs。[UbuntuKernel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:225) 的 rootfs 测量只调用针对 HOST_ROOTFS 的固定 du，[RootfsManager.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/sandbox/RootfsManager.kt:203) 也没有把全局 backing 并入统计。

静态反例：所有 session 文件不变，仅往真实 shared backing 增加大文件，FilesHub 顶部“Minis 占用”及三个分段不变，尽管该页同时提供 shared/memory/skills 的浏览入口。这里只确认全局目录的确定遗漏；没有用未知 cache/log 大小猜测实际漏报多少，也不主张该数字等于 Android 系统设置的所有安装占用。

修复方向：明确统计口径。如果显示整个产品占用，应纳入这些现役持久 backing，并避免 bind/alias 重复计数；若只显示三类已测数据，应把标题改为相应口径。没有修改界面或测量真实用户文件。

## 固定 OpenMinis 对照与暂不计数候选

只读已有 `/tmp/openminis-review-20261004` Git 对象，固定 HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。

- ST01：上游 StorageManagementScreen.kt 635—660 使用 deleteFilesDetailed 收集 failedPaths，再测量剩余大小并反馈；data/session/SessionStorage.kt 74—102 检查删除成功和目标是否仍存在。本地仍保留零置状态和不检查 Boolean 的媒体 helper。没有据此要求恢复上游 PRoot/Alpine 架构。
- ST02：上游 StorageUsageViewModel.kt 137—145、175—191 包含 shell、数据库、logsCaches 与 session 行；其 shell 来自旧 alpine-rootfs。该片段也不能证明已覆盖本地 Direct Ubuntu 的 minis-global backing，故不把上游旧路径当修复模板。

listSessions 会过滤部分 source，存储页没有子会话聚合；但现役 AppSubAgentPort 使用的 SOURCE_SUB_AGENT 值为 sub_agent，与 DAO 过滤的 subagent 不同。此次未把所有子会话描述成一定隐藏，也未新增子会话清理缺陷；后续需沿实际 Bot/child 创建与保留链核验，避免把旧注释当实现事实。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [FilesHubScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/FilesHubScreen.kt) | 228 |
| [StorageManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/StorageManagementScreen.kt) | 435 |
| [StorageBarSharesTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/settings/StorageBarSharesTest.kt) | 21 |

已有 StorageBarSharesTest 仅验证比例和零/负输入；没有覆盖统计来源或删除结果，本轮未运行。StorageManagementScreen 按 1—220、221—435 阅读，后一段已单独重读以消除合并输出截断。FilePreviewScreen 尚未读取，本轮不登记其全文覆盖。

累计全文 **1003 / 1546 个文件、206595 行**；partial 13，not_registered 530，excluded 124。全仓通读尚未完成。已有 full 文件只片段复核，不重复统计；未升级其他 partial。

完成时核对全部 1670 tracked 路径、SHA-256、字节数和正数文本行数，固定本仓/上游 HEAD，tracked/staged 空 diff，此前 74 份旧报告哈希，报告文件链接与行号，以及台账批次与全文总计。没有访问真实媒体、密码或账号。
