# Provider 配置审计：持久化、导入导出与刷新竞态

日期：2026-10-05。目标源码：`d64e7c06d8159be90737dd9ba03dd9bd5855f339`。

本轮另建独立报告，不修改任何旧报告。只做源码阅读、静态调用链核对及固定上游快照比较，没有修改产品代码，也没有运行测试、构建、模拟器、adb、Root 或账户服务。以下触发顺序是静态推导；所有验收建议均未执行。P1 表示数据丢失或持久化持续失效的优先风险，P2 表示功能、配置完整性或资源边界缺口，均不代表已经在设备复现。

## 新发现概览

| 编号 | 级别 | 问题 |
|---|---|---|
| CF01 | P1 | 配置加载失败被吞掉后，修改入口仍能保存空占位配置，覆盖旧数据 |
| CF02 | P2 | 删除最后一个 Provider 后，数据库与镜像之间的失败窗口可让旧 Provider 重现 |
| CF03 | P2 | 删除 Provider 只清 API key，没有清 OAuth token 与手工 Bearer |
| CF04 | P1 | 在途模型刷新可写回已删除 Provider 的模型，使后续完整快照持续违反外键 |
| CF05 | P2 | 导入在验证模型之前已创建 Provider、写入凭据，失败后留下半份配置 |
| CF06 | P2 | Provider JSON 往返丢失 Azure、图像路由、上下文与思考配置 |
| CF07 | P2 | 显式空模态覆盖导入后变为继承，用户关闭的能力恢复 |
| CF08 | P2 | 异步启动没有加载自定义思考规则缓存；固定上游快照已有修复 |
| CF09 | P2 | 自定义模型允许重复 modelId，与持久化复合主键冲突 |
| CF10 | P2 | 支持免 key 的自定义端点却跳过真实模型目录刷新 |
| CF11 | P2 | 手工 Bearer／自定义 OpenAI 端点误用 Codex 模型目录 |
| CF12 | P2 | 单 JSON 导入同步无限量读取且未关闭流，ZIP 也在结果回调中同步执行 |

## CF01 — 加载失败没有阻止修改与保存

证据：[ProviderRepository.kt:477](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:477)、[ensureConfigLoaded:700](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:700)、[saveConfig:765](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:765)、[removeInstance:1019](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1019)。

底层发现 DAO 读取失败且 JSON 镜像不可用时会抛异常，意图避免把故障当成首次安装。但 `ensureConfigLoaded()` 捕获后直接正常返回，修改调用方无法知道失败。`addInstance`、`removeInstance` 等随后仍取得 `workingCopy()` 并调用 `saveConfig()`。保存没有检查 `_configLoaded`，会发布并排队写入以空占位为基础的新快照。DAO 的 `replaceAll` 是清空后重建完整配置。

具体条件：旧数据库有真实配置；读取暂时失败，镜像也无法提供可用配置；用户此时添加或删除 Provider；后台写入时数据库已经恢复可写。结果会用新建的少量配置，甚至空配置覆盖旧 Provider。不是任何数据库读取失败都会丢数据：有效镜像或成功数据库回退能够避免这一分支；持续无法写入时也不会立即完成覆盖。

源码注释说“mutators 的 saves 不会覆盖拒绝读取的存储”，但实际没有这一门槛。方向：让现有加载结果明确传回修改入口，未成功加载时禁止修改提交并展示可重试错误；合法空配置和未加载状态必须分开。

待执行验收：已有多 Provider 的存储，控制读取失败并让镜像不可用，触发添加／删除，再恢复写入；重启后验证原配置完整，错误可见。保留操作顺序、配置摘要和重启结果，不保存真实凭据。

## CF02 — 已初始化但为空的数据库被当成尚未迁移

证据：[ProviderRepository.kt:356](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:356)、[镜像回退:404](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:404)、[双写:637](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:637)、[ProviderConfigDao.kt:62](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ProviderConfigDao.kt:62)。

持久化先完成 Room 事务，再 `SharedPreferences.commit()` 写 JSON。两种存储之间仍存在进程终止或镜像提交失败的窗口。数据库有实例且迁移完成时，加载器确实选择数据库并修复镜像，这部分已有保护；不能笼统说每次双写失败都会回滚到旧镜像。

