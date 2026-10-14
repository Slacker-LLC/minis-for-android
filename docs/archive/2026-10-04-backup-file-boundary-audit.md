# Minis for Android — 备份、文件及下载新增审计

日期：2026-10-04。独立报告，不修改此前两份报告；以下 9 项不重复计算已有的 46 项。

## 基线与验证范围

- 本地：`main` / `d64e7c06d8159be90737dd9ba03dd9bd5855f339`，审阅时工作树。
- 上游：[OpenMinis 固定提交](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c)，使用 `/tmp/openminis-review-20261004` 的只读检出。
- 审阅实际 UI/CLI 入口、备份导出、包认证、完整性检查、解密、文件恢复，以及文件写入的宿主实现；读取已有相关测试，没有编写新测试。
- 本轮仅新增本报告。未修改产品代码、合同或旧报告，未执行 Android 构建、设备命令、Root 操作或设备端到端测试。
- 九项均有当前源码和具体触发条件。下文是静态控制流结论，不是真机复现记录；未声称发生过真实泄露、篡改或数据损失。端到端验收均是修复后应执行的建议。
- 旧报告保全 SHA-256：`2026-10-04-project-audit.md` 为 `9a909370f95462d951897c81e1f38809699d7ff9a8f1f89e14f8b8f28f0319ca`；`2026-10-04-follow-up-audit.md` 为 `3104db736446dcc072f1fd85e7d0c19c0ae123d8da795082350ee87e6f52c71a`。

