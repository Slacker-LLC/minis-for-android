# 远程备份传输、路径与历史兼容静态审查

日期：2026-10-06。当前仓库固定 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **3 项 P2 问题**，全文阅读 5 个文件、1673 行。独立新报告，不追加或改写旧报告。只静态读取源码及既有依赖缓存，未修改产品代码，未运行测试、构建、模拟器、设备或真实远程传输。

## RT01 / P2：上传前清理会删除并非 Minis 创建的 `.partial` 文件

入口：[上传](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:123)每次开始传输前执行 sweepAbandonedPartials。[清理条件](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:239)只排除目录、要求 Name 以 `.partial` 结尾，并保留这次上传的那一个临时路径；随后直接 operations/deletefile。没有校验 `.minisbak.partial` 命名、来源或其它归属证据。

静态反例：用户把备份目的地配置到共享目录，目录同时有另一程序的 `dataset.partial`、Minis 的 `old.minisbak.partial` 和本次 `new.minisbak.partial`。上传 new 时，前两者都通过同一个删除条件；dataset 并非 Minis 临时文件，仍会被删除。需要远程 listing 能看见该文件且账户有删除权限；不是所有远程文件都受影响，也不声称已经删除真实数据。

[现有 sweep 测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RcloneRemoteSingleFileTest.kt:119)镜像了同一个后缀判断，但 fixture 只有 Minis 风格的 partial、真实备份和目录，没有其它应用的普通 `.partial` 文件。测试源码没有提供对此反例的保护证据，本轮没有执行测试。

建议修复边界：清理至少限定为本应用生成的备份临时命名，再核实共享目录中活跃上传与所有权；不能把通用 `.partial` 后缀当作归属证明。

## RT02 / P2：SFTP 绝对目的地在 RC 文件系统根与子路径之间放错层级

[Remote.fsSpec](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:75)始终返回 `name:`。[join/listRoot](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneRemoteStore.kt:91)虽然为 SFTP 保留 `/srv/backup` 和 `/`，上传 [copyfile 参数](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:160)、列表、下载和删除仍使用 `fs = name:`，把绝对目录放在 remote/srcRemote/dstRemote 子路径里。恢复列表 [listDirectory](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:348)及 [面包屑](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RestoreBrowseScreen.kt:98)又无条件 trim('/')；但问题不能归结为这一处 trim。