缺口在最后一个实例被删除之后：数据库实例数为零，但 meta 中仍有迁移标记及合法的新配置。加载器只有 `instanceCount > 0` 才读取这些 meta，否则直接把数据库配置设为 null，继续导入旧 JSON。若进程在数据库删除成功与镜像落盘之间结束，下次启动可能把旧 Provider 重新写回。`.commit()` 同步等待并不把它和前面的 Room 事务合成一个原子操作。

方向：按数据库初始化／迁移状态识别合法空配置，不用实例行数判断数据库是否存在有效状态。待执行验收：删除最后一个 Provider，控制在 Room 成功、镜像写入之前终止进程，恢复正常 IO 后启动；验证仍为空，并保存两个存储的非敏感摘要和启动结果。

## CF03 — 删除实例留下另一套凭据

证据：[ProviderRepository.kt:1040](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1040)、[OAuthManager.kt:62](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt:62)、[logout:301](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/auth/OAuthManager.kt:301)、[备份秘密恢复:2949](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2949)。

`removeInstance()` 清配置、API key、思考规则，却没有清 `oauth_prefs` 中按实例 ID 保存的结构化 token 与手工 Bearer。显式 Sign Out 有清这两项的入口，删除 Provider 没有调用。这里是普通删除后的稳定残留，区别于上一轮 PA07 的登出与在途刷新竞争。

残留不等于凭据公开泄漏。更具体的行为影响是：若以后通过保留同一实例 ID 的备份恢复配置，旧凭据仍可能参与身份选择；`restoreBackupProviderSecret()` 仅在现有秘密为空时写入备份中的 token，可能因删除前的残留而跳过新备份身份。普通新增使用随机 ID，不会自然复用这个键，不能说每次新增都会串账号。

方向：删除实例时通过现有认证管理入口清掉该实例持有的全部秘密，并使旧任务不能重新提交。待执行验收：写入 canary OAuth 与手工 token，删除后核对键的存在性；恢复同 ID 的无秘密／新秘密备份，确认不沿用旧身份。只记录存在性与身份摘要。

## CF04 — 刷新提交没有复核实例是否仍存在、端点是否仍相同

证据：[ProviderRepository.kt:2117](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2117)、[replaceEntries:1244](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1244)、[新增模型:1287](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1287)、[ProviderModelEntryEntity.kt:22](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ProviderModelEntryEntity.kt:22)。

刷新携带调用时的实例快照，发出请求后再按旧 ID `replaceEntries`。后者有配置锁，但不要求实例仍存在，也没有核对请求对应的端点／凭据版本。删除发生在网络等待期间时，晚到结果会重新加入该 ID 的模型，实例本身已不存在。

模型表对实例表有外键。完整保存会先写当前实例列表，再插入这些孤立模型，事务因此应拒绝提交。写入队列会记录失败并继续，但后续快照若仍携带孤立模型，也会继续失败。于是用户之后的其他配置修改可能只留在内存，重启丢失；这里没有声称已测到实际 SQLite 错误日志。

同一根因也影响改端点：从 A 发出的结果可覆盖已经切到 B 的模型目录并更新刷新时间，延后真正的 B 刷新。方向：提交时在现有锁内核对实例存在及请求版本，丢弃失效回复；仓库自身拒绝为不存在的实例写模型。

待执行验收：受控目录端点延迟回复，等待期间删除实例／改端点；回复后检查无孤立模型、其他配置可落盘，重启仍一致，目录来自当前端点。

## CF05 — 一个导入文件失败仍会留下副作用

证据：[ProviderRepository.kt:2601](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2601)、[模型解析:2659](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2659)、[ProviderListScreen.kt:122](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderListScreen.kt:122)。

外层 JSON 及类型、标签通过后，先 `addInstance()`，再保存 API key／OAuth 秘密，最后才遍历模型。`models.getJSONObject(i)` 遇到非对象元素会抛异常。可用合法外层 JSON、合法 providerType、非空 label 和 `"models":[1]` 表达这一静态触发条件。

界面捕获后报文件读取错误，ZIP 入口把该文件算作失败，但已经创建的实例及之前写入的秘密没有回滚。重试还会生成改名后的第二份实例。本项要求单份配置先验证再提交；ZIP 本身允许多文件部分成功，不将整个 ZIP 必须全有全无当成隐含需求。

方向：先解析并验证完整的单文件配置，确认可接受后才提交配置和秘密。待执行验收：含 canary key 的错误模型数组应失败且不留下实例或秘密；修正后导入只出现一份配置，重启一致。

## CF06 — JSON 分享格式没有覆盖当前可编辑配置

