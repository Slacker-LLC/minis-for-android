# 备份加密成员命名与全文导出审查

日期：2026-10-06。仓库固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **2 项 P2 问题**，新增全文阅读 4 个文件、1440 行。独立新报告，旧报告不改。仅静态源码审查，没有编译、运行测试、生成加密样本、启动模拟器或访问设备/账户。

## BENAME01 / P2：记忆里的普通 `manifest.json` 在加密备份中仍是明文，而且不纳入完整性清单

[exportMemory](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:459)复制记忆目录下所有普通文件，保留相对路径，不限制扩展名或保留文件名。生产 backing 来自 [UbuntuPaths](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuPaths.kt:103)的 filesDir/minis-global/memory，[UbuntuKernel](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/runtime/ubuntu/UbuntuKernel.kt:556)将其 bind 到 guest `/memory`。这不是不可达的 exporter 测试目录；恢复 [importMemory](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:804)也接收该目录下的所有普通文件。

但是 [encryptStagedMembers](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:754)用 `file.name != "manifest.json"` 排除明文元数据；[buildManifest](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:792)生成 integrity 时使用同一 basename 排除。两个条件都不核对“包根目录”的路径。

静态反例：记忆目录里有用户的 `manifest.json` 或子目录 `project/manifest.json`，内容是普通项目资料。选择 MEMORY 并设置备份密码后，该文件先被复制成 `data/memory/manifest.json`，随后被加密循环跳过；[ZIP archive](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupZip.kt:119)仍将其作为普通文件打包。manifest 的 MEMORY encrypted 标记却已经在 [类别标记](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:192)统一设为 true。

拿到包的第三方无需密码即可读取该文件；此文件也没有 integrity 条目。这里不声称整个包、正常 `.md` 记忆或 secrets.json 都未加密，条件限定为记忆中存在同 basename 的普通数据文件。

正确 passphrase 解锁后，[decryptMembers](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1020)把这个明文成员直接复制，importMemory 又恢复它。[verifyIntegrity](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt:151)只验证 manifest 声明的成员，所以正常校验不会检查被排除的该文件。MAC 认证的是根 manifest 字节，不会自动认证未列入 integrity 的文件内容。

这与旧 BF04 的“第三方给加密包添加明文成员后被导入”不同：本项是 App 自己生成的正常包在出口已经漏加密、漏哈希，无须攻击者追加成员。沿恢复链的宽松接收只用于确认真实影响，不重复计为新问题。

修复方向：只允许根路径的正式 manifest 保持明文，用户目录中的同名数据仍应加密并纳入完整性范围；按完整逻辑路径识别元数据，不能只比较 basename。

## BENAME02 / P2：加密输出 `.enc` 覆盖尚未处理的普通源文件，包能正常通过完整性检查却丢失内容

[加密成员快照](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:753)先把 staging 的所有待处理文件保存为 File 对象列表，随后在同一目录直接生成 `${file.name}.enc` 并删除原路径。[encryptFile](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupCrypto.kt:185)使用 destination.outputStream，不拒绝目标已存在，也没有独立输出树。File 对象保存的是路径，不是原文件的内容快照。

条件反例：记忆目录同时有 `memo.md`（内容 A）和用户已有的 `memo.md.enc`（内容 B）。exportMemory 原样复制二者；若 walkTopDown 的文件遍历先到 memo.md，再到 memo.md.enc，会发生：

1. 加密 A → memo.md.enc，打开输出流会覆盖 B；删除 memo.md。
2. 列表里的第二个 File 仍指向 memo.md.enc，但该路径已经变成 A 的密文，不再是 B。
3. 再次加密这个密文 → memo.md.enc.enc，删除 memo.md.enc。包里最后只剩双后缀文件。

[buildManifest](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:791)在覆盖发生后才计算哈希，因此会为双后缀文件记录正确的现存密文字节，根 manifest 的 MAC 也能正常生成。没有哈希碰撞或错误密码。恢复时 decryptMembers 只 removeSuffix 一次，得到 memo.md.enc；内容是内层加密 A 的字节。原 memo.md 的路径消失，B 也不在备份中；导入新设备会留下错误文件，在同设备恢复还可覆盖已有 B。

