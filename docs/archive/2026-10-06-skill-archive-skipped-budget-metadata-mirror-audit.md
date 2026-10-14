# 技能归档跳过条目的解压预算与元数据自愈测试静态审查

日期：2026-10-06。本仓固定HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。新增全文3文件、649行，确认1项P2与1项P3。仅静态阅读，不改产品源码、不编写或运行测试、不构建/启动模拟器/adb/Root/设备/云/模型，不导入ZIP、不读取真实技能/账号/凭据。独立新报告，旧报告不改。

## SZA01 / P2：被跳过的隐藏文件与目录不受解压字节预算约束，closeEntry仍读取完整展开内容

[SkillArchiveReader.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillArchiveReader.kt:40) reader声明compressed32MiB、单条展开4MiB、总展开16MiB、最多512条。然而 [SkillArchiveReader.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillArchiveReader.kt:72) directory分支直接zip.closeEntry并continue，[SkillArchiveReader.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillArchiveReader.kt:81) hidden/__MACOSX分支也直接closeEntry。只有普通保留条目才走第89行readBounded，并在第90行增加totalBytes。

[AOSP固定libcore源码](https://android.googlesource.com/platform/libcore/+/51b1b6997fd3f980076b8081f7f1165ccc2a4008/ojluni/src/main/java/java/util/zip/ZipInputStream.java)中closeEntry通过重复read至EOF消费当前成员；对DEFLATED成员仍进入解压读取。没有将“跳过而不保存在files列表”误当“不读取payload”。本轮只查询公开平台源码，没有生成压缩负载或在设备测量耗时。

静态反例：ZIP含一个合法小SKILL.md及一个高压缩率的 `.cache/blob`，后者展开超过4MiB、压缩后总归档仍低于32MiB，条目数和路径也合法。validatePath允许.cache，isSkippable=true，整个成员被closeEntry读完，没有MAX_ENTRY_BYTES检查、也不加totalBytes；reader仍可返回合法skill与skippedEntries=1。多个这样成员累计展开超过16MiB也不被totalBytes检测。携带数据的目录名成员同理，不能以通常目录为空替代对输入的限制。

CountingInputStream计算的是底层压缩流读取量，不是展开后的skip数据量；压缩预算仍有效，但不能兑现单条/总展开上限。影响主要是无相应展开预算的CPU/读取工作；跳过内容没有整体ByteArray分配，不声称因此必然把全部隐藏payload装进堆或已发生OOM。

真实导入入口 [SkillsManagementScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/SkillsManagementScreen.kt:427) 先用BoundedStreams把压缩输入限于32MiB，再第453行交给repository；[SkillRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:579) 调reader，随后只将archive.files与重建SKILL.md交安装器。因此现有前置compressed限制没有封住此反例；[SkillPackageInstaller.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillPackageInstaller.kt:632) staging总量限制也只能检查留下的files，不能退回已经完成的隐藏成员解压。失败/成功UI本身不执行这个资源控制。

修复方向：所有被消费的成员，包括跳过内容，统一以有界读取/丢弃统计单条及总展开字节；达到边界立即拒绝并关闭整个流。仅删去显式closeEntry然后继续nextEntry仍会隐式消费前成员，不能当修复。允许正常小隐藏metadata继续跳过，不要求把它保存。未实施。

## STM01 / P3：空名称自愈测试验证手写镜像，生产分支仍不包含其关键条件

[SkillStaleMetadataTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/SkillStaleMetadataTest.kt:50) 测试私有heal函数用nameStale或descStale触发；第59行可独立补空名称。第75行测试“blank name + perfectly fine description”调用这个本地镜像并断言name恢复，而不是调用SkillRepository.loadAll。

生产 [SkillRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1856) 仍仅在description为>/|/blank时进入自愈，第1869行还要求reparsed.description非blank才补name。因此测试中明确给出的状态——storedName空、storedDescription正常、SKILL.md可解析且name正常——在生产不会进入自愈块，name保持空；测试镜像却认为已修复。即使storedDescription空，parsedDescription也空而parsedName正常时，镜像会补name，生产仍不补。

[SkillRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillRepository.kt:1778) loadAll是现役初始化/reload读取链，读取DB name/description，在第1881行将未修复值放进Skill；随后auto-discover只为dbSkills没有的id创建记录，已有空名记录不会因此被替代。没有把这一静态已有记录条件描述成新导入一定产生空名，也没有访问用户SQLite或证明某台设备已有这种旧记录。

本项确认的是回归保护失真与实际自愈条件不一致。测试头注释承认镜像手动同步，本轮发现其已不同步；即使这些纯镜像断言通过，也不证明生产自愈满足同一个行为。方向：先明确需要兑现的空名修复，再让验证覆盖真实生产决策/存储入口，避免维护第二份成功算法。没有为此新增helper或测试。

## 已有归档测试与不计数候选

SkillArchiveReaderTest全文253行，调用生产reader，覆盖root/单层wrapper、SKILL.md到达顺序、普通隐藏/metadata跳过、目录忽略、路径遍历/绝对/反斜杠/重复路径拒绝、缺失/空/重复SKILL.md、普通成员4MiB、数量512、路径长度/深度与输入buffer独立。hidden案例只有4字节，directory只有零字节，超界案例是保留的assets/blob.bin，因此没有覆盖SZA01两种skip分支。

路径安全先于hidden判断，..没有通过“以点开头”越过校验；目录分支未validatePath，但它不在archive.files里，不凭一个../目录名就认定路径穿越写文件。wrapper stripping后可能使不同原始路径归到相同目标，安装器validateStagedSet第617—619行另查collisionKey；不能只看reader缺第二次seen就声称真实覆盖文件。UTF-8替换解码、目录信息不保留等候选未凭实现差异新增数据损坏结论。

SkillStaleMetadataTest全文188行，其其余场景也只调用私有Healed/heal，没有生产DB、文件API或guest runtime。它提供预期行为案例，不能当端到端自愈证据。没有运行两份测试或造任何ZIP/DB记录。

## 固定OpenMinis对照

只读 `/tmp/openminis-review-20261004` 固定HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未fetch。上游没有独立SkillArchiveReader同名源码；SkillRepository第581—603行旧readZipEntries对普通成员无展开budget、directory closeEntry。本地新reader增加了有效普通成员边界，但漏了skip展开计量，不能说新reader毫无保护，也不能因上游更宽松就认为本地声明的预算已兑现。

上游SkillRepository第1461—1499行有nameStale||descStale与changed/独立字段补全，与本地测试镜像接近；本地当前loadAll没有该条件。STM01是具体生产/镜像差异，不依据注释移植编号猜修复状态。上游文件访问旧架构不作为本地存储恢复模板，保留App-owned guest事务/Direct Ubuntu边界。

## 覆盖与完整性复核

| 新增全文文件 | 行数 |
|---|---:|
| [SkillArchiveReader.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/SkillArchiveReader.kt:1) | 208 |
| [SkillArchiveReaderTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/SkillArchiveReaderTest.kt:1) | 253 |
| [SkillStaleMetadataTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/SkillStaleMetadataTest.kt:1) | 188 |

reader全文208行，归档测试分1—145、146—253，自愈测试全文188行，输出完整。相关repository/UI/安装器只读片段，不重复计数。两次混合查询输出截断，涉及自愈与上游实现的片段已单独重读，不升级它们的全文覆盖。

平台核对使用公开AOSP/OpenJDK源码，仅取closeEntry行为；起初几个AOSP路径不可访问，后来固定commit来源检索已提供该循环，不拿访问失败当行为证明。没有执行远端代码或下载ZIP负载。

累计全文 **1339 / 1546文件、259584行**；partial13、not_registered194、excluded124，全仓通读尚未完成。完成时核验全部1670 tracked路径SHA-256/字节数/正数文本行数、两仓固定HEAD、tracked/staged空diff、此前134份报告哈希、新报告绝对链接与行号、全文批次总计。只新建本报告并更新覆盖台账。
