# 旧分组迁移与 Entry 绑定身份审查

日期：2026-10-06。固定 HEAD `d64e7c06d8159be90737dd9ba03dd9bd5855f339`。本轮新增 **1 项 P2**；全文阅读 3 个文件、596 行。只做静态排查，未改产品源码，未运行测试、构建、模拟器、设备、Root 或云任务。另建报告，旧报告不改。

## LG01 / P2：模型条目转换为持久 ID，已有 Entry 绑定却保留旧 UUID

[LegacyGroupStateParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/migration/LegacyGroupStateParser.kt:117) 从旧条目建立随机 UUID → providerInstanceId/modelId 的别名字典，数据库入口还在第 60 行合并 JSON 镜像的旧 UUID。[LegacyGroupMigrator.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/migration/LegacyGroupMigrator.kt:99) 会真正把 ModelEntry.uuid 替换为 canonical ID；[ProviderConfig.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:389) 的 id getter 直接返回 uuid，因此迁移后的模型目录已经不含旧随机 ID。

但会话的 migrateBinding 在 [LegacyGroupMigrator.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/migration/LegacyGroupMigrator.kt:199) 只迁移 Group，Entry 原样返回。Bot 与定时任务共用 migrateBotBinding，[LegacyGroupMigrator.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/migration/LegacyGroupMigrator.kt:168) 同样对 Entry 原样返回；它只在 Group 与已知裸 ID 分支应用别名字典。格式已经是 Entry 并不意味着内部 ID 已经是持久 ID。

静态反例：输入配置包含 uuid=legacy-one、providerInstanceId=provider、baseModel.id=model-one，会话/Bot/定时任务的 binding 为 {"type":"entry","entryId":"legacy-one"}。Parser 生成 legacy-one → provider/model-one；迁移后配置条目 ID 为 provider/model-one，三个 binding 仍为 legacy-one。不是 malformed 数据，也不需要写入失败就可出现悬空引用。

### 现役调用链与影响

[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:405) 的旧 JSON 导入会调用 migrateLegacyGroupsFromJson；数据库迁移入口也读取这些现役绑定。[ProviderRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:558) 持久化迁移结果，原样 Entry 会被会话/Bot 的差异检查跳过。最后第 599 行写入新配置及 migrationComplete=true；后续正常加载在第 381 行按迁移完成标记跳过迁移，不会自动修正这个遗漏。

会话恢复 [ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5001) 用 entry.id == binding.entryId 精确匹配，不应用旧别名。该步骤返回 false 后，[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:4475) 按存储 model_id 找模型，再走默认选择。[ChatViewModel.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:5101) 仅按 base model.id 查第一个可见条目；多个 Provider 供应同名模型时，不能维持原 Entry 所代表的 Provider 身份。没有多 Provider/缺少旧 model_id 的场景也可能恢复同一模型，因此不声称所有旧会话一定选错或全部不可用。

[BotModelResolver.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/BotModelResolver.kt:15) 对显式 Entry 绑定找不到条目直接返回 null，未转用主槽。现役 BotRepository、BotDelegationCoordinator、ScheduledAgentRunner 均调用这个 resolver。确认的是迁移后精确绑定无法解析；各入口的提示和任务失败处理不是本轮新增缺陷，不泛化成全部后台任务停摆。

### 已有测试为何没有覆盖

[LegacyGroupMigratorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/LegacyGroupMigratorTest.kt:93) 的保留 Entry 测试使用 e3，别名字典只有 old-e1 → e1，没有测试需要 canonical 的 Entry；[LegacyGroupMigratorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/LegacyGroupMigratorTest.kt:203) 的旧备份 JSON 测试包含 legacy-one/two，但只传分组/槽与 agent loop 引用，没有旧 UUID 的现成 Entry 绑定。两类测试分别存在，却缺少交叉组合。仅静态阅读，未编写或运行测试。

修复方向：迁移已解析的 Entry 时也应用同一别名字典，保留已经是持久 ID 的身份映射；覆盖会话、Bot、定时任务和 JSON/数据库两个入口。不要通过模型名称猜 Provider 来掩盖引用错误。本轮只记录。

## 其它核对与 OpenMinis 对照

分组成员、槽、agent loop ID、Group.lastEntryId 和 Bot 裸 ID 已应用 canonical；Group 迁移的可用成员选择、既有 override 优先和主分组顺序与已有测试一致，本轮不另计故障。配置写入失败后的重试与跨存储中断仍需结合更多持久化入口核对，未把候选当作确认问题。没有恢复旧分组运行栈，也没有把迁移专用模型视为应立即删除的死代码。

只读固定 `/tmp/openminis-review-20261004` HEAD `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，未 fetch。该快照树没有 LegacyGroupMigrator/LegacyGroupStateParser 同名文件；仅据此说明不存在直接同路径对照，不声称上游所有迁移均正确或完全没有分组迁移，也不建议照搬其旧 runtime。

## 全文覆盖与复核

| 本轮全文文件 | 行数 |
|---|---:|
| [LegacyGroupMigrator.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/migration/LegacyGroupMigrator.kt) | 220 |
| [LegacyGroupStateParser.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/migration/LegacyGroupStateParser.kt) | 133 |
| [LegacyGroupMigratorTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/LegacyGroupMigratorTest.kt) | 243 |

累计全文 **1121 / 1546 个文件、220228 行**；partial 13，not_registered 412，excluded 124。全仓通读尚未完成。测试首次合并输出的截断部分已分段重读。ChatViewModel 仍 partial，只追加本轮干净片段；其它已登记调用方不重复计全文。

完成时核对全部 1670 tracked 路径的 SHA-256、字节数与正数文本行数，固定本仓/上游 HEAD、tracked/staged 空 diff、此前 89 份报告哈希、本报告文件链接/行号和批次全文总计。只新建本报告并更新覆盖台账，不访问用户数据库、偏好或凭据。
