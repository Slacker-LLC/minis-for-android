# 模型设置与思考规则：配置路径、保存语义和请求发送审计

日期：2026-10-05。目标源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本轮独立报告。只做静态审阅与固定上游快照对照，没有改产品代码、测试、合同或旧报告；没有运行构建、测试、模拟器、设备命令或账户服务。触发顺序来自源码推导，不等于真机复现。下面的端到端验收均为待执行建议。

## 新发现

| 编号 | 级别 | 问题 |
|---|---|---|
| MT01 | P2 | 自定义模型新增返回保存前的随机 ID，后续定位和新增撤销失效 |
| MT02 | P2 | 带点号的复合模型 ID 被配置路径解析器截断 |
| MT03 | P2 | 模型详情保存重建不完整模态，可能把文本模型变成纯图像模型 |
| MT04 | P2 | 模型详情普通保存清掉未展示的 maxThinkingLevel 覆盖 |
| MT05 | P1 | UI 可保存三种发送器不支持的思考格式，命中后请求构造直接抛错 |
| MT06 | P2 | 自定义 reasoning effort 的 offValue 保存了却不参与发送 |
| MT07 | P2 | 思考规则 JSON 读取把嵌套对象字符串化，读写／撤销丢失 values |
| MT08 | P2 | CLI 模态覆盖无法明确关闭某个方向，未提供的方向重新继承 |
| MT09 | P2 | 可空字段的 schema、帮助与实际 writer 不一致 |
| MT10 | P2 | UI 的“无限制”上下文值可读取却无法经同一 CLI 写回 |
| MT11 | P2 | 批次部分写入失败仍只返回整体成功，失败原因被吞掉 |
| MT12 | P2 | 配置总权限只在调用开始检查，撤销后旧请求仍可提交 |

## MT01 — 返回 ID 与仓库中的实际 ID 不同

证据：[ModelsCollection.kt:108](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:108)、[ProviderRepository.kt:742](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:742)、[ConfigBridge.kt:633](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:633)、[新增撤销:899](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:899)。

新增构造的不可变 ModelEntry 默认带随机 uuid，调用 `repo.addEntry(entry)` 后仍返回原对象的 `entry.id`。仓库保存则复制并规范化为 `instanceId/modelId`，原对象不会跟着改变。因此 `models.append` 成功回复及审计 `entry_id` 中的 ID 并不是实际保存的 ID。

以回复 ID 执行下一次读取、配置或删除会找不到对象。对这次新增执行 audit-revert，也会用错误 ID 删除，`ModelsCollection.remove` 返回 unknown_path，新增仍保留。这与此前 N03 的“删除以后恢复遗漏参数”不同：本项是新增之后反向删除定位失败。

方向：创建和返回统一使用现有仓库 ID 规则，或由仓库返回实际保存对象。待执行验收：通过公开 CLI 新增模型，直接用回复中的 ID 读取、设字段、绑定 slot，再撤销新增；检查每步定位同一对象并在重启后确认状态。

## MT02 — modelId 中的点号破坏动态配置路径

证据：[ConfigRegistry.kt:42](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigRegistry.kt:42)、[ModelsCollection.kt:40](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:40)、[ProviderConfigMapping.kt:268](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ProviderConfigMapping.kt:268)。

模型当前 ID 是 `instanceId/modelId`。解析器使用 `path.split('.', limit=3)`，把第二段当完整 ID。示意：`models.<instance>/mimo-v2.5.contextWindow` 被切成 `models`、`<instance>/mimo-v2`、`5.contextWindow`，在错误 ID 上取字段，最终 unknown_path。正常返回的原始 ID 无法直接用于路径；本地也没有提供对应编码协议。

