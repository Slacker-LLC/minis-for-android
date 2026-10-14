# 备份角色数据与格式层静态审查

日期：2026-10-06。新增 1 项 P2。独立报告，不修改产品代码或旧报告。

本地 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 固定对照 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`。只读取现有 Git 对象，未拉取、运行代码、构建、测试、模拟器、adb、Root、设备或云任务；未读取凭据，未使用子 Agent。

## BC01 · P2 · 角色备份只保留最新 500 项，超出的角色静默缺失

[BackupRoleplayData.kt:50](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupRoleplayData.kt:50)定义 MAX_ITEMS=500，[BackupExporter.kt:576](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:576)对完整 DAO 列表直接 take(MAX_ITEMS)。[CharacterDao.kt:12](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/CharacterDao.kt:12)按 updated_at 降序查询，未加数量限制，所以截断的是较早更新的角色。

这个边界不是正常产品存储上限：[CharacterRepository.kt:70](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterRepository.kt:70)创建角色时验证卡片并生成 ID 后直接 upsert，没有检查角色总数；[CharacterRepository.kt:116](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterRepository.kt:116)正常导入卡片也调用 create。已有 501 个合法角色即可触发，无需异常包、Root 操作或并发。

静态数据流：选择 CHATS 后，[BackupExporter.kt:131](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:131)调用角色导出；最近更新的 500 项写入 characters.jsonl，剩余项连同其卡片、头像与剧情记忆均不写入该文件。函数返回 Unit，仅日志输出实际选中数量，没有被截断数量、warning 或 tombstone。[BackupExporter.kt:228](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupExporter.kt:228)的 Summary.skippedFiles 只来自 blobStore，不能反映这次列表截断。

恢复入口 [BackupImporter.kt:234](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:234)调用 restoreCharactersAndBots；[BackupImporter.kt:1162](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupImporter.kt:1162)只能读取包中现有记录。这个包只有 500 条，因此它的超限 ignored 分支也不会报告来源第 501 项缺失。没有会话引用的角色在新安装中完全消失。有关联会话的角色即使保留卡片 snapshot，也不能据此恢复独立角色对象与全套剧情记忆；本项不重复旧报告的会话 roleplay 字段缺失问题。

Bot 导出也使用相同 take(500)，但正常 Bot 数量另有产品约束，旧报告已经指出恢复绕过 Bot 数量约束。本项以无数量上限的角色正常入口证明，避免重复计数 Bot 导入问题。

[BackupRoleplayMappingTest.kt:29](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupRoleplayMappingTest.kt:29)只覆盖单角色映射与头像/记忆大小判断；[BackupCategoryCountTest.kt:24](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupCategoryCountTest.kt:24)关注技能、MCP、Provider 的统计单位，没有覆盖 501 个角色导出的完整性。只读这些测试，未执行。

上游对照：固定 OpenMinis 树中不存在 BackupRoleplayData.kt，backup 目录 grep 也没有 BackupRoleplayMapping/exportCharactersAndBots。没有同名现役实现可用于证明共享缺口，因此按本地角色备份扩展登记，不声称上游也有同一截断。可复核 [固定上游 backup 目录](https://github.com/OpenMinis/OpenMinis/tree/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/backup)。

修复方向记录：完整导出合法存储的角色，复用 JSONL 分片/流式写入；如保留明确资源限额，必须把遗漏对象计入可见报告并提供完整迁移路径。导出和恢复的对象限额要一起核验，不能只去掉导出 take 而让恢复继续截断。本轮不改代码、不创建 501 项数据或执行恢复。

## 格式层及既有问题边界

BackupFormat 全部 461 行已阅读，包含非空/可空时间序列化、category、manifest、KDF、索引、思考规则与秘密记录。KDF 上界对应上一轮 BK02，不重复；索引 SHA 来源约束与 manifest 认证对应旧 BF 问题，不重复。未知字段、默认值与历史 voice_corrections 解码保留已有实现，不误报为缺失。

角色映射将过大的头像/记忆转换为 absent 的行为也已读到；是否存在正常可达的超限剧情记忆及是否被其它报告完整表达，仍需沿写入入口进一步确认，本轮不另编号。Base64 解码发生在大小拒绝前，仅这个孤立片段不足以证明真实入口无其它记录资源边界。

## 覆盖与核验

本轮全文 5 个文件、1016 行：

- [src/android/app/src/main/java/com/openminis/app/backup/BackupFormat.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupFormat.kt)：461 行。
- [src/android/app/src/main/java/com/openminis/app/backup/BackupRoleplayData.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/backup/BackupRoleplayData.kt)：119 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupCategoryCountTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupCategoryCountTest.kt)：184 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupFormatToleranceTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupFormatToleranceTest.kt)：158 行。
- [src/android/app/src/test/java/com/openminis/app/backup/BackupRoleplayMappingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/backup/BackupRoleplayMappingTest.kt)：94 行。

累计全文 965 / 1546 个文件、195753 行；partial 14，not_registered 567，excluded 124。全仓通读尚未完成。Exporter/Importer 调用片段仍记 partial；CharacterRepository 和两个 DAO 此前已全文，不重复增加。

所有 1670 tracked 路径、SHA256、字节数与正整数文本行数重新核对；65 份旧报告摘要不变，HEAD 固定，tracked/staged diff 为空。新报告本地引用与行号、批次总数核验。检索输出曾截断，后续用针对性检索核对相关旧报告与调用链；本轮五份全文输出均完整，无截断后冒计全文。没有运行时或真实迁移验证证据。
