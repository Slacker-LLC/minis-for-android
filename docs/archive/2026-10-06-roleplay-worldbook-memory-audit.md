# 角色卡、世界书与剧情记忆静态审查

日期：2026-10-06。本轮新增独立报告，确认 6 项问题：4 项 P2、2 项 P3。仅审查和记录，不修改产品代码或旧报告。

本地 HEAD：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。OpenMinis 固定对照：`b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，读取既有 Git 对象，不拉取或执行上游代码。本轮没有构建、执行测试、启动模拟器、使用 adb/Root/设备/云资源或读取凭据。全代码通读仍未完成。

## RP01 · P2 · 剧情记忆的 revision 检查与落盘没有组成原子操作

[仓库 mutate](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryRepository.kt:38)先读取文件，交给纯文档函数校验 revision，再单独写回；整个流程没有按角色锁定。[文档 revision 校验](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryDocument.kt:142)只保护传入的字符串，并不能保证磁盘在校验与写入之间保持该版本。

真实工具[在 Dispatchers.IO 中调用仓库](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/CharacterMemoryTools.kt:75)，没有串行保护；[统一执行器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:167)在权限检查后直接调用 handler，也没有按角色互斥。[不同会话使用各自的 ViewModelStore](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModelStore.kt:53)，同一角色的记忆又明确[由绑定该角色的多个会话共享](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/CharacterMemoryTools.kt:133)。[会话活动跟踪器](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/service/SessionActivityTracker.kt:298)实际维护多个同时运行的 session 及各自取消回调。会话内顺序执行不能保护另一个会话对同一文件的写入。

可成立的静态交错：A 和 B 均读到 M0，revision 均为 r0；A 校验成功并算出 M0+A，暂停；B 也以 M0 校验成功并写入 M0+B；A 恢复并写入 M0+A。两次工具调用都能报告成功，B 的内容却被覆盖，所谓过期 revision 拒绝没有触发。这是代码允许的并发交错，不是本轮设备实测。

[写入 helper](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryRepository.kt:63)还复用固定 `memory.md.tmp`，并发写入会争用同一临时文件。正常 rename 可以防止单次写入的半文件，但不能替代 revision 检查与写入之间的互斥；rename 失败后的直接覆盖也不能提供同等的进程死亡保护。

[现有冲突测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterMemoryDocumentTest.kt:98)只把已经变化的字符串传给纯函数，证明顺序校验，不覆盖仓库读写交错。此问题涉及独立的 App 私有剧情记忆文件，不重复普通 GLOBAL/daily 记忆编辑报告中的覆盖问题。

修复方向记录：复用已有适用的文件变更保护，或在角色存储边界把读取、revision 比较和提交纳入同一临界区；读写失败应保留旧内容，临时文件提交也要有明确失败行为。

## RP02 · P2 · 世界书保存失败仍清除草稿并返回

[数值输入](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterDetailScreen.kt:249)接受 `toIntOrNull()` 得到的负整数，[草稿编码](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterWorldbookDraft.kt:81)则明确拒绝负扫描深度和负 token 预算。[保存按钮](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterDetailScreen.kt:95)将编码和仓库写入包在 `runCatching` 中，却不检查结果；随后无条件执行 `dirty = false` 和 `onBack()`。

无需存储故障就能触发：打开角色世界书 → 将 token 预算填成 `-1` → 同时修改一个条目正文 → 保存。编码在负数校验处抛出，数据库没有写入，但页面仍返回且没有失败提示；本次正文修改随离开页面丢失。数据库或文件写入失败也走同一路径。

[绑定角色](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterPickerDialog.kt:87)与[解除绑定](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterPickerDialog.kt:99)同样忽略 `runCatching` 结果并关闭弹窗；[删除确认](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterDetailScreen.kt:205)也在结果未知时返回。它们是同一类结果处理缺口，未为了数量单独拆项。绑定仍通过当前仓库和 DAO，不声称写入本身总是失败。

[现有负数拒绝测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterWorldbookDraftTest.kt:174)覆盖纯编码拒绝，无法证明 UI 会保留草稿和展示错误。修复方向记录：失败时保留页面、dirty 状态和草稿，给出可操作错误；仅确认操作成功后返回。输入校验可复用现有模式。

## RP03 · P2 · 角色提示词在上下文检查后追加，完整成本没有进入预算

[角色投影预算](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/RoleplayTurnProjection.kt:46)只扣除消息、`toolsText`、未展开的 depth/post-history 文本和 16 token 余量，就给世界书分配剩余空间。角色描述、性格、场景、示例、用户人设、剧情记忆，以及原有系统提示词均不在这个扣除集合里。

[实际调用](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8277)也没有传 `toolsText`，故该参数保持空字符串。[角色块构造](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterPrompt.kt:43)随后拼接这些字段及展开后的宏，并没有限制整块的上下文 token 数。剧情记忆虽有独立字符上限，也并未从世界书可用 token 中扣除。

[循环上下文检查](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:7980)发生在角色投影之前；[最终请求](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:8297)再将 `characterBlock` 追加到已有 `effectiveSystemPrompt`。[循环检查实现](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:3862)依赖已完成请求的 usage，首次没有 usage 时直接继续，不能在首次请求前发现这一新增大块。

静态反例：角色描述含 140000 个普通汉字、模型窗口为 128000；该 JSON 远低于[16 MiB 卡片上限](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterCardCodec.kt:22)，也低于[宏展开字符上限](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMacros.kt:21)，可以通过导入与展开。按项目自己的[非 ASCII token 估算](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentContextBudget.kt:52)，仅描述已超过窗口，投影仍构造并送出完整角色块。世界书即便全部被剔除也无法解决这个反例。

Provider 的溢出错误处理和压缩重试仍然存在，不能说所有上下文保护都缺失；但只压缩会话历史无法缩短每次重新追加的固定角色描述。[现有小窗口测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/RoleplayTurnProjectionTest.kt:133)只断言 lore 被移除、lore usedTokens 为零，没有校验最终系统提示词加消息是否适配窗口。

修复方向记录：在构造完整请求时核算现有系统提示词、工具、角色字段、宏展开、记忆与输出预算；将角色投影纳入同一上下文决策，避免先检查历史、后加入未预算的大块。不需要另建一套竞争的压缩阈值。

## RP04 · P3 · 剧情记忆分页的首条长行可以突破 max_chars

[分页循环](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryDocument.kt:120)仅在 `page.isNotEmpty()` 时才因预算不足退出，第一行无论多长都会完整加入。[实际读取工具](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/CharacterMemoryTools.kt:46)直接使用该结果，将正文与范围写进 JSON。

合法剧情记忆可包含一条 30000 个 ASCII 字符的长行，仍在 64000 字符存储上限内。请求 `max_chars=256`，返回正文却是整条 30000 字符，且 `has_more=false`；30000 字符不是预算上下取整造成的小差异。现有[分页测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterMemoryDocumentTest.kt:63)使用短行，没有覆盖这个分支。

[工具总输出保护](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolRegistry.kt:175)仍生效，但它采用[50 KiB 字节门槛](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/runtime/ToolResultBudget.kt:25)，与请求者指定的 256 字符不同。上述 ASCII 正文加元数据仍在门槛内，通常会原样返回；更大输出可能被通用保护 offload，不能说输出完全无界。

修复方向记录：明确并兑现读取上限，长行需要有可继续读取的位置，或明确拒绝并提示；不能用首行豁免将 `max_chars` 变成无效参数。

## RP05 · P3 · 截断剧情记忆后，提示模型调用了错误的记忆工具

[剧情记忆注入](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterPrompt.kt:62)复用 `MemoryInjectionBudget.bound()`。当正文超出预算，[截断尾部](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryInjectionBudget.kt:58)固定提示 `Use memory_get to read the rest.`。

然而剧情记忆实际保存在[App 私有 roleplay/<角色>/memory.md](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryRepository.kt:20)，应使用[roleplay.memory_read 或 character_memory_get](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/CharacterMemoryTools.kt:26)读取。[memory_get 分发](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:9855)进入[普通记忆查询](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/MemoryTools.kt:124)，最终只遍历[/var/minis/memory](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/MemoryRepository.kt:31)里的 GLOBAL/daily 文件，无法找到剧情记忆剩余部分。

例如 20000 字符剧情记忆配合 128000 上下文，注入会截断；模型按提示搜索普通 `memory_get`，不会取得被截去的剧情，还可能读到另一套真实记忆内容。这里是错误路由指引，不声称代码已经把两套文件合并或授予新的文件权限。

[现有截断测试](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterMemoryPromptTest.kt:58)反而把包含 `memory_get` 当作成功条件，证明字符串存在，未确认工具能读取对应文件。修复方向记录：复用预算逻辑时提供正确的继续读取指引，让剧情记忆明确指向自己的现役工具。

## RP06 · P2 · 兼容性警告只有测试调用，导入后会静默跳过部分角色内容

[兼容性分析](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterCardCompatibility.kt:28)已经实现不支持的宏、世界书条件、特殊插入位置、扩展、附加资源及群组问候等警告。搜索当前主源码，未找到任何外部生产调用；只有[测试 helper](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterCardCompatibilityTest.kt:20)调用 `warnings()`。

[真实文件导入 UI](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharactersScreen.kt:91)只调用仓库导入并提示抛出的异常；[仓库导入](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterRepository.kt:116)进行 JSON/PNG 校验与存储，不执行兼容性分析。详情页只展示世界书编辑字段，也没有展示这份警告。卡片可合法导入，但用户不会收到不支持内容的说明。

运行时[世界书选择](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterWorldbook.kt:49)会因 `CharacterWorldbookSupport.reasons(raw)` 非空而跳过条目。反例是合法条目携带 `extensions.useProbability=true` 与 `extensions.probability=50`：[运行规则](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterWorldbookSupport.kt:42)将其判为不支持并跳过；导入仍成功，用户只能看到该条目“已启用”，无法知道它根本不会注入。旧数据没有被删除，缺口是能力降级没有在实际用户入口说明。

修复方向记录：将已有分析结果接入导入结果或详情页，并让被运行时跳过的条目可识别。继续保留未知字段和当前跳过行为，不能为了消除提示而无条件执行不支持的条件。

## OpenMinis 对照及已核实边界

固定上游树没有本地 `roleplay/` 目录、`CharacterMemoryTools.kt`、`CharacterDetailScreen.kt` 等角色模块。本轮问题出自本地分叉模块，不能称 OpenMinis 已有相同问题或可以直接移植对应修复。源码中的 Eta 来源说明只是定位线索，本轮未读取 Eta 仓库，不能用它证明 Eta 当前行为或移植是否完整。

PNG 有文件大小、CRC、头部格式和几何限制，V3 元数据优先且不会在损坏时自动退回 V2；没有把这些已有检查误报为缺失。角色快照 fallback 也已经实现，未重复旧报告中备份缺失会话 roleplay 引用的问题。

宏展开有循环检测、深度上限和字符总量限制；世界书不执行声明不支持的条件；草稿编码会深拷贝并保留未编辑的未知字段。这些处理的存在，不能替代真实 UI 结果处理、并发提交或完整请求预算。

本轮只阅读现有测试源码，不执行或新增测试。不处理用户让其他工具处理的注释、基础设施复制及 Lint 基线。

## 覆盖与复核

本轮新增全文阅读 **34 份文件、4683 行**，包括角色模块全部现有源码及对应测试、3 个角色 UI、剧情/普通记忆工具和 AgentContextBudget：

| 文件 | 全文行数 |
| --- | ---: |
| [AgentContextBudget.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/agent/AgentContextBudget.kt:1) | 88 |
| [CharacterBinding.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterBinding.kt:1) | 65 |
| [CharacterCard.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterCard.kt:1) | 90 |
| [CharacterCardCodec.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterCardCodec.kt:1) | 182 |
| [CharacterCardCompatibility.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterCardCompatibility.kt:1) | 130 |
| [CharacterCardException.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterCardException.kt:1) | 14 |
| [CharacterCardPng.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterCardPng.kt:1) | 143 |
| [CharacterMacros.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMacros.kt:1) | 129 |
| [CharacterMemoryDocument.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryDocument.kt:1) | 182 |
| [CharacterMemoryRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterMemoryRepository.kt:1) | 73 |
| [CharacterPrompt.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterPrompt.kt:1) | 125 |
| [CharacterRepository.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterRepository.kt:1) | 211 |
| [CharacterStoragePolicy.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterStoragePolicy.kt:1) | 42 |
| [CharacterWorldbook.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterWorldbook.kt:1) | 122 |
| [CharacterWorldbookDraft.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterWorldbookDraft.kt:1) | 156 |
| [CharacterWorldbookSupport.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/CharacterWorldbookSupport.kt:1) | 102 |
| [RoleplayTurnProjection.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/roleplay/RoleplayTurnProjection.kt:1) | 99 |
| [CharacterMemoryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/CharacterMemoryTools.kt:1) | 169 |
| [MemoryTools.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/tools/MemoryTools.kt:1) | 137 |
| [CharacterDetailScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterDetailScreen.kt:1) | 322 |
| [CharacterPickerDialog.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterPickerDialog.kt:1) | 157 |
| [CharactersScreen.kt](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/CharactersScreen.kt:1) | 220 |
| [CharacterBindingTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterBindingTest.kt:1) | 74 |
| [CharacterCardCodecTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterCardCodecTest.kt:1) | 201 |
| [CharacterCardCompatibilityTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterCardCompatibilityTest.kt:1) | 126 |
| [CharacterCardPngTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterCardPngTest.kt:1) | 156 |
| [CharacterMacrosTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterMacrosTest.kt:1) | 133 |
| [CharacterMemoryDocumentTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterMemoryDocumentTest.kt:1) | 171 |
| [CharacterMemoryPromptTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterMemoryPromptTest.kt:1) | 87 |
| [CharacterPromptTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterPromptTest.kt:1) | 160 |
| [CharacterStoragePolicyTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterStoragePolicyTest.kt:1) | 49 |
| [CharacterWorldbookDraftTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterWorldbookDraftTest.kt:1) | 197 |
| [CharacterWorldbookTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/CharacterWorldbookTest.kt:1) | 213 |
| [RoleplayTurnProjectionTest.kt](/home/jiale/projects/minis-for-android/src/android/app/src/test/java/com/openminis/app/roleplay/RoleplayTurnProjectionTest.kt:1) | 158 |

按实际区间继续扩展 ChatViewModel.kt 和 MinisApp.kt 的部分阅读登记，不将搜索命中算全文。ToolRegistry、ToolProvider、ToolResultBudget、MemoryRepository、ChatViewModelStore 及字符数据库模型等此前已经全文阅读的文件仅核对相关证据。

[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)当前全文 **848 / 1546 份纳入文件**，部分阅读 **14** 份，未登记 **684** 份；当前版本全文累计 **177144 行**。1670 个受跟踪路径与 SHA256、字节数和文本行数，固定 HEAD、tracked/staged diff、54 份既有报告 SHA256、本文本地引用路径/行号及固定上游缺失事实均复核。tracked/staged diff 为空，写入仅为本报告和覆盖台账。