沿项目固定 rclone v1.75.0 源码核对：RC GetFsAndRemoteNamed 分别读取 fs 与 remote，并只用 fs 创建文件系统；ParseRemote 对 `name:` 得到空 root。SFTP NewFsWithConnection 会将空 root 通过服务器 RealPath 或 Getwd 解析为登录目录，随后 remotePath 使用 `path.Join(f.absRoot, remote)`。Go Join 是拼接后 Clean，后续参数中的 `/` 不会丢弃先前目录。依据：[Go 标准库实现](https://go.dev/src/path/path.go#L155)。以下结果是源码静态推导，未连接 SFTP。

例如服务器把空路径解析到 `/home/alice`，用户选 `/srv/backup`：即使 join 返回 `/srv/backup/a.minisbak`，最终仍拼成 `/home/alice/srv/backup/a.minisbak`。如果该相对目录存在，上传、统计、最终改名都可能在错误目录完成并报告成功；不存在则请求失败。选择服务器 `/` 则仍操作登录目录。这也说明，删掉 trim 或字符串单测通过，不能证明绝对路径传输正确。登录目录本来就是 `/` 的服务器不会表现出这个偏移。

[现有 SFTP 测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RcloneRemoteSingleFileTest.kt:53)只断言 Kotlin 字符串 join/listRoot，不经过 RC 和 SFTP backend。建议修复时把所选绝对根放到 rclone 文件系统根的位置，再以相对子路径操作，并核对上传、浏览、下载、删除和面包屑的全部调用方。

## RT03 / P2：历史分片备份仍能列出、删除，但现役恢复页无法选择

底层 [listPackages](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:408)仍发现 `.minis-parts/<id>` 并生成 RemotePackage；[download](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:584)也保留拼接分片的读取路径。问题在现役 UI 的调用链：

- 恢复标签点击目的地 [恢复入口](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt:802)进入 RESTORE_BROWSE；“从服务器恢复”进入服务器列表，再进入同一浏览页。导航绑定见 [AppNavigation](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:986)和 [服务器选择导航](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/navigation/AppNavigation.kt:1050)。
- [RestoreBrowseScreen](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RestoreBrowseScreen.kt:89)调用 browseDestination → listDirectory，而不是 listPackages。[listDirectory](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt:357)跳过所有点开头的目录，包括 `.minis-parts`；用户无法进入历史分片目录。
- 恢复页构造的 [RemotePackage](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RestoreBrowseScreen.kt:178)始终 partCount = 1，仅支持所显示的完整文件。
- 旧 ServerPackagePicker 仍调用 downloadServerPackage，但其显示条件是 RestoreTab 局部 browsing 非空。该状态 [初始化](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt:763)为 null；整文件检索只有初始化、读取与关闭时设 null，没有任何设非空的入口。现役按钮已经改为导航，旧选择器不可达。
- [BackupDestinationBrowseScreen](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupDestinationBrowseScreen.kt:65)确实仍调用 listServerPackages，能列出历史分片，但行只提供删除按钮，不提供恢复动作。

静态反例：远程只有完整的 `.minis-parts/old-id/000000…`，没有完整 `.minisbak`。目的地详情仍能看到它并删除；两个现役“从服务器恢复”入口都走隐藏点目录的页面，无法选中这个包。旧分片数据没有被本项逻辑自动删掉，仍可在 App 外自行下载拼接；这里确认的是 App 内历史恢复入口缺失。

[现有 legacy 测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RcloneRemoteSingleFileTest.kt:155)只构造 RemotePackage、断言 isChunked 和排序，不验证任何 UI 到旧包的可达性。建议恢复旧分片的可见、可选择入口，复用现有 listPackages/download，不为此恢复已退出生产路径的 PRoot、broker 等架构。

## OpenMinis 固定上游对照

只读取已有 `/tmp/openminis-review-20261004` 对象，固定 HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，没有 fetch 或运行上游程序。

| 问题 | 固定上游 Android 证据 | 结论 |
|---|---|---|
| RT01 | RcloneChunkedUpload.kt 268—293：同样仅按 `.partial` 删除 | 共享缺陷 |
| RT02 | RcloneRemoteStore.kt 74 返回 `name:`；RcloneChunkedUpload.kt 388—412 同样 trim；RestoreBrowseScreen.kt 102—103 同样 trim | 共享请求构造问题；实际偏移结论依据本仓库固定 rclone 版本，不冒充上游设备实测 |
| RT03 | RcloneChunkedUpload.kt 449—480 保留旧分片发现；RestoreBrowseScreen.kt 199—204 只构造单文件包；BackupAndRestoreScreen.kt 1008/1115/1119 保留 null 状态旧选择器；目的地详情同样只有删除 | 共享 UI 兼容断链 |

## 尚未独立计为新问题

上传存在 isCancelled 参数，但现役 deliverToRemotes 未传取消函数，不能据此虚构一个当前可点击的上传取消入口。旧分片下载确实不读 cancelFlag，且没有每片校验最终总大小；不过本轮先确认了当前 UI 无法选中旧包，因此不把不存在的可达取消交互单独计为新问题。单片历史目录判定、分片缺号及纳秒时间解析仍需结合实际历史格式/平台实现继续核实。

正常单文件下载已经使用异步 job/stop；不声称全部下载都无法取消。此前 TLS 全局降级、凭据残留、ZIP 解包预算和备份路径越界不重复计入本轮。

## 阅读覆盖与复核

| 本轮新增全文文件 | 行数 |
|---|---:|
| [RcloneChunkedUpload.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/remote/RcloneChunkedUpload.kt) | 679 |
| [RcloneRemoteSingleFileTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/RcloneRemoteSingleFileTest.kt) | 201 |
| [RestoreBrowseScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RestoreBrowseScreen.kt) | 476 |
| [BackupDestinationBrowseScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupDestinationBrowseScreen.kt) | 196 |
| [RestoreServersScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/RestoreServersScreen.kt) | 121 |

累计全文 **988 / 1546 个文件、200754 行**；partial 16，not_registered 542，excluded 124。全仓通读尚未完成。BackupViewModel 只增补实际片段，BackupAndRestoreScreen 只登记本轮读取的 740—880、1430—1475，AppNavigation 此前已 full，复核不重复增加。

较长合并输出曾截断，已单独补读 RestoreBrowseScreen 216—350 和 ServerPackagePicker 1448—1475；没有用截断输出冒充全文。合同只用于边界核对，不增加新全文覆盖数。

已复核所有 1670 个 tracked 路径、SHA-256、字节数、正数文本行数，仓库固定 HEAD、tracked/staged diff 为空；此前 70 份报告 SHA-256 保持不变；覆盖台账批次总数和全文行数一致，当前报告文件链接及行号均核对。

固定依赖缓存仅只读关键片段，未计入本仓库全文统计。用于 RT02 的文件与 SHA-256：
- [fs/rc/cache.go](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/rc/cache.go)：`43a465bc4c1af72d7bf238184a03c02cfb3f6175ba9a2051e2a2e4e05741ab53`。
- [fs/operations/rc.go](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/operations/rc.go)：`91d443428c9f73c807d7a62c02c7161f31fd692cd4a44c0163c9aa3de4b598c0`。
- [fs/newfs.go](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/newfs.go)：`dbdb53257366165bf64cdc641aca76528b201ed155c07424c883a1636b627530`。
- [fs/fspath/path.go](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/fs/fspath/path.go)：`882886f0275d52a25652ebfeb3a471aa32d0c5d09d30a58162928687a489a3c6`。
- [backend/sftp/sftp.go](/home/jiale/go/pkg/mod/github.com/rclone/rclone@v1.75.0/backend/sftp/sftp.go)：`dfedcdad9b436b89cf7f62520623db0552ce9e6721fadd7ef29348b93755ede9`。
