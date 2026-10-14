# 分享接收修复与暂存生命周期审计

日期：2026-10-07。固定远端HEAD `37158d188a190416f2b16b45dfb112ecf53cdd7b`。新增全文7文件、1000行；新增SHR01一项，复核旧SH/NV编号。仅静态阅读，没有修改产品代码、编写/运行测试、读取或分享实际文件、导入Provider、使用凭据、构建、模拟器、adb、Root、设备或云任务。此前145份报告及旧台账保持原样。

## SHR01 · P3 · Provider导入完成后暂存原件成为孤儿

[ShareReceiverActivity.kt:318](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt#L318) copyUriToStaging把共享JSON复制到filesDir/share_extension/shared-随机名.json；第75行识别provider export后显示导入/附件选择。[ShareReceiverActivity.kt:191](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt#L191) 导入按钮调用importProviderJson，随后finish；成功、返回null、repo不可用、捕获异常，都没有deleteSharedFiles，也没有savePendingShare。

ProviderRepository第2573行importInstanceJSON只接收字符串，无法识别ShareReceiver所建暂存文件；第2614行处理apiKey等导出字段也不负责删这个副本。原文件因此不归任何PendingShare或内存buffer所有。全库share_extension/清理引用核对：正常聊天消费只删本次附件名，overflow/expiry也只删已登记项；cleanSharedFiles仅在另一个过期pending记录被处理时目录清理，并非Provider导入或一般启动的收尾。

静态可达顺序：分享一个小Provider JSON → staging复制成功 → 选择导入 → 导入返回后Activity结束 → staging文件仍在，下次重复分享生成另一个名字。既不需要导入失败也不依赖并发。如果后续没有过期pending触发目录清理，孤儿副本持续保留；含apiKey/manualOAuthToken的输入也会原样留在私有暂存目录。这里只确认多余磁盘副本及不受队列预算管理，不声称其它App可读、远程泄密或已测得磁盘耗尽。

与SH03的待消费队列累计预算不同：这条路径已结束且没有进入队列；与SH04失败附件被过早删不同：这里是成功导入后未删。本轮首次独立记录。方向：导入成功后按本次items删原件；失败若要重试应保留明确所有权与重试入口，避免无登记留下文件。只记录，不修改。

固定OpenMinis `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的ShareReceiverActivity第145—220行同样是importProviderJson后finish，未清理本次items。本轮读取已有Git对象，不fetch；这是共同遗漏，不说由此次修复引入。

## 旧分享问题的当前状态

旧编号来源：[导航、系统分享、文件浏览与会话导出静态审计](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-navigation-share-export-audit.md)。

| 编号 | 当前静态状态 |
|---|---|
| SH01 目录级清理删除其它交接文件 | 正常消费和buffer过期已按本次文件删除；stale prefs分支仍目录清理，部分修复 |
| SH02 text/plain stream被忽略 | 已通过singleSendSource处理无文字但有stream的文本文件 |
| SH03 内存队列无累计上限、长文本绕过预算 | 队列新增50项/64MiB，长文本计入32MiB批次预算，原缺口已补 |
| SH04 导入失败仍删原件 | 新增失败toast，但仍消费buffer并删失败项，部分修复 |
| NV02 首页/设置暖分享无聊天入口 | 新增shareNeedsFreshChat路由，原静态缺入口已有补偿 |
| NV03 重组重复生成快捷启动UUID | startDestination已remember(initialDeepLink)，原重组触发点已处理 |

## SH01与SH04残留闭环

[ChatScreen.kt:626](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt#L626) 现在正常消费只deleteSharedFiles(pending.attachmentFileNames)，[ShareCoordinator.kt:129](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareCoordinator.kt#L129) buffer过期也是只删本buffer附件，旧“消费A删掉生产中B”的正常入口已改变。

但[ShareCoordinator.kt:60](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareCoordinator.kt#L60) stale pending仍调用cleanSharedFiles(keep=liveBufferFileNames)。[SharedShareStore.kt:127](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/SharedShareStore.kt#L127) 遍历整个目录，keep不含正在IO复制或Provider确认中的新文件。MainActivity第482行普通启动也调用processPendingShare：旧pending已过5分钟 → 另一ShareReceiver正IO复制B或在导入选择框 → 用户打开MainActivity → stale目录清理仍可删B。归SH01剩余入口，不重复编号。仅扩大keep到live buffer没有覆盖生产阶段所有权。

[ChatScreen.kt:617](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatScreen.kt#L617) addAttachmentFromStagedShare返回null时现在累加失败数，随后toast，修了静默反馈；第626行仍删除整个pending文件集合。[ChatViewModel.kt:4783](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt#L4783) cacheDir/share_inbound复制失败返回null，原件仍可存在却被调用方删掉。buffer已consume且没有失败项回队，SH04的重试材料缺口仍在，不因有提示就判全部修好。

## 预算、载荷与目标会话

[ShareIntakePolicy.kt:18](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareIntakePolicy.kt#L18) text/plain非空文字优先；空白/无文字但有stream则读附件，其它MIME优先stream再fallback文字。政策已被handleSingleSend实际使用。[ShareReceiverActivity.kt:253](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt#L253) 长文本先计算UTF-8字节并tryConsume，写文件不再完全绕过批次预算。

[ShareIntakePolicy.kt:40](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareIntakePolicy.kt#L40) merge以kind/value去重、保留arrival order，新增项分别限制数量和累计附件字节；已有项不拒绝。ShareCoordinator读取真实staged File.length计量，拒绝项按名字删除并toast，buffer续期。SharedShareStore仍按50项保留最新prefs条目，capped或旧record替换时没有处理被弃文件的所有权，作为同一暂存收尾方向记录，不将它另扩成第二个新编号。

[AppNavigation.kt:452](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt#L452) SESSION_LIST打开新聊天；其它非CHAT路由通过shareNeedsFreshChat也打开新聊天。第473行startDestination用remember(initialDeepLink)，避免shareBufferVersion重组改UUID。只核对当前源码分支，safeNavigate生命周期拒绝/多窗口挂载ChatScreen抢消费仍需独立证据，不声称所有启动场景已实测。

ShareCoordinator的buffer是一次性全局槽，MainActivity调用在主线程；SharedShareStore目前load/clear与save不是专门锁事务，但现役save发生IO返回后的主线程，不能仅凭缺synchronized就宣布并发丢失。不同Activity完成次序可能与分享启动次序不同，也不能未经产品排序约束直接计Bug。

## 已有测试及全文范围

ShareIntakePolicyTest调用生产policy，覆盖SEND载荷、去重、50项、既存不拒绝、累计字节；SharedFileCleanupTest调用真实deleteFilesIn，验证仅删本次文件、拒绝../和嵌套名、缺文件无错误。没有stale目录清理、Provider导入收尾、失败附件重试或真实Activity交接，因此不证明SHR01/SH01/SH04已验收。没有运行它们。

Provider detection限制1MB且只单文件；copyBounded限制URI每项16MiB/批次32MiB，失败恢复预算并删本次dest。未知扩展推断、长文本内存编码和Activity销毁后的阻塞provider读取属于候选，未取真实ContentProvider或压力输入，未计OOM/ANR/越界。PendingShare.fromJson过滤未知kind和空value，路径必须结合生产者安全生成；未把私有记录可构造内容直接当外部路径攻击。

| 新增全文文件 | 行数 |
|---|---:|
| [PendingShare.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/PendingShare.kt#L1) | 53 |
| [ShareCoordinator.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareCoordinator.kt#L1) | 159 |
| [ShareIntakePolicy.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareIntakePolicy.kt#L1) | 64 |
| [ShareReceiverActivity.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/ShareReceiverActivity.kt#L1) | 457 |
| [SharedShareStore.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/main/java/com/openminis/app/share/SharedShareStore.kt#L1) | 133 |
| [ShareIntakePolicyTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/share/ShareIntakePolicyTest.kt#L1) | 78 |
| [SharedFileCleanupTest.kt:1](https://github.com/Slacker-LLC/minis-for-android/blob/37158d188a190416f2b16b45dfb112ecf53cdd7b/src/android/app/src/test/java/com/openminis/app/share/SharedFileCleanupTest.kt#L1) | 56 |

Receiver按1—235、236—457完整读取，其余六文件完整输出，无截断。旧报告/台账混合搜索部分截断，SH01—04单独重读；VM首次定位范围误中另一段，随后4783真实导入调用独立读取，片段不计全文。MainActivity、ChatScreen、AppNavigation、NavigationPolicy、ProviderRepository仅调用闭环。

新累计台账：[2026-10-07-code-review-coverage-37158d18-share-intake.json](/home/jiale/projects/minis-for-android/docs/archive/2026-10-07-code-review-coverage-37158d18-share-intake.json)。全文 **1181 / 1652文件、190368行**；partial4、not_registered467、excluded124，tracked1776。全库阅读尚未完成。

交付核验旧145份报告及所有旧台账hash、固定本地/远端/上游HEAD、tracked/staged空diff、远端1776快照Git blob/hash/bytes/lines及本文证据行号。仅新增本文与新版台账。
