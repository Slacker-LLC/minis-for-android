# 会话配置、记忆编辑与注入展示静态审查

日期：2026-10-06。本轮新增独立报告，确认 5 项问题：4 项 P2、1 项 P3。只读审查产品源码，旧报告不作修改。

本地 HEAD 固定为 `d64e7c06d8159be90737dd9ba03dd9bd5855f339`；OpenMinis 对照固定为 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，读取的是此前已有的 Git 对象。没有构建、执行测试、启动模拟器、使用 adb/Root/设备/云资源或读取凭据。全代码通读仍未完成。

## SS01 · P2 · 普通保存会话 Soul 会清掉未编辑的模型覆盖字段

设置页[加载完整 SessionOverrides](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionAdvancedSettingsSheet.kt:111)，但只保留 Soul 编辑状态。[普通保存构造](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionAdvancedSettingsSheet.kt:141)新建仅有 `systemPrompt` 的对象，其他字段均回到 null；[写入数据库](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionAdvancedSettingsSheet.kt:282)替换整份 JSON，锁定分支也仅复制已有 Soul，不会保留其他覆盖字段。

静态样本：已有合法配置 `{"systemPrompt":"A","temperature":0.25,"maxTokens":4096}` → 打开该页 → 普通保存 Soul 为 B → 写入仅含 `systemPrompt` 的 JSON。即便 Soul 已锁定、用户没有改文本，点击保存仍会清掉温度和输出上限。此处不指“重置全部”按钮，那一操作本来就要求清空。

这些字段仍是有效运行配置：[数据模型](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SessionOverrides.kt:16)负责解析和序列化，[实际 Provider 请求](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8302)使用 `maxTokens` 与 `temperature`。会话[复制](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt:113)和[分支](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/SessionForkManager.kt:262)也会保留原 JSON。不能因为当前编辑页只展示 Soul，就把已有合法字段当成无效历史数据。

当前 UI 没有提供这两个数值的编辑控件，本问题以已有持久化覆盖为前提，不声称用户现在能从该页创建它们。修复方向记录：保存时读取现存覆盖，只替换本页负责的 Soul 字段，保留其他合法字段；继续维持现有 Soul 锁定规则。

固定上游树没有 `SessionAdvancedSettingsSheet.kt` 和 `SessionOverrides.kt`，不宣称上游已有对应修复。

## SS02 · P2 · 被解析和保留的会话工具允许列表实际不生效

[enabledTools](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SessionOverrides.kt:123)区分未设置与显式空集合，序列化也会保留该差异，但[filterTools 实现](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SessionOverrides.kt:64)无条件返回全部输入工具。搜索主源码和现有测试，未找到运行代码调用该函数或读取 `enabledTools`。

实际[当轮工具选择](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8117)仅由 `chatOnlyForNextTurn` 决定：否则直接使用 `agentTools`；[Provider 请求](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8306)直接传 `turnTools`。因此，在正常有工具的运行模式下，合法存储值 `{"enabledTools":[]}` 不会让会话进入仅聊天模式，非空子集也不会限制本会话工具范围。

[现有测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/model/SessionOverridesTest.kt:78)虽然名称声称空集合代表仅聊天模式，断言实际上只覆盖解析、空对象判定和 JSON 往返，没有调用工具筛选或真实请求入口。本轮只阅读测试源码，没有执行它们。

范围说明：当前设置页没有工具允许列表编辑控件，缺口针对仍被认可、复制和保存的配置字段；其他工具注册、审批及权限约束仍然适用，不将此事表述为通用 Root 绕过。它也不同于旧报告中的 MCP 会话开关：这里是 `SessionOverrides.enabledTools` 与 Agent 请求之间的断链。

修复方向记录：明确该字段的现役语义；若继续支持，应在构造当轮工具集合时应用，并保留现有工具权限边界。不能仅靠修改注释或解析测试声称筛选已实现。固定上游没有该数据模型，无法据此套用上游实现。

## SS03 · P2 · 记忆文件保存成功后，再打开仍读旧正文

记忆弹层[首次加载 autoItems](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:79)后，点击列表时直接使用[缓存的 item.content](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:156)。[保存成功](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:176)只更新当前详情 `mode`、编辑状态和成功提示，不更新 `autoItems`，其加载 effect 的键也没有变化。

静态操作序列：GLOBAL.md 原文 A → 编辑成 B → 保存成功 → 返回同一弹层的列表 → 再点 GLOBAL.md → 详情重新显示缓存 A。再进入编辑并保存，可以把磁盘上的 B 覆盖回 A；全过程不需要后台 Agent 或外部并发写入。[saveFile](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:340)确实调用实际写入，[写入异常会向上抛出](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:468)，不是仅修改 UI。

此问题独立于旧报告的“整文件保存覆盖后台新增记忆”：这里是应用刚保存的内容没有同步回自身列表快照。关闭并重新打开整个弹层会重新加载，不能说每次打开都永久保留旧文。