证据：[导出模型覆盖:2465](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2465)、[导出实例末尾:2548](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2548)、[导入实例:2591](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2591)、[导入覆盖:2692](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2692)、[配置字段:355](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:355)。

当前导出／导入遗漏 `azureMode`、`isEnabled`、`imageEndpointMode`／`imageEndpointResolved`。模型覆盖遗漏 `maxThinkingLevel`、`defaultThinkingLevel`、`contextLimitTokens`、`lastContextLimitTokens`；`hostedWebSearch` 虽被导出，导入构造器没有读取它。

上下文上限、默认思考等级、联网设置具有实际编辑入口：[ModelEntryDetailScreen.kt:80](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt:80)。Azure 模式也传入实际 Provider 工厂。往返因此不只是元数据变化：原 Azure 配置回到普通 API 路由，已禁用实例变成默认启用，手动上下文上限消失。图像探测缓存可以选择重置，但用户显式图像端点模式需要保留；不把丢弃设备上的明文 HTTP 信任授权计为缺陷。

方向：让当前分享格式完整表达有调用方的用户设置，保留旧格式缺字段的默认兼容。待执行验收：逐个设置非默认值，导出再导入，比较有效配置并检查实际路由、上下文和默认思考等级；保存脱敏 JSON 与结果摘要。

## CF07 — 空列表与未设置被合并

证据：[ProviderRepository.kt:2803](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2803)、[ModelEntry.model:399](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:399)、[编辑保存:157](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ModelEntryDetailScreen.kt:157)。

编辑页能够把原本有图像输入的模型覆盖成 `inputModalities=[]`，含义是显式关闭。导出也写出空数组。但导入辅助函数把所有空数组转换成 null，随后有效模型用 `overrides.inputModalities ?: baseModel.inputModalities` 重新继承原能力。

因此，在基础模型仍声明图像输入时，用户全部关闭输入模态，导出再导入会重新启用图像。这是“空”与“缺失”语义损失，与 CF06 完全遗漏某个字段不同，也不等于系统权限被提升。

方向：原生字段存在时保留其显式空数组，不让位图兼容回退覆盖已表达的用户意图。待执行验收：基础模型包含图像／音频，全部关闭后往返，有效模态仍为空；缺字段的旧格式继续按设计继承。

## CF08 — 正常异步加载漏掉思考规则缓存

证据：[ProviderRepository.kt:259](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:259)、[同步加载提前返回:690](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:690)、[缓存加载:1646](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1646)。

异步初始化采用磁盘配置并设置 `_configLoaded=true`，没有调用 `loadAllThinkingRulesIntoCache()`。仓库内该函数的唯一加载调用位于同步 `ensureConfigLoaded()` 末尾；若异步先完成，同步入口在开头直接返回，永远到不了缓存预热。

规则编辑页可以直接从数据库读到规则，请求构造却经 [ThinkingRuleResolver.kt:100](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/thinking/ThinkingRuleResolver.kt:100) 的内存缓存读取；缓存初始为空。编辑规则会重新发布缓存，所以表现可能是“页面中规则还在，重启后请求忽略，编辑后恢复”。同步入口先赢得启动竞争时会加载成功，因此不是每次启动都失败。