影响限定：取决于存在后缀冲突文件和该遍历顺序。若 memo.md.enc 先处理，该例可以正常保留两个文件；不能把每次备份都说成必然丢失。源码没有排序、排他创建或碰撞保护，故存在可执行的失败顺序。本轮未执行加密、遍历或设备实验。

[MEMORY 统计](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:466)在复制阶段已经计入两个文件；后续 Summary 的 skippedFiles 只来自 blobStore，而这条原始 memory 复制路径不经 blobStore。正常最终归档不能证明所有原始成员都仍存在，亦不能依靠现有完整性哈希发现这种导出过程中的覆盖。

修复方向：生成密文时保留未处理源文件，使用不会覆盖输入成员的暂存/提交方式，或在提交前可靠识别并拒绝命名冲突。核对单后缀、多后缀与目录冲突，不能单靠修改哈希或让 importer 再多次解密掩盖导出损坏。

## 固定 OpenMinis 对照

仅只读已有 `/tmp/openminis-review-20261004` Git 对象，HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，没有 fetch 或运行程序。

| 问题 | 固定上游 Android 证据 | 结论 |
|---|---|---|
| BENAME01 | BackupExporter.kt 378—396 同样复制所有 memory 文件；607—622 加密排除所有 basename manifest.json；647—652 完整性也按 basename 排除 | 共享缺陷 |
| BENAME02 | BackupExporter.kt 609—620 保存 File 列表后原目录追加 .enc 写入、删除源；BackupImporter.kt 916—933 解密只移除一次 .enc | 共享命名冲突路径 |

这些是固定源码对照，不是上游或本仓库的设备测试结果，也不以 iOS 注释作为实现证据。

## 现有测试与尚未新增计数的候选

本轮通读已有 BackupFileTreeExporterTest、BackupRoundTripInstrumentedTest 和 CrossPlatformSessionShapeTest。前者验证 blob/index 与树遍历，未触达 memory 原样复制后的加密命名；设备 round-trip 虽调用真实 Exporter/Importer，但选择只有 CHATS 与 PROVIDERS，没有 MEMORY，也没构造 manifest.json 或 .enc 冲突文件；跨平台测试验证 JSON 嵌套字段。三个测试均没有运行，不能称本轮验证通过。

完整导出器还显示：过长提示词与超过 200 的例程被导出过滤，多个 category 没有使用 snapshotAt，坏 partsJson 会回退为空数组。需继续对照正常写入限制、实际数据格式和用户可见结果，暂不凭这些表达式新增问题。此前角色 500 条截断、blob 二次读取、凭据策略、停止备份问题不重复计数。

## 覆盖与复核

| 新增全文文件 | 行数 |
|---|---:|
| [BackupExporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt)（原 partial，已补齐全文） | 913 |
| [BackupFileTreeExporterTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupFileTreeExporterTest.kt) | 214 |
| [BackupRoundTripInstrumentedTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/androidTest/java/com/openminis/app/backup/BackupRoundTripInstrumentedTest.kt) | 209 |
| [CrossPlatformSessionShapeTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/CrossPlatformSessionShapeTest.kt) | 104 |

累计全文 **996 / 1546 个文件、203895 行**；partial 14，not_registered 536，excluded 124。全仓通读尚未完成。Exporter 按 1—240、241—460、461—700、701—913 分段干净读取后才升级 full；没有用搜索命中替代全文。

Importer 只补读 156—245、790—838、999—1045，继续 partial。Crypto、Reader、Zip、UbuntuPaths、UbuntuKernel 此前已经 full，本轮片段复核不重复计数。

已检查全仓 1670 个 tracked 路径、SHA-256、字节数与正数文本行数，固定 HEAD 和 tracked/staged 空 diff；此前 72 份旧报告保持原哈希；当前报告所有本地文件链接和行号、台账全文与批次总计均已核对。未读真实凭据，未触发任何加密或解密程序。