OpenMinis 也[只在弹层打开时缓存列表](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt#L75)，其[保存分支](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt#L157)同样只更新详情，属于共享缺口。本地把初次加载和保存移到 IO，并没有解决这个状态同步问题。

修复方向记录：成功保存后同步该条目的正文和摘要，或重载列表；返回后再次进入详情必须以刚保存的数据为准。

## SS04 · P2 · 打开提示词设置页会把用户内容写入错误日志

[加载设置时的 Log.e](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionAdvancedSettingsSheet.kt:102)直接插值整个 `ChatSessionEntity`，且没有 Debug 条件。该 Kotlin data class 包含[最近消息](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatSessionEntity.kt:29)、[角色卡 JSON](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatSessionEntity.kt:47)及[会话覆盖 JSON](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ChatSessionEntity.kt:54)，默认 `toString()` 会将这些字段一起输出，包含用户编写的会话 Soul。

用户只需打开该页，即会执行这条带 `PROMPT_DEBUG` 标签的错误日志。问题是原本只需读取的用户内容进入诊断日志；本轮不声称普通第三方 App 都能读取 Logcat，也没有实际抓取任何用户日志。[Release 配置](/home/jiale/projects/minis-for-android/src/android/app/build.gradle.kts:266)启用 R8，但项目规则未声明移除此类日志；没有编译或检查发布 APK，因此结论限定为源码中的无条件输出路径。

修复方向记录：日志仅保留查找是否成功等必要状态，去掉整个实体的插值和用户正文。固定上游没有该设置页，无法将其称作共享缺口。

## SS05 · P3 · “自动注入”列表没有反映实际截断和日志选择

记忆列表位于[自动注入区域](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:344)，[说明文字](/home/jiale/projects/minis-for-android/src/android/app/src/main/res/values/strings.xml:24)表示这些内容在每轮开始加载进系统提示词。但列表与运行链分别维护选择和预算，已经出现两种可证明的不一致：

- GLOBAL.md 的[标签固定为全文](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:518)。运行时却应用[字符预算](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:212)；[预算算法](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryInjectionBudget.kt:24)对 128000 上下文得到 8000 字符正文上限。例如文件有 20000 个无标题字符，列表显示 `1 lines (full)`，模型实际只能收到前 8000 个正文字符和截断说明。
- 日志列表只枚举[今天和昨天](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:536)。运行时[在 30 天内选择最多 3 份非空日志](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:28)，现役[异步路径](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:255)也执行同一规则。若只有三天前的日志有正文，它可以进入提示词，却不出现在这个自动注入列表；连续三天均有日志时，第三份同样被列表遗漏。

[现役提示词构造](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:10574)调用上述异步加载。例子以本会话记忆开启、加载正常为前提；本报告不主张取消预算或将 30 天日志全量注入，而是记录展示与实际输入不一致。

OpenMinis 的[GLOBAL 标签及两天列表](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt#L479)相同，且[运行时同样选择最近三份日志](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt#L270)，所以日志遗漏是共享问题。但是上游[GLOBAL 加载仍注入全文](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt#L257)，没有本地 `MemoryInjectionBudget.kt`；GLOBAL 的错误全文标签是本地增加预算后未同步展示，不能误判为上游同样截断。

修复方向记录：用现役选择规则和当前模型预算生成注入说明；完整文件查看仍可保留，但需区分原始文件正文与真正进入提示词的部分。

## 本轮边界

没有重报用户已指出的记忆编辑键盘避让问题，也没有处理注释噪音、复制基础设施或 Lint 基线。Skills 搜索派生状态会跟踪查询，批量开关作用于搜索结果；仅凭实现差异不能说这些行为有错。MCP/Skills 全部运行生命周期仍按此前相关报告及后续通读推进。

已有 SessionOverrides 的数值、Soul 与 JSON 往返测试，以及 MemoryInjectionBudget 的截断测试均只阅读源码，不能代替设置页保存、当轮工具选择和记忆弹层导航的实际运行证据。

## 覆盖与复核

本轮新增完整阅读 15 份文件、2682 行：

| 文件 | 全文行数 |
| --- | ---: |
| [SessionConfigSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionConfigSheet.kt:1) | 209 |
| [SessionMcpsSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMcpsSheet.kt:1) | 142 |
| [SessionAdvancedSettingsSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionAdvancedSettingsSheet.kt:1) | 309 |
| [SessionSkillsSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionSkillsSheet.kt:1) | 229 |
| [SessionMemorySheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SessionMemorySheet.kt:1) | 554 |
| [StandardChatSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/StandardChatSheet.kt:1) | 167 |
| [MemoryDetailScreens.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/MemoryDetailScreens.kt:1) | 300 |
| [TokenUsageSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/TokenUsageSheet.kt:1) | 165 |
| [FallbackOrder.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/FallbackOrder.kt:1) | 14 |
| [FallbackOrderTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/ui/chat/FallbackOrderTest.kt:1) | 21 |
| [SpeechLanguagePickerSheet.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/SpeechLanguagePickerSheet.kt:1) | 149 |
| [SessionOverrides.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/SessionOverrides.kt:1) | 149 |
| [SessionOverridesTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/model/SessionOverridesTest.kt:1) | 124 |
| [MemoryInjectionBudget.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryInjectionBudget.kt:1) | 62 |
| [MemoryInjectionBudgetTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/data/repository/MemoryInjectionBudgetTest.kt:1) | 88 |

按完整读到的区间扩展 `ChatViewModel.kt` 和 `SkillRepository.kt` 的部分阅读登记，不将搜索命中或相关片段计作全文。MemoryRepository、SessionForkManager、ChatSessionEntity 及构建配置等已读文件仅复核相关证据。

[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)当前全文 **814 / 1546 份纳入文件**，部分阅读 **14** 份，未登记 **718** 份；当前版本全文累计 **172461 行**。复核 1670 个受跟踪路径及 SHA256、字节数和文本行数，固定 HEAD、tracked/staged diff、53 份既有报告 SHA256、本文本地引用路径/行号、固定上游引用与缺失文件事实。tracked/staged diff 为空，写入范围仅为本报告与覆盖台账。