固定 openminis 快照 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c` 的[对应实现:300](https://github.com/openminis/openminis/blob/b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt#L300) 已在异步采用分支调用缓存加载。本轮读取本地 git 对象确认这一差异；上游注释里的设备观察不是本项目的设备验证证据。

方向：复用现有缓存加载，在每条成功采用配置的路径完成初始化。待执行验收：保存可辨识规则，重新启动并等异步配置加载完成，不打开编辑页，检查首个模型请求使用该规则；保留脱敏请求及启动事件顺序。

## CF09 — 自定义模型绕过统一唯一性约束

证据：[AddCustomModelScreen.kt:78](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/AddCustomModelScreen.kt:78)、[ProviderRepository.kt:1399](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:1399)、[canonicalizeConfig:742](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:742)、[ProviderConfigMapping.kt:120](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/db/ProviderConfigMapping.kt:120)。

`addEntry()` 只为非 custom 条目检查同实例同 modelId。真实的添加自定义模型页面以及 debug mutation 都提交 `isCustom=true`，于是可以重复添加已有 modelId。

保存把每个条目的 ID 规范化为 `instanceId/modelId`，没有合并或拒绝重复。内存会同时保留同 ID 的两条不同内容；Room 使用该复合 ID 作主键并以 REPLACE 写入，重启从数据库读取只剩后写入的一条。用户观察到的数量、覆盖与重启前后状态因此不一致。没有核实具体列表是否会因重复 key 崩溃，不把该可能性当成已确认结果。

`ModelsCollection` 的新增入口已有重复检查，但未覆盖所有仓库调用方。方向：在现有仓库锁内统一唯一性规则，重复创建给出明确结果；有意修改走修改入口。待执行验收：分别重复内置模型和自定义模型 ID，配置数量和已有覆盖不变，重启前后一致。

## CF10 — 免 key 的实际请求语义没有复用到目录刷新

证据：[ProviderConfig.kt:284](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/model/ProviderConfig.kt:284)、[usableApiKey:2395](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2395)、[refreshModels:2120](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2120)、[目录请求门槛:2169](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2169)。

自定义 OpenAI／Anthropic API-key 模式允许没有 key，`usableApiKey` 会按现有语义提供空字符串供实际 Provider 使用。但目录刷新直接读取 `loadApiKey`，只有非 null 才查询真实端点。

免 key 的私人兼容服务即使提供合法 `/models` 目录，也只会走 models.dev 回退。私人主机通常没有对应公共目录，新实例可能保持空模型列表，已有实例则得不到服务新模型。手工添加仍可用，不能据此说免 key 聊天完全不可用。

方向：复用实例已有的免 key 凭据语义，并让目录客户端按服务合同决定是否发送 Authorization。待执行验收：受控免 key 私有端点提供模型目录，实际刷新访问该端点并更新列表；保存服务请求计数与模型摘要。

## CF11 — 目录选择与聊天工厂的路线判断不一致

证据：[ProviderRepository.kt:2143](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/data/repository/ProviderRepository.kt:2143)、[ProviderFactory.kt:60](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/ProviderFactory.kt:60)、[OpenAIModelsApi.kt:34](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/provider/openai/OpenAIModelsApi.kt:34)。

聊天工厂只有 OAuth、没有手工 Bearer、没有自定义 basePath 才走 Codex 后端；有手工 Bearer 或自定义端点时走普通 OpenAI-compatible 路线。刷新只检查 openAI + oauth 这两个枚举，手工 Bearer 分支取内置 `codexOAuth` 模型目录；非手工分支也没有检查自定义端点就请求 Codex 目录。

只要这些目录非空，刷新会替换实例模型并返回成功，实际配置端点的模型还未查询。一个本来使用自定义服务 alpha／beta 模型的实例，可能因此被换成 Codex 名单。这里不推断模型服务真实是否接受某种 token，也不把错误目录选择直接认定为账户泄漏。

方向：目录来源使用与实际 Provider 创建相同的路线条件，Codex 回退只用于确实走 Codex 的实例。待执行验收：手工 Bearer + 自定义端点及无自定义端点的普通 API 路线分别返回可辨识目录，检查刷新访问及结果与聊天路线一致。

## CF12 — 导入没有统一读入边界，且同步阻塞结果回调

证据：[ProviderListScreen.kt:87](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderListScreen.kt:87)、[单文件读取:112](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderListScreen.kt:112)、[ProviderImportZip.kt:32](/home/jiale/projects/minis-for-android/src/android/app/src/main/java/com/openminis/app/ui/settings/ProviderImportZip.kt:32)。

单 JSON 分支直接 `openInputStream()?.bufferedReader()?.readText()`，没有字节上限，也没有 `use` 关闭输入流。结果回调里同步执行查询、读入、解析和仓库导入，未切换 IO dispatcher。ZIP 解压和逐文件导入也在同一回调同步完成。[AndroidX ActivityResultRegistry 源码](https://raw.githubusercontent.com/androidx/androidx/androidx-main/activity/activity/src/main/java/androidx/activity/result/ActivityResultRegistry.kt) 将结果分发标为 MainThread，并同步调用结果 callback；结合当前入口，读入和解析会占用界面线程。

ZIP 已有每项 4 MiB、累计 16 MiB、最多 512 项的解压限制、路径检查和临时目录清理，这些保护不能覆盖直接选择的单 JSON。超大或缓慢的文档提供方可能导致长时间无响应和内存压力；`catch(Exception)` 也不接住 OutOfMemoryError。没有测量实际 ANR 时间或崩溃阈值。

方向：使用已有有界读入能力，关闭流，把读入／解压／解析移到 IO 工作，再在界面线程发布进度和结果。待执行验收：受控文档源分别提供正常 JSON、超过上限的数据、缓慢分块读取和正常 ZIP；验证界面可响应、超限明确失败、流结束后关闭且无半导入配置。保存操作录像或界面事件记录及源端关闭记录，不使用云资源。

## 上游与现有保护的核对边界

上游仅比较本地只读 git 对象仓库 `/tmp/openminis-review-20261004` 的固定提交 `b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c`，不声称是此刻远端最新版。CF08 的差异有明确修复代码；本轮核对的上游删除清理、custom 新增检查、同步单文件读入片段仍可见相同缺口。没有把其他每一项都宣称为已确认的上下游共同问题。

配置锁、工作副本与 FIFO 写队列确实减少并发改列表和写入乱序；Room 的 replaceAll 事务也保证数据库内部替换不会部分落盘。它们不能自动保证加载成功、晚到结果有效、镜像与数据库一致或导入前验证完成。现有 WriteQueue 测试覆盖顺序和一次失败后继续工作，没有覆盖本文的双存储终止窗口、失效刷新外键和 JSON 往返；只阅读测试，没有执行。

与此前报告去重：PA07 是显式登出的在途认证写回，CF03 是普通实例删除后凭据残留；N03 是删除撤销对象不完整，CF06 是分享格式往返遗漏；BF 的备份文件边界不能替代此处单 Provider 导入边界。本文不追加论文的架构建议或重复统计旧项。

## 本轮全文阅读与总体进度

本轮新增 22 个全文记录，共 5,883 行；ProviderRepository 此前只有片段记录，这次分段读完全部 2,983 行。累计全文 283 / 1,546 个纳入文件，59,651 行，尚未完成全库审阅。片段阅读单独登记，不计全文。详见[覆盖台账](/home/jiale/projects/minis-for-android/docs/archive/2026-10-05-code-review-coverage.json)。

以下路径以 `/home/jiale/projects/minis-for-android/src/android/app/src/` 为前缀：

| 全文文件 | 行数 |
|---|---:|
| main/java/com/openminis/app/data/repository/ProviderRepository.kt | 2983 |
| main/java/com/openminis/app/data/repository/ProviderConfigWriteQueue.kt | 68 |
| main/java/com/openminis/app/data/model/ProviderConfig.kt | 473 |
| main/java/com/openminis/app/data/db/ProviderModelGroupEntity.kt | 25 |
| main/java/com/openminis/app/data/db/ProviderThinkingRuleEntity.kt | 50 |
| main/java/com/openminis/app/data/db/ProviderDatabase.kt | 120 |
| main/java/com/openminis/app/data/db/ProviderModelEntryEntity.kt | 39 |
| main/java/com/openminis/app/data/db/ProviderConfigMapping.kt | 268 |
| main/java/com/openminis/app/data/db/ProviderConfigDao.kt | 120 |
| main/java/com/openminis/app/data/db/ProviderConfigMetaEntity.kt | 24 |
| main/java/com/openminis/app/data/db/ProviderAgentLoopIdEntity.kt | 17 |
| main/java/com/openminis/app/data/db/ProviderInstanceEntity.kt | 37 |
| main/java/com/openminis/app/ui/settings/ProviderImportZip.kt | 178 |
| main/java/com/openminis/app/ui/settings/ProviderListScreen.kt | 538 |
| main/java/com/openminis/app/ui/settings/AddCustomModelScreen.kt | 102 |
| main/java/com/openminis/app/provider/openai/OpenAIModelsApi.kt | 267 |
| main/java/com/openminis/app/provider/ProviderModelsCache.kt | 64 |
| test/java/com/openminis/app/data/repository/ProviderConfigWriteQueueTest.kt | 60 |
| test/java/com/openminis/app/data/ProviderReorderTest.kt | 97 |
| test/java/com/openminis/app/data/SlotResolveCredentialFilterTest.kt | 70 |
| test/java/com/openminis/app/data/ModelSlotReorderTest.kt | 51 |
| test/java/com/openminis/app/backup/RestoreProviderTypeToleranceTest.kt | 232 |

本轮还核对 ThinkingRuleResolver、OpenAIProvider、ModelsCollection、ProviderMutationMethods、ModelEntryDetailScreen 的相关片段，均保持片段状态。交付前已核验目标提交、全部 1,670 个 tracked 文件内容及此前 19 份报告均未变；本轮只新增本报告并更新覆盖台账。未验证的设备行为继续保持未验证。