| 编号 | 优先级 | 新发现 |
|---|---|---|
| [BF01](#bf01) | P1 | 恢复索引的 sha256 未校验，blob 来源可以跳出包目录 |
| [BF02](#bf02) | P1 | 备份遍历跟随软链接，把未选中的宿主文件带入备份 |
| [BF03](#bf03) | P1 | 删除 manifest.mac 即可跳过加密备份的 manifest 认证 |
| [BF04](#bf04) | P1 | 加密备份接受未列入清单的明文数据成员 |
| [BF05](#bf05) | P1 | 写文件会递归删除已有目录，且所谓原子覆盖先删后移 |
| [BF06](#bf06) | P2 | 文件恢复写入失败只记日志，没有进入失败报告 |
| [BF07](#bf07) | P1 | 下载重名检查只看目录前 500 项，会覆盖后面的已有文件 |
| [BF08](#bf08) | P2 | 长单行的续读游标跳过未读内容 |
| [BF09](#bf09) | P2 | 编辑替换文件没有保留原权限，可执行脚本变成普通文件 |

优先级按数据边界和不可恢复损失判断；P1 不等于无需用户操作即可远程利用。BF01–BF04 都需要用户导入或导出相应备份，BF05 需要工具/文件 API 调用。

<a id="bf01"></a>
## BF01 — blob 来源路径未受约束

### 触发与根因

恢复文件已经校验输出路径，但随后用包内索引的 `sha256` 原文构造源文件路径，没有要求它是 64 位十六进制摘要，也没有校验该源位于 blobs 根目录内。

- [BackupFormat.kt:306](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupFormat.kt:306)：`BackupFileIndexEntry.sha256` 是普通可空 String，没有格式约束。
- [BackupImporter.kt:1026](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1026)：读取索引时直接反序列化，没有额外摘要验证。
- [BackupRestoreFiles.kt:94](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupRestoreFiles.kt:94)：执行 `File(File(File(packageRoot, "blobs"), sha.take(2)), sha)`，随后只判断 `blob.isFile` 并复制。
- [BackupImporter.kt:752](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:752)：技能 payload 读取也重复使用这个来源拼接，然后读取其字节。技能事务保护目的树，不约束已经读取的 blob 来源。
- [BackupImporter.kt:586](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:586)：真实 Shared Files 导入入口把此结果写入 App-owned shared，下一次 guest 启动可见。

可构造索引项：

```json
{"path":"shared/canary-copy.txt","size":1,"sha256":"../../outside-canary.txt","category":"shared_files"}
```

`shared_files` 是当前 Shared Files 分类的实际 key。包中同时包含一个空 `blobs/` 目录，使路径中间组件实际存在。由此源路径为：

```text
<packageRoot>/blobs/../../../outside-canary.txt
                 ^ 第一段 .. 来自 sha.take(2)，后两段来自 sha 本身
```

准确的组件表达式是 `<packageRoot>/blobs/../` + `../../outside-canary.txt`，规范化后是 `<packageRoot>/../../outside-canary.txt`。是否能读到目标取决于文件实际存在及 App UID 的权限。

### 影响与限制

攻击者不必把逃逸路径放在 ZIP entry 名称里：普通 ZIP 条目加一个恶意索引即可绕过 ZIP 的路径检查。恢复器可能读取包外的 App-UID 可读文件，并写到合法的 shared/会话/技能目的地。

对于未加密包，攻击者可提供自己计算的完整性清单；完整性检查并不验证索引中的 blob 地址属于包。不能把 SHA-256 完整性校验当成路径授权。没有证明能读取 Root-only 文件或其他 UID 私有文件。

**上游对照：** 固定上游 `BackupRestoreFiles` 也直接拼接 `sha.take(2)` 和 `sha`，来源约束缺失共有。技能事务分支以本地当前实现为准。

**修复方向：** 在所有 blob 读取入口统一验证摘要格式和来源边界，拒绝非法索引；保证最终读取不跟随包外链接。复用现有安全文件访问能力，不只补 ZIP 名称检查。

**端到端验收：** 在隔离的 App 测试目录放置包外 canary，分别用合法 SHA、含 `..`、斜杠、绝对路径和超长摘要恢复 Shared Files/Chats/Skills。非法输入应拒绝且不生成 canary 副本；保留构造包、匿名化来源/目标路径及最终文件摘要。

<a id="bf02"></a>
## BF02 — 导出跟随软链接，跨过备份分类边界

### 触发与根因

在会话 workspace、shared、技能附件或 memory 中存在一个软链接，指向该分类之外的宿主文件/目录。只勾选相应分类导出，树遍历与后续文件读取会跟随链接。

- [BackupFileTreeExporter.kt:66](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupFileTreeExporter.kt:66) 仅对起始根做 canonical 化，再使用 `walkTopDown()`；`:80` / `:89` 通过 `File.isDirectory` / `isFile` 判断，未检查 symlink，也未限制每个文件的 canonical 来源。
- [BackupBlobStore.kt:125](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupBlobStore.kt:125) 对这些 File 调用 inputStream 和 copyTo，实际读取链接目标。
- [BackupExporter.kt:133](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:133) 的真实分类入口使用该 exporter；`:421` 指向 `filesDir/minis-global/shared`。
- [BackupExporter.kt:459](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:459) 的 memory 分支另行使用同样的 `walkTopDown().filter { it.isFile }`，也会跟随链接。
- [SafeFileTree.kt:12](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SafeFileTree.kt:12) 已明确写出 Kotlin File walker 跟随目录 symlink 的原因，并提供不跟随链接的递归删除；备份没有落实这条原则。

相对逻辑路径仍显示为 `shared/link/...` 等所选分类，看起来像正常用户文件，但字节来自目录之外。宿主可读的绝对 Android 路径链接，即使在 chroot 内无法解析，导出进程也可能解析成功。

### 影响与限制

`includeCredentials=false` 只控制显式 `secrets.json` 收集，[BackupExporter.kt:169](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:169) 不会过滤被当作普通文件读入的私有偏好、审计、数据库或其他会话数据。因此“未选择该分类/未包含凭据”不再保证这些文件被排除。

这不等于所有凭据都会以明文出现：加密偏好文件复制后仍可能是密文；目标需存在、App UID 可读，并通过导出的大小/时间等筛选。链接形成环还可能造成遍历膨胀，但本项主要结论是来源范围越界。

**上游对照：** 固定上游 Android 的树 exporter 与 memory 分支同样跟随 File walker；增加取消检查没有解决来源边界。

**修复方向：** 对 guest-controlled 树使用不跟随链接的遍历与读取，明确软链接的备份策略；不要只检查根目录 canonical 路径。沿用项目已有 NOFOLLOW/目录句柄模式。

**端到端验收：** 从 guest shell 在 shared/workspace/memory 内创建指向其他分类及宿主 canary 的链接，只导出一个分类且关闭凭据；包内不得出现外部 canary。加入目录链接、文件链接和链接环，保留包清单和 canary 搜索结果，不使用真实秘密做验证。

<a id="bf03"></a>
## BF03 — manifest 认证可因 sidecar 被删除而消失

### 触发与根因

对一个本 App 正常导出的加密包，删除 `manifest.mac`，再改 manifest 的非 KDF/非 verifier 字段。用户输入原密码时，unlock 不再验证 manifest MAC。

- [BackupExporter.kt:202](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:202) 对当前加密包写入 `manifest.mac`，不写嵌入式 `manifest_mac`。
- [BackupPackageReader.kt:99](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt:99) 校验密码 verifier 后调用 `verifyManifestAuthenticity`。
- [BackupPackageReader.kt:121](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt:121) 仅在 sidecar 存在时验证；没有 sidecar 时，即使 embedded MAC 存在也只写日志；两者都不存在直接正常返回。
- [BackupPackageReader.kt:151](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt:151) 完整性检查相信 manifest 自己给出的路径和哈希，不能替代对 manifest 的认证。攻击者也能把 integrity 改成空表。
- [BackupImporter.kt:172](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:172) 的生产恢复入口使用这一 unlock。

### 影响与限制

不需要知道密码即可改变 backup ID、设备名、分类统计/选择依据、完整性清单等 manifest 内容，并让原密码校验和后续导入继续。单独篡改密文仍会受到 AES-GCM 校验，不能将本项描述成破解加密或获取密码。修改 KDF 参数通常会影响 verifier，本项不依赖该操作。

代码将无 sidecar 情况解释为旧格式兼容，但当前包被删 sidecar 后也走同一宽松路径；没有区分可验证的历史格式，也没有向用户明确提示“manifest 未认证”。

**上游对照：** 固定上游 Android 的同名认证函数有相同宽松回退，缺陷共有。

**修复方向：** 当前加密格式必须要求可验证认证数据，缺失即拒绝；确实需要的历史兼容应有明确格式识别和降级提示，不能把任何被删认证成员的当前包自动视为旧包。

**端到端验收：** 对正常加密包分别保留/删除/修改 MAC，并篡改 manifest 分类和 integrity；保留 MAC 的篡改与缺失 MAC 的当前包均必须在写任何用户数据前失败。保存原包、变体包和匿名化拒绝原因。

<a id="bf04"></a>
## BF04 — 加密包仍导入未认证的新增明文成员

### 触发与根因

在一个原有 MEMORY 分类的正常加密包里加入新的明文 `data/memory/canary.md`，不修改 manifest、不修改 `manifest.mac`、不触碰任何已有密文或哈希。只要这个逻辑路径没有原来的 `.enc` 对应成员，处理顺序也不会影响注入结果。

- [BackupPackageReader.kt:151](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt:151) 只检查 integrity 列出的文件，没有检查包内是否多出数据成员。
- [BackupImporter.kt:1006](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1006) 的 `decryptMembers` 遍历包内所有文件；`:1018` 对 `.enc` 做认证解密，但 `:1020` 对非 `.enc` 文件直接复制到 work。
- [BackupImporter.kt:803](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:803) 的 memory 恢复遍历 work 中全部文件并复制到 App-owned memory，没有要求成员在原 manifest 或完整性清单中。
- [BackupExporter.kt:752](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:752) 正常加密流程对数据成员统一增加 `.enc`；这里接受的新增明文并非该流程生成的普通 memory 成员。

### 影响与限制

即使 manifest MAC 存在且有效、完整性检查开启，也可将未经密码认证的数据带入被标为加密的恢复结果。与 BF03 独立：本项无需删除 MAC 或篡改 manifest。

memory 是明确的生产输入例子；不要把所有文件类型都说成必然可注入同样效果。与已有密文同名的明文还存在目标冲突，但覆盖胜负依赖目录遍历顺序，本报告不用该不稳定场景证明本项。

**上游对照：** 固定上游 Android 的 `decryptMembers` 同样复制所有明文文件、完整性检查同样不拒绝额外成员，缺陷共有。`BackupPackageReader.materialize` 只在测试中有调用，本项依据真实 importer，未把那个 helper 当作生产入口。

**修复方向：** 按认证后的 manifest 确定可恢复的成员集合与加密形态；加密包仅允许明确规定的明文元数据，拒绝未声明的数据成员和解密后的逻辑路径碰撞。不要通过关闭 MAC/完整性检查处理兼容。

**端到端验收：** 在保留有效 MAC 的加密包中添加新的明文 memory 文件、替代索引及重复逻辑路径，恢复必须拒绝未认证数据；正常跨平台包仍应通过。保存变体包成员清单、MAC 检查结果与恢复后 canary 不存在的证据。

<a id="bf05"></a>
## BF05 — 普通文件覆盖调用了递归删除

### 触发与根因

用 `file_write` 的默认覆盖模式写入 `/workspace/project`，而该路径实际是包含用户文件的目录。当前实现不会以“目标是目录”拒绝，而是删除目录及内容，再用临时文件替代它。

- [FileWriteTool.kt:16](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileWriteTool.kt:16) 定义为写/创建文件；`:103` 进入 WorkspaceFileClient，没有目标目录拒绝。
- [WorkspaceFileClient.kt:76](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:76) 校验路径可写后调用 SecureFileAccess.writeBytes。
- [SecureFileAccess.kt:339](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:339) 的 `writeAtomic` 只检查 parent.leaf 非空，没有检查 leaf 实际类型；`:349` 调用 `deleteEntryIfPresent`。
- [SecureFileAccess.kt:526](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:526) 在发现目录时递归删除全部子项，最后删除目录。
- [SecureFileAccess.kt:173](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:173) 的 writeStream、`:329` 的 copyFile 也使用此替换逻辑；`:100` 的 readToFile 另用递归删除目的地。

session path 的权限校验允许正常 workspace 写入，不能阻止这个类型错误。预读 revision 能拒绝目录，但 expected_sha256 是可选 transport 参数，普通模型调用不提供它。append 分支会检查是否为普通文件，本项明确针对覆盖模式。

### 同一替换逻辑也不具备所称的原子性

写临时文件后先删除旧目标，再 `move`，两个操作之间目标不存在。进程在此窗口停止、或者 move 失败时，finally 仅清理临时文件，没有恢复旧目标；可能丢失已存在的内容。此处没有声称路径约束/NOFOLLOW 本身失效。

这是文件工具的单文件提交问题，与旧报告 F35 的备份数据库事务/取消缺口不是同一条链。

**上游对照：** 固定上游 FileWriteTool 用 `File.writeText` / `appendText` 写入解析的目标；目标是目录时会报错，未使用本地这套递归删除替换。不能因此称上游普通写入具备原子性；本项的目录递归删除是本地安全文件实现带入的行为。

**修复方向：** 普通文件写入先拒绝目录/特殊类型，替换文件使用同一目录句柄下的真正原子 rename/replace；递归删除仅供显式删除操作。保持现有 NOFOLLOW、session 和 SAF 权限校验，不退回不受约束的 File 路径操作。

**端到端验收：** 经真实 file_write 写入包含 canary 的目录，必须拒绝且所有子项完整；正常覆盖同一普通文件，在提交边界终止进程或制造 rename 失败，重启后只能看到完整旧版本或完整新版本。保存调用响应、目录摘要及重启检查工件。

<a id="bf06"></a>
## BF06 — 恢复写入失败没有进入可见报告

### 触发与根因

本地 shared 中已有非空目录 `conflict`；备份索引将 `shared/conflict` 表示为普通文件，并携带可读取的合法 blob。来源、目标路径都合法，但无法把该普通文件 rename 到非空目录上。

- [BackupRestoreFiles.kt:128](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupRestoreFiles.kt:128) 复制临时文件后尝试 rename；目标非空目录时 rename 失败，`:133` 删除目录也失败。
- `:137` catch 只记 AppLogger、删除临时文件并返回 false。
- [BackupRestoreFiles.kt:106](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupRestoreFiles.kt:106) 只在返回 true 时增加 written/bytes；false 没有 failedWrites/unreadable 等计数，也不抛到类别错误层。
- [BackupImporter.kt:988](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:988) 只能汇总这个 Result 已有的计数，无法获知实际写入失败。
- [BackupAndRestoreScreen.kt:1061](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt:1061) 的问题区列出 failed、tombstone、missingBlobs、unreadable 等，但上述失败不进入这些字段。

空间不足、复制 I/O 错误或无法提交目标也走同一 catch。用目录冲突证明本项，无需假设特定设备的磁盘故障。

### 影响与限制

恢复正常返回，失败文件只留下日志；完成页没有对应的文件失败原因。文件计数可能显示少于预期，Importer 也可能输出 SHORT 日志，所以不能称系统完全没有任何异常痕迹。核心缺口是报告没有记录这次真实失败，用户无法从完成报告知道哪些文件未恢复。

BF05 是“写文件意外删除目录”；本项是备份恢复发生正常拒绝后没有上报，处理方向不同。

**上游对照：** 固定上游 BackupRestoreFiles 同样用 Boolean 吞掉写入错误且没有失败计数，缺陷共有。

**修复方向：** 保留逐文件错误路径/类型并计入类别结果和完成页；允许部分恢复，但不能把写入失败隐藏在未计数的 false 中。复用现有 Report/warnings 边界即可。

**端到端验收：** 用文件/目录冲突与受控空间不足执行实际恢复，完整保留既有目录；完成页和持久化结果明确列出失败路径及原因，成功文件仍可恢复。保存冲突包、恢复报告和前后文件清单。

<a id="bf07"></a>
## BF07 — 重名检测把目录首屏当成全部内容

**明确触发：** 同一 workspace 内有 500 个按名称排序在前的 `a000.txt` 至 `a499.txt`，以及已有 `z-report.pdf`。浏览器再下载一个名为 `z-report.pdf` 的文件。

- [WorkspaceFileClient.kt:99](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/WorkspaceFileClient.kt:99) 的 `uniqueChildPath` 只调用 `list(..., 500, 0)`，把返回名字当作完整 used 集合，未读 `next_offset`；`:109` 因而认为 `z-report.pdf` 未被使用。
- [SecureFileAccess.kt:282](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:282) 确实按名称排序并分页，提供 next offset；第 501 项不在上述 used 集合中。
- [BrowserTabPool.kt:423](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/browser/BrowserTabPool.kt:423) 的真实 URL 下载使用这个名字分配器，`:451` 通过 writeStream 保存；提交会替换已有同名文件。
- [BrowserUseOffloadHandler.kt:305](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/BrowserUseOffloadHandler.kt:305) 的浏览器产物保存、[ModelUseOffloadHandler.kt:1290](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/guest/ModelUseOffloadHandler.kt:1290) 的模型附件命名也使用同一 helper。

名字查询失败还会被 catch 变成空集合。并行下载之间没有保留名字的原子创建，因此即使目录少于 500 项也有同时选中同名的风险；本项的 501 项例子不依赖并发。

**影响：** helper 承诺“直到未使用”，调用方没有显式要求覆盖，却覆盖已有用户文件或生成产物。

**上游对照：** 固定上游 BrowserTabPool 的 uniqueFile 对每个候选执行 exists，未受目录首屏限制；它同样不是原子名字预留，不能当作并发分配问题已彻底解决。

**修复方向：** 对候选路径直接检查/原子保留，或完整分页；列表失败应明确失败，不能视为没有文件。保留 session 语义与安全访问约束。

**端到端验收：** 准备 501 个文件，从实际 WebView 下载同名文件，原文件摘要不变，新文件另取名字；并行下载两个同名文件均保留完整内容。保存下载事件、路径和文件摘要。

<a id="bf08"></a>
## BF08 — 长单行被截断后，next_offset 跳到下一行

**明确触发：** 文本第一行含 20,000 个 ASCII 字符，第二行是 `END`。用默认 `file_read` 读取，随后按返回的 `next_offset` 续读。

- [FileReadTool.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileReadTool.kt:44) 默认 max_length 为 15,000，最高为 80,000；offset 是行号，没有行内游标。
- [FileReadOutputFormatter.kt:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileReadOutputFormatter.kt:50) 先 take(maxLength)，若这段没有换行，`:54` 不会退到完整行；`:55` 把部分第一行计作已显示第 1 行，`:56` 返回 `next_offset=2`。
- [FileReadTool.kt:89](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileReadTool.kt:89) 的下一次 offset=2 从第二行开始，第一行后 5,000 字符永远没有通过这轮续读返回。

对于超过 160,000 字符的纯单行文件，head 和 tail 各最多 80,000 字符，行号分页无法读取中间部分；“retry with a smaller lines value”也无助于分割这唯一的一行。minified JSON、压缩成一行的源码和较长文本日志都可能满足条件。

**影响与限制：** 文件本身未被修改，返回也标记了 truncated；缺陷是给出的续读指令丢内容，不能称工具隐藏了所有截断信息。通过 shell 或其他字节读取接口仍可能读到完整文件。

**上游对照：** 固定上游 FileReadTool 内联了相同 take/lastNewline/nextOffset 逻辑，缺陷共有；本地将其抽到 formatter 后仍保留该问题。

**修复方向：** 为超长行提供可前进的行内/字节游标，或明确提示该行无法通过行号分页完整读取；不能把只返回一部分的行当作已读完。

**端到端验收：** 经真实 file_read 读取混合短行/长行、200,000 字符单行和 Unicode 长行，逐页重组内容并对比摘要；游标必须前进且不丢字符。保存请求、响应和重组摘要。

<a id="bf09"></a>
## BF09 — 修改内容时丢掉原文件权限

**明确触发：** workspace 中有一个 chmod 700 的 `run.sh`，通过 file_edit 修改其中一行后，再直接执行 `./run.sh`。

- [FileEditTool.kt:115](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/FileEditTool.kt:115) 完成内容匹配后调用 WorkspaceFileClient.writeBytes，而不是就地修改。
- [SecureFileAccess.kt:345](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/files/SecureFileAccess.kt:345) 创建全新的临时文件，没有提供原目标 FileAttribute，也未读取/复制原 POSIX mode；随后删除旧文件并移动临时文件。
- 同文件中没有恢复权限的调用。copyFile `:324` 及 writeStream `:155` 同样创建新文件；append 没有更换 inode，不属于本例。

新文件采用平台的新建普通文件权限及进程 umask，原来的执行位/权限设置不会自动随路径名字保留下来。AOSP 的 [UnixSecureDirectoryStream.newByteChannel](https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/sun/nio/fs/UnixSecureDirectoryStream.java#160) 使用 [ALL_READWRITE](https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/sun/nio/fs/UnixFileModeAttribute.java#37) 作为无 attributes 时的创建模式，只有读写位，没有执行位。具体最终 mode 要在目标 Android/OEM 上记录，不能仅凭宿主结果指定为固定 0600；脚本会因执行位未保留而在直接执行时失败。AOSP 参考读取于本轮审阅，main 链接后续可能变化。

**影响：** 文件编辑只改变文本，却同时改变未请求的元数据；执行脚本和依赖权限的工具链出现额外故障。BF05 要修提交原子性与目标类型；本项还需单独保留原元数据，不能因修成一次 rename 就认为解决。

**上游对照：** 固定上游 FileEditTool `:106` 用 File.writeText 修改现有文件，普通就地覆盖会保留原 inode 权限；本地切换为新 inode 替换后没有带回此行为。上游方式没有提供原子内容提交，不建议直接回退为上游普通写法。

**修复方向：** 在受控目录句柄内保留已有普通文件应保留的权限，再原子提交；新增文件沿用明确默认权限。不要默认给全部新文件添加执行位。

**端到端验收：** 用 guest shell 准备不同 mode 的文件，经 file_edit/file_write 修改后读取 mode；可执行脚本应仍能直接执行，受限文件不得被无意放宽。保存修改前后 stat、调用响应和脚本执行结果。

## 本轮检查与后续边界

- 已追到生产恢复入口；没有把仅测试使用的 materialize 当作生产漏洞入口。
- ZIP entry traversal guard 已存在，本轮不把“ZIP 解压完全无路径保护”当成新问题。
- 备份迁移位置与 App-owned backing 一致；本轮关注来源校验、认证覆盖和文件提交，不重新质疑已确认的目录映射。
- 额外发现 KDF iterations 缺少上界、解压缺少总体预算、按原始路径分配文件修改锁等候选，尚未完成资源消耗/竞争时序复核，不列入这九项。
- 交付前检查 Markdown 本地链接/行号、九个锚点、两份旧报告保全哈希，并执行工程规范中的两项文档检查。这些检查不替代设备行为验证。
