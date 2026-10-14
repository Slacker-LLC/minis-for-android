# 备份 Blob 一致性与 KDF 工作量静态审查

日期：2026-10-06。新增 2 项 P2 问题。只新建独立报告、更新阅读台账，不修改产品代码和旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读既有 Git 对象，没有拉取或执行上游。没有构建、执行测试、模拟器、adb、Root、设备、云任务、账号、凭据或子 Agent 操作。没有生成、解密、篡改或导入备份，也没有执行高成本 KDF。

## BK01 · P2 · 算摘要与复制分两次读取，备份内容可能与去重键不一致

[BackupBlobStore.addFile](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupBlobStore.kt:112)先获取长度、读取源文件计算 SHA256，再 [copyTo](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupBlobStore.kt:134)重新打开源文件复制。未从实际写入 staging 的同一字节流计算摘要，也没有在读取/复制之间固定内容版本。seen 与索引使用第一次读取的 digest，size 使用更早的 stat。

[真实共享文件导出](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:422)直接遍历 App-owned minis-global/shared；不是不可变快照副本。[文件树过滤](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupFileTreeExporter.kt:94)只在读取前检查 mtime，不能阻止之后普通 Agent/用户写入。

静态交错：源 x 最初为 A，hash 得到 h(A)；复制前 x 被改为同长度 B；备份把 B 写到 blobs/.../h(A)，索引仍指 h(A)。之后另一个内容为 A 的 y 被当作 Duplicate，索引也指 h(A)。恢复 x/y 时都得到 B，y 即使导出期间从未改变也被错误去重。这个反例无须摘要碰撞，也不依赖任何链接越界。

[manifest 构造](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:791)对已复制的实际 blob B 重新算完整性摘要，因此包内 integrity 可以完全通过；这只证明 B 此后没被改过，不证明名称 h(A) 与内容一致。[恢复路径](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupRestoreFiles.kt:94)按索引 sha 找文件，直接 staged copy，不再验证 blob 内容是否等于去重键。加密层也只能保护最终已打包的错误映射。

现有 BlobStore 测试覆盖静态相同内容去重、大小边界、rehydrate 和摘要向量，没有覆盖 hash 与 copy 之间源文件变更。maxFileBytes 的预检查还不能保证正在增长的文件最终复制字节仍在上限内，这是同一两次读取边界的附带影响，不另计一项。

固定上游 [BackupBlobStore.kt:125](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupBlobStore.kt#L125)同样先 hash，134 行再 copy。共享缺口，不是旧 BF02 的符号链接范围越界。

修复方向记录：用同一读取流写 staging 并累计实际摘要/字节数，再按得到的摘要提交 blob；对明确变化的源文件记录不一致，而不是让快照时间过滤承担它无法保证的稳定性。只记录，不执行备份或修复。

## BK02 · P2 · 未认证的 KDF 迭代次数直接控制导入工作量

[KDF 描述](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupFormat.kt:293)把 iterations 作为可空 Int 保存，没有允许范围。[deriveKeys](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupCrypto.kt:130)取包中的非空值直接交给 PBKDF2；[PBEKeySpec/generateSecret](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupCrypto.kt:149)之间没有上界校验、deadline 或协作取消。

[真实导入](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:172)在 integrity 检查之前调用 unlock；[unlock](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt:99)又先 deriveKeys，之后才验证 verifier 和 manifest MAC。因此输入包尚未认证就可给出远高于正常 600000 的正迭代次数，用户点击恢复并提供非空密码后，必须先完成高成本同步推导，才可能报密码或认证失败。

[UI 调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/backup/BackupViewModel.kt:983)放到 Dispatchers.IO，可以避免主线程直接计算，但不能控制这个输入决定的总 CPU 工作量；仅取消协程也不会在 generateSecret 内自动插入取消检查。实际时间取决于设备与加密 Provider，本轮不做耗时估算或资源压测。非正数可能被 PBEKeySpec 拒绝，不代表大的正数已有边界。

此前 BF 报告把“KDF iterations 缺上界”列为待核候选；本轮追到 manifest、unlock、import 和 UI 后才登记为确定问题。旧 BF03 的 MAC 缺失问题不重复计算；即使最终 MAC 检查失败，这个前置工作量仍已经消耗。现有互操作测试使用 1000 次，验证密码与认证测试没有覆盖工作量边界。

固定上游 [BackupCrypto.kt:130](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup/BackupCrypto.kt#L130)相同使用 manifest 参数且直接 generateSecret。共享缺口。

修复方向记录：在执行 KDF 前验证所支持格式的算法参数与工作量范围，并明确未来更高参数版本的拒绝/升级行为；不能为降低成本静默替换包参数，否则会改变派生结果。正常加密备份兼容与取消处理需在真正修复时核验，本轮未执行。

## 补读及既有问题边界

完整阅读 crypto 分段、AAD、MAC、verifier、流读写；完整阅读 package reader、blob store、file tree/index writer 与 JSONL shard writer。旧 manifest.mac 缺失、明文额外成员、文件路径与链接问题只复核，不重复计数。materialize 的行为不作为生产入口推断，实际判断使用 importer。

rehydrate 检查现存 blob 的方式和分段 EOF 语义仍值得沿现役路径进一步核验；不把无生产调用或受默认 integrity 检查拦截的孤立函数问题直接升级为新漏洞。四份现有测试源码已通读，未执行，不能据此声称互操作或设备恢复通过。

## 覆盖与核验

本轮新增全文 9 个文件、1691 行：

- [src/android/app/src/main/java/com/openminis/app/backup/BackupBlobStore.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupBlobStore.kt)：209 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupCrypto.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupCrypto.kt)：310 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupFileTreeExporter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupFileTreeExporter.kt)：203 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupJsonlWriter.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupJsonlWriter.kt)：80 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupPackageReader.kt)：201 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupBlobStoreTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupBlobStoreTest.kt)：157 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupCryptoInteropTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupCryptoInteropTest.kt)：186 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupJsonlWriterTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupJsonlWriterTest.kt)：103 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupPackageReaderTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupPackageReaderTest.kt)：242 行。

累计全文 952 / 1546 个文件、193498 行；partial 15，not_registered 579，排除项 124。全仓通读尚未完成。

交叉片段在台账精确记为 partial，包含 BackupExporter、BackupImporter、BackupViewModel 和 BackupRestoreFiles。BackupFileTreeExporter 合并输出曾截断，已单独重读全部 203 行；UI 恢复片段也单独补读后才登记。未将这些调用方片段冒充全文。

所有 1670 个跟踪路径、SHA256、字节数和正整数文本行数重新核对；63 份旧报告摘要未变；HEAD 固定，产品 tracked/staged diff 为空。新报告本地引用与行号、阅读批次与总数核验。没有运行时或真实备份验证证据。