固定上游快照的[ConfigRegistry.kt:158](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/config/ConfigRegistry.kt#L158) 已用首个点号定位集合、最后点号定位字段，中间完整保留 ID，并按字段叶名匹配。本轮读取本地 git 对象确认差异，不声称已移植。

方向：按现有集合字段协议正确分离集合、完整 ID 与叶字段，统一帮助和读取返回的拼写。待执行验收：modelId 分别含点号、斜杠、多段小数点，使用返回 ID 原样读取／修改／撤销；未知路径仍明确拒绝。

## MT03 — 保存丢掉界面没有展示的模态

证据：[ModelEntryDetailScreen.kt:132](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt:132)、[有效模型覆盖:399](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:399)、[LLMModel.isTextOutput:104](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/LLMModel.kt:104)、[slot 兼容:87](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelSlotsCollection.kt:87)。

详情页用图像、PDF、音频、视频输入开关，以及图像、音频输出开关重建列表，没有文本输入／输出，也没有视频输出。只要列表与基础模型不同，就写成完整 override，而非保留未编辑部分。

明确静态样例：基础输出 `['text','image']`，打开详情页只改显示名或直接保存。新输出是 `['image']`，被保存为 override；有效模型失去 text，`isTextOutput=false`，不再符合 Main／Light slot 的文本要求。基础只有 text 时会写空数组，但本地 `normalizeModalities()` 把空列表视为默认文本，所以不能说每个纯文本模型都会因此被排除；复合文本输出样例才会明确触发。输入 text 与未展示的视频输出也被丢弃。

方向：普通保存保留没有编辑的模态；编辑某一展示能力时，也保留界面不负责的能力。固定上游的[模型覆盖构造:422](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt#L422) 已区分 touched 状态，避免未编辑保存覆盖原模态；不能直接推断其所有“编辑过模态”场景都满足本地需要。

待执行验收：分别准备 text+image、text+audio、video 输出条目，改名称、无操作保存、仅切单一能力；比较有效模态及文本可选资格，重启核对。

## MT04 — 普通保存清掉思考上限覆盖

证据：[ModelEntryDetailScreen.kt:144](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt:144)、[ProviderConfig.kt:355](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:355)、[ThinkingLevelCatalog.kt:46](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ThinkingLevelCatalog.kt:46)、[ChatViewModel.kt:1496](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt:1496)。

保存重新构造 ModelOverrides，没有传 `maxThinkingLevel`，该字段恢复 null。虽然本页没有编辑它，已经通过现有配置／备份持有的覆盖仍是有效用户设置，聊天的思考等级上限也实际读取它。

例如原覆盖 LOW，基础／目录上限 HIGH，用户只改显示名后保存，上限会从 LOW 退回 HIGH。与上一轮 CF06 的导出格式遗漏不同，本项不经过导出，仅正常编辑保存即可触发。

方向：在现有 overrides 上修改本页负责的字段，保留其余字段。固定上游的[对应构造:432](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt#L432) 已显式带回 maxThinkingLevel；其额外字段和同步模型与本地不完全相同，应按本地真实字段选择性参考。

待执行验收：准备已有非默认思考上限的模型，只改显示名、最大输出和可见性，保存后上限保持原值，实际聊天选择器与重启结果一致。

## MT05 — 编辑器提供发送器尚未实现的格式

证据：[ThinkingRuleEditor.kt:77](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ThinkingRuleEditor.kt:77)、[格式选择项:254](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ThinkingRuleEditor.kt:254)、[ThinkingRuleResolver.kt:498](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt:498)、[实际 Provider 调用:2626](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIProvider.kt:2626)。

BooleanToggle、ExtraBodyToggle、CustomPath 在真实编辑器下拉菜单中可选、可保存，也能经 CLI 编码持久化。但是 OpenAI emit 分支只处理 OmitEverything、两种 Effort、DeepSeekSibling、QwenDual，其他格式落入 `error(...)`。注释把这当成程序员错误，当前却是正常用户操作就能制造的状态。

新规则排在内置规则之前；选 AllModels 时，该实例每个匹配请求构造都会抛异常，重试仍命中同一规则。这里只确认请求构造失败，不断言异常一定造成整个 App 进程崩溃。编辑器预览捕获异常只显示 preview unavailable，保存有效性却没有据此禁止，所以预览失败仍可把问题持久化。

固定上游[ThinkingRuleResolver.kt:658](https://github.com/OpenMinis/OpenMinis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt#L658) 已实现这三种格式。本地未包含。本轮没有复用上游注释里的设备复现结论。

方向：以真实支持格式约束保存与发送，现有用户数据须有明确兼容处理；需要开放的三种格式复用最小有界字段写入，不能继续保存后才抛错。待执行验收：UI 与 CLI 分别保存三种格式，走实际请求并核对生成字段；无效路径保存前拒绝，不留下不可用实例配置。

## MT06 — offValue 没有进入有效决策

证据：[编辑器关闭值设置:176](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ThinkingRuleEditor.kt:176)、[ThinkingRuleCoding.kt:28](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleCoding.kt:28)、[ThinkingRuleResolver.kt:334](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt:334)。

两种 Effort 格式都允许设置 offValue，编码和摘要也保留并展示。实际发送 root Effort 时使用 `ctx.offEffort`，没有读取命中格式的 offValue；nested Effort 在 OFF 直接不发，也没有读取其 offValue。关闭“发送关闭值”同样不影响从 ctx 得出的值。

因此用户针对兼容端点设置显式关闭值后，发送仍遵循旧端点推断。可能仍发送旧值，也可能完全省略。这里不预设服务端哪个值正确，只确认保存项与发送行为脱节。固定上游对应发送片段仍保持这一缺口。

方向：区分内置规则和用户明确规则的关闭语义，在现有格式中读取真实参数，保留必要的服务约束。待执行验收：受控端点检查 root／nested 格式，OFF 时分别设置 null 与 canary offValue，请求必须遵循保存的选择；开启等级保持原行为。

## MT07 — 规则读取没有保留 JSON 类型

证据：[ThinkingRulesCollection.kt:191](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ThinkingRulesCollection.kt:191)、[ThinkingRuleCoding.kt:93](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleCoding.kt:93)、[ConfigValue.kt:116](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigValue.kt:116)。

规则读取把 JSONObject 每个值都包成 `ConfigValue.Str(o.get(k).toString())`。CustomPath 的 `values` 本应是嵌套对象，读取后变成含 JSON 文本的字符串。原样写回时构造器仍把它写成字符串；解码只读取 `optJSONObject('values')`，得到 null，随后保存空映射。

该字段标记 revertable，旧值也通过同一 read 进入审计，所以对 wireFormat 的撤销会面临相同类型损失。普通 ConfigValue 已有递归 JSON 解码能力，集合却另写了只保留字符串的转换。当前 CustomPath 的实际发送还受 MT05 阻挡；本项独立确认已存规则的数据往返损坏，不额外声称当前请求会因空映射产生某种网络行为。固定上游此转换仍相同。

方向：复用现有递归 ConfigValue 编解码，保持对象、布尔、数字与数组类型。待执行验收：经公开入口存储有多个等级 values 的规则，读取后原样写回，再修改／撤销；完整类型和映射保持一致，工件使用非秘密 canary 值。

## MT08 — 某一方向的空覆盖被重新解释成继承

证据：[ModelsCollection.kt:343](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:343)、[ModelEntry.model:399](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:399)。

CLI 按扁平的 directional 模态列表拆分 input／output，却对任一空方向调用 `ifEmpty { null }`。例如基础输入含 image、输出含 text，用户设置 modalitiesOverride 为只含 `text_output`，输出设成 text，输入空列表变为 null，又继承原 image。反过来只设置输入也会保留基础输出。

返回覆盖列表只展示显式值，可能看起来只有 text_output；有效 modalities 仍包含 image_input。整体 `[]` 恢复继承是帮助明确约定，不把它本身视为 bug；缺口是非空完整覆盖无法表达另一方向明确为空，且没有区分“未提供”和“关闭”。这与 CF07 的分享文件导入丢空数组不是同一个输入入口。

方向：给方向明确的覆盖以一致语义；在既有帮助与配置格式中区分整体重置、方向继承和方向为空。待执行验收：基础多模态模型依次设置仅输出、仅输入、整体重置，比较 override 与有效 modalities，不让关闭的方向悄悄恢复。

## MT09 — 可空声明和写入实现不一致

证据：[ModelsCollection.kt:328](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:328)、[contextWindowOverride:382](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:382)、[ConfigSchema.kt:80](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigSchema.kt:80)。

modalitiesOverride 帮助说 `[] or null` 可清除，但 schema 是非 optional Array，null 在进入 writer 前失败。contextWindowOverride 的 schema 则是 Optional(Int)，null 能通过预校验，writer 却只接受 Int，在用户批准以后返回 TypeMismatch。后者并非非法输入：系统公开的 schema 已明确允许它。

方向：统一 schema、帮助、writer 和重置值，不让合法输入等确认后才失败。待执行验收：按 topic-help 提供的可空协议清除覆盖，null 与约定重置值达到相同状态；非法类型在确认前拒绝。MT11 另记录这种执行失败在批次中被整体成功掩盖的结果。

## MT10 — “无限制”值读得出、写不回

证据：[ModelEntryDetailScreen.kt:425](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt:425)、[ModelsCollection.kt:428](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/collections/ModelsCollection.kt:428)。

UI 滑条用 Int.MAX_VALUE 表示“无限制”，并按正值存入 contextLimitTokens。配置字段声明最大 Int.MAX_VALUE，读取也会原样返回。但 writer 另限定 `0..10_000_000`，超过上限报错时文字仍说最大 2147483647。

因此从 UI 保存无限制后，CLI get 的合法值无法原样 set 回去；曾经以无限制为旧值的审计撤销也会被 writer 拒绝。限制本身可以有产品理由，但读写协议、UI sentinel 与错误说明应一致。方向：按现有数据合同统一无限制与数值 cap 的表达和接受范围。待执行验收：UI 设无限制，CLI 读取／写回，改有限值后撤销；重启状态与文案一致。

## MT11 — 批次失败被整体成功省略

证据：[ConfigBridge.kt:707](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:707)、[结果判定:720](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:720)。

执行行失败时捕获 earlyError，但只有 applied 为零才把它返回。只要另外一行成功，就进入 ok=true 分支，既不返回失败项清单，也不返回 earlyError；普通 Throwable 仅记日志，全部失败时甚至报“用户拒绝了每一项”。

确定静态样例：同批次修改有效显示名，以及设 contextWindowOverride=null。两项均通过 schema，用户全部批准。第一项成功，第二项按 MT09 失败，返回却只有 ok=true 与第一项 applied。调用方需要自己比较原请求才可能发现遗漏，无法获知第二项的具体错误。

此项不要求批次具备事务原子性，也不重复 N02 的列表旧快照覆盖。方向：在现有响应中返回逐项成功／拒绝／失败与原因，整体状态准确表达部分成功；系统错误不能冒充用户拒绝。待执行验收：混合成功与错误字段、全系统错误、用户逐行拒绝三种情况，公开回复与实际配置、审计状态相符。

## MT12 — 撤销总权限没有使已进入的写入失效

证据：[ConfigBridge.kt:283](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:283)、[批准后写入:599](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/ConfigBridge.kt:599)、[MinisConfigPermissionStore.kt:44](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/MinisConfigPermissionStore.kt:44)、[ConfigConfirmationGate.kt:94](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/config/confirm/ConfigConfirmationGate.kt:94)。

总开关在 writeFields 开始检查。随后 performWriteBatch 可能等待确认，批准后的提交段没有重查；关闭开关也只更新偏好和 flow，没有取消旧请求或改变其提交资格。

静态时序风险：请求在开关开启时进入，批准事件发生；提交工作线程恢复之前，用户关闭总开关；旧提交仍能写入。待确认队列同样没有撤销接线。新调用会被挡住，不能说关闭完全无效；也没有完成实际 UI 时序复现，尤其不假设模态对话框显示时用户一定能进入设置页。核心是撤销与提交缺乏共同边界，批准后至提交前的窗口仍存在。

方向：提交时复核当前权限／撤销版本，按现有 gate 终止失效请求；保留清晰的取消结果。待执行验收：在实际设置 UI 关闭总开关与受控延迟提交交错，记录开关变更、批准、提交事件顺序；撤销后不得有失效请求的新写入，重新启用也不能自动恢复旧请求。

## 去重、现有保护与上游边界

全文读取 ConfigBridge 后重新看到 N01（拒绝／超时把凭据写进审计）、N02（同批次列表操作覆盖）和 N03（集合删除恢复参数缺失）；仍保留在原报告，不作为本轮新增。CF08 的启动缓存问题也不重计。本轮 MT01 是新增返回的身份错位，MT03／MT04 是直接 UI 保存语义，MT07 是字段 JSON 类型，均有不同触发入口与根因。

现有 ConfigSchema 在确认前验证字段类型，ClosureField 写入时再次验证；隐藏凭据字段、write-only API key 与 $$ENV 引用写入前解析仍有效。ModelsCollection 新增也确实检查重复模型。本文没有把这些保护删去或推断整个配置层无权限控制。

比较仅使用 `/tmp/openminis-review-20261004` 的固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，不拉取、不执行，不称为当前远端最新状态。MT02、MT05 有明确上游修复；MT03／MT04 有可参考的保存语义改动。上游 Android 对象身份不等同于本地 Room 复合主键迁移，不能只凭上游 `return entry.id` 相同就说其同样存在 MT01。本轮读取的上游 offValue 与 JSON 转换片段仍显示 MT06／MT07 的缺口；其余不作全量上下游结论。

已读 ThinkingRuleCustomMergeTest 的 116 行：覆盖空规则、OmitEverything、pattern 命中及实例隔离，没有覆盖 UI 可选三种格式、OFF 参数或配置 JSON 往返。本轮没有执行它，也不将这个单文件的覆盖情况泛化为“全项目无相关测试”。

## 全文覆盖进度

本轮新增 24 个全文记录，共 5,726 行。累计全文 **307 / 1,546** 个纳入文件，共 **65,377 行**，全库审阅仍未完成。搜索和调用链片段独立登记，不算全文。详见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

下列路径以前缀 `/home/jiale/projects/minis-for-android/src/android/app/src/` 展开：

| 全文文件 | 行数 |
|---|---:|
| main/java/com/openminis/app/config/collections/ModelsCollection.kt | 477 |
| main/java/com/openminis/app/config/collections/ProvidersCollection.kt | 386 |
| main/java/com/openminis/app/config/collections/ModelSlotsCollection.kt | 96 |
| main/java/com/openminis/app/config/collections/ThinkingRulesCollection.kt | 215 |
| main/java/com/openminis/app/config/collections/EnvVarsCollection.kt | 113 |
| main/java/com/openminis/app/config/ConfigRegistry.kt | 133 |
| main/java/com/openminis/app/config/ConfigCollection.kt | 46 |
| main/java/com/openminis/app/config/ConfigField.kt | 66 |
| main/java/com/openminis/app/config/ConfigSchema.kt | 97 |
| main/java/com/openminis/app/config/ConfigValue.kt | 149 |
| main/java/com/openminis/app/config/ConfigBridge.kt | 961 |
| main/java/com/openminis/app/config/fields/ClosureField.kt | 80 |
| main/java/com/openminis/app/config/MinisConfigPermissionStore.kt | 48 |
| main/java/com/openminis/app/config/confirm/ConfigConfirmationGate.kt | 186 |
| main/java/com/openminis/app/provider/thinking/ThinkingRule.kt | 142 |
| main/java/com/openminis/app/provider/thinking/ThinkingRuleCoding.kt | 169 |
| main/java/com/openminis/app/provider/thinking/ThinkingWireFormat.kt | 131 |
| main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt | 678 |
| main/java/com/openminis/app/provider/ThinkingLevelCatalog.kt | 47 |
| main/java/com/openminis/app/data/model/LLMModel.kt | 240 |
| main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt | 485 |
| main/java/com/openminis/app/ui/settings/ThinkingRuleEditor.kt | 350 |
| main/java/com/openminis/app/ui/settings/ThinkingRulesSection.kt | 315 |
| test/java/com/openminis/app/provider/thinking/ThinkingRuleCustomMergeTest.kt | 116 |

OpenAIProvider、ChatViewModel 与 OffloadPermissionScreen 仅追加本轮相关片段，未登记全文。交付前已核验全部 1,670 个 tracked 文件、目标提交及此前 20 份报告内容均未变，产品修改为零。本轮仅新增本报告并更新覆盖台账。未验证的 UI、网络和设备行为继续保持未验证。
